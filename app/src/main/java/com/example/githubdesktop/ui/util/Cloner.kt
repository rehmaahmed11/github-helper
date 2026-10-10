package com.example.githubdesktop.ui.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * V1 "clone": download the repo zipball via the API and extract it into a
 * user-picked SAF folder. No storage permissions needed.
 * (True JGit clone with history comes in V2.)
 */
object Cloner {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    private val MIME_BY_EXTENSION = mapOf(
        "md" to "text/markdown", "txt" to "text/plain", "json" to "application/json",
        "kt" to "text/plain", "kts" to "text/plain", "java" to "text/plain",
        "xml" to "text/xml", "html" to "text/html", "css" to "text/css",
        "yml" to "text/yaml", "yaml" to "text/yaml", "sh" to "text/x-sh"
    )

    suspend fun downloadAndExtract(
        ctx: Context, token: String, owner: String, repo: String, destTreeUri: Uri
    ): OpResult = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo/zipball"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()

        val response = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            return@withContext OpResult.Failure(
                "Could not reach GitHub: ${e.message ?: "network error"}"
            )
        }

        // Must check the status: an expired token yields a small JSON error body,
        // which a ZipInputStream would silently read as "no entries".
        if (!response.isSuccessful) {
            val detail = runCatching { response.body?.string() }.getOrNull()
            return@withContext OpResult.Failure(
                "Download failed (HTTP ${response.code})" +
                    (detail?.let { if (it.isBlank()) "" else ": ${it.take(200)}" } ?: ".")
            )
        }

        val body = response.body
            ?: return@withContext OpResult.Failure("GitHub returned an empty archive.")
        val root = DocumentFile.fromTreeUri(ctx, destTreeUri)
            ?: return@withContext OpResult.Failure("Could not open the selected folder.")

        var written = 0
        try {
            body.byteStream().use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val segments = safeSegments(entry.name)
                        // Drop the "owner-repo-sha/" prefix GitHub wraps archives in.
                        val parts = segments.drop(1)
                        if (parts.isNotEmpty() && !entry.isDirectory) {
                            if (writeEntry(ctx, root, parts, zis)) written++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext OpResult.Failure(
                "Extraction stopped after $written file(s): ${e.message ?: e::class.java.simpleName}"
            )
        }

        if (written == 0) {
            OpResult.Failure("The archive contained no files.")
        } else {
            OpResult.Success("Extracted $written file(s) from $owner/$repo.")
        }
    }

    /** Splits a zip entry path, dropping empty / traversal segments. */
    private fun safeSegments(entryName: String): List<String> =
        entryName.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }

    /**
     * Returns true if the file was written.
     *
     * The original used `createDirectory(seg)!!`, which threw (and aborted the
     * whole download) whenever a provider refused to create a directory - and
     * `createFile` returns null when a same-named file already exists.
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
        // Reuse an existing entry so a re-extract overwrites instead of failing
        // or silently producing "file (1)".
        val target = dir.findFile(name) ?: dir.createFile(mimeFor(name), name) ?: return false

        return try {
            ctx.contentResolver.openOutputStream(target.uri)?.use { zis.copyTo(it) } != null
        } catch (e: Exception) {
            false
        }
    }

    private fun mimeFor(name: String): String =
        MIME_BY_EXTENSION[name.substringAfterLast('.', "").lowercase(Locale.US)]
            ?: "application/octet-stream"
}