package com.example.githubdesktop.ui.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * V1 "clone": download the repo zipball via the API and extract it into a
 * user-picked SAF folder. No storage permissions needed.
 * (True JGit clone with history comes in V2.)
 *
 * Two phases, each with a progress bar:
 *  1. download the zip to the app cache (exact when GitHub sends a length),
 *  2. extract it into the chosen folder (exact, by zip bytes consumed).
 */
object Cloner {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            // GitHub answers the zipball URL with a 302 to codeload.github.com.
            // OkHttp follows it and drops the Authorization header on the
            // cross-host hop, which is what we want (the redirect URL is signed).
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun downloadAndExtract(
        ctx: Context, token: String, owner: String, repo: String, destTreeUri: Uri,
        onProgress: (TransferProgress) -> Unit = {}
    ): OpResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(ctx, destTreeUri)
            ?: return@withContext OpResult.Failure("Could not open the selected folder.")
        if (!root.canWrite()) {
            return@withContext OpResult.Failure(
                "The selected folder is read-only. Pick a folder you can save into."
            )
        }

        val zip = File(ctx.cacheDir, "clone-$owner-$repo.zip")
        try {
            download(token, owner, repo, zip, onProgress)?.let {
                return@withContext OpResult.Failure(it)
            }
            extract(ctx, root, zip, owner, repo, onProgress)
        } catch (e: IOException) {
            OpResult.Failure("Download interrupted: ${e.message ?: "network error"}")
        } catch (e: Exception) {
            OpResult.Failure("Clone failed: ${e.message ?: e::class.java.simpleName}")
        } finally {
            zip.delete()
        }
    }

    /** Streams the zipball to [dest]. Returns an error message, or null on success. */
    private fun download(
        token: String, owner: String, repo: String, dest: File,
        onProgress: (TransferProgress) -> Unit
    ): String? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/zipball")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()

        val response = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            return "Could not reach GitHub: ${e.message ?: "network error"}"
        }

        response.use { resp ->
            // Must check the status: an expired token yields a small JSON error body,
            // which a ZipInputStream would silently read as "no entries".
            if (!resp.isSuccessful) {
                val message = runCatching { resp.body?.string() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONObject(it).optString("message") }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                return "Download failed (HTTP ${resp.code})" + (message?.let { ": $it" } ?: ".")
            }

            val body = resp.body ?: return "GitHub returned an empty archive."
            val total = body.contentLength().takeIf { it > 0 }

            onProgress(TransferProgress("Downloading $owner/$repo…", total?.let { 0f }))
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport >= 256 * 1024) {
                            lastReport = copied
                            onProgress(
                                TransferProgress(
                                    "Downloading $owner/$repo… ${mb(copied)}" +
                                        (total?.let { " of ${mb(it)}" } ?: ""),
                                    total?.let { copied.toFloat() / it }
                                )
                            )
                        }
                    }
                }
            }
        }
        return if (dest.length() == 0L) "GitHub returned an empty archive." else null
    }

    /** Extracts [zip] into [root]. Progress is by compressed bytes consumed. */
    private fun extract(
        ctx: Context, root: DocumentFile, zip: File,
        owner: String, repo: String, onProgress: (TransferProgress) -> Unit
    ): OpResult {
        val totalBytes = zip.length().coerceAtLeast(1L)
        var written = 0
        var skipped = 0
        var firstSkipped: String? = null

        val counting = CountingInputStream(FileInputStream(zip)) { read ->
            onProgress(
                TransferProgress(
                    "Extracting $owner/$repo…",
                    (read.toFloat() / totalBytes).coerceIn(0f, 1f)
                )
            )
        }

        try {
            ZipInputStream(counting).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // Drop the "owner-repo-sha/" prefix GitHub wraps archives in.
                    val parts = safeSegments(entry.name).drop(1)
                    if (parts.isNotEmpty() && !entry.isDirectory) {
                        if (writeEntry(ctx, root, parts, zis)) {
                            written++
                        } else {
                            skipped++
                            if (firstSkipped == null) firstSkipped = parts.joinToString("/")
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: IOException) {
            return OpResult.Failure(
                "Extraction stopped after $written file(s): ${e.message ?: e::class.java.simpleName}"
            )
        }

        return when {
            written == 0 && skipped == 0 -> OpResult.Failure("The archive contained no files.")
            written == 0 -> OpResult.Failure(
                "Could not write any files into the folder (first: $firstSkipped). " +
                    "Pick a different folder."
            )
            skipped > 0 -> OpResult.Failure(
                "Extracted $written file(s) from $owner/$repo, but $skipped could not be " +
                    "written (first: $firstSkipped)."
            )
            else -> OpResult.Success("Extracted $written file(s) from $owner/$repo.")
        }
    }

    /** Splits a zip entry path, dropping empty / traversal segments. */
    private fun safeSegments(entryName: String): List<String> =
        entryName.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }

    /**
     * Returns true if the file was written.
     *
     * Files are created with a generic MIME type. Some storage providers append
     * an extension that matches the MIME type (for example "x.kt" becoming
     * "x.kt.txt"), so the name is kept exactly as it is in the archive.
     */
    private fun writeEntry(
        ctx: Context, root: DocumentFile, parts: List<String>, zis: ZipInputStream
    ): Boolean {
        var dir = root
        for (i in 0 until parts.size - 1) {
            dir = dir.findFile(parts[i]) ?: dir.createDirectory(parts[i]) ?: return false
            if (!dir.isDirectory) return false
        }

        val name = parts.last()
        // Reuse an existing entry so a re-clone overwrites instead of producing "file (1)".
        val target = dir.findFile(name)
            ?: dir.createFile("application/octet-stream", name)
            ?: return false

        return try {
            // "wt" truncates: without it, an overwrite of a longer file keeps stale tail bytes.
            ctx.contentResolver.openOutputStream(target.uri, "wt")?.use { zis.copyTo(it) } != null
        } catch (e: Exception) {
            false
        }
    }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / (1024.0 * 1024.0))

    private class CountingInputStream(
        input: InputStream,
        private val onBytes: (Long) -> Unit
    ) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) tick(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) tick(n.toLong())
            return n
        }

        private fun tick(n: Long) {
            count += n
            // Throttle: about one update per 64 KB keeps the UI cheap.
            if (count - lastReport >= 64 * 1024) {
                lastReport = count
                onBytes(count)
            }
        }
    }
}
