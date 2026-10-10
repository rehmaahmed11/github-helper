package com.example.githubdesktop.ui.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.githubdesktop.data.RepoRepository

/**
 * Reads a SAF tree (the folder the user picked) into [PushOp]s and pushes it as one commit.
 *
 * Everything is read *before* anything is sent, so the request count is predictable, a failure
 * while reading changes nothing in the repository, and no half-uploaded folder is left behind.
 * Git cannot store empty directories, so they are counted and reported instead of vanishing.
 */
object FolderUploader {

    /** Guards against pathologically deep provider trees blowing the stack. */
    private const val MAX_DEPTH = 32

    private class Collected {
        val ops = mutableListOf<PushOp>()
        val skipped = mutableListOf<String>()
        var emptyDirs = 0
    }

    /**
     * @param basePath in-repo directory the contents land under. The picked folder itself
     *   becomes one more path segment, unless [basePath] is empty - in which case the
     *   folder's *contents* land at the repository root.
     */
    suspend fun uploadTree(
        ctx: Context, r: RepoRepository,
        owner: String, repo: String,
        basePath: String, treeUri: Uri, working: String, base: String,
        onProgress: (TransferProgress) -> Unit = {}
    ): OpResult {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: return OpResult.Failure("Could not open the selected folder.")

        val rootName = root.name?.takeIf { it.isNotBlank() } ?: "folder"
        val prefix = if (basePath.isBlank()) rootName else "$basePath/$rootName"

        onProgress(TransferProgress("Reading $rootName…", null))
        val data = Collected()
        collect(ctx, root, prefix, 0, data)

        if (data.ops.isEmpty()) {
            val empty = data.emptyDirs
            return OpResult.Failure(
                when {
                    data.skipped.isNotEmpty() ->
                        "Nothing could be uploaded: ${data.skipped.joinToString("; ")}"
                    empty > 0 ->
                        "That folder holds $empty empty folder(s) and no files - git does not " +
                            "store empty folders."
                    else -> "That folder was empty - nothing to upload."
                }
            )
        }

        onProgress(TransferProgress("Uploading ${data.ops.size} file(s)…", 0f))
        val result = SafHelper.push(
            r, owner, repo, working, base, data.ops,
            "Upload $rootName (${data.ops.size} files) via mobile", onProgress
        )

        val notes = mutableListOf<String>()
        if (data.skipped.isNotEmpty()) {
            notes += "Skipped ${data.skipped.size}: ${data.skipped.joinToString("; ")}"
        }
        if (data.emptyDirs > 0) {
            notes += "${data.emptyDirs} empty folder(s) are not stored (git has no empty folders)"
        }
        if (notes.isEmpty()) return result

        val joined = notes.joinToString(". ")
        return when (result) {
            is OpResult.Failure -> OpResult.Failure("${result.message} $joined")
            is OpResult.Success -> OpResult.Success("${result.message} $joined", result.workingBranch)
        }
    }

    private suspend fun collect(
        ctx: Context, dir: DocumentFile, path: String, depth: Int, data: Collected
    ) {
        if (depth > MAX_DEPTH) {
            data.skipped += "$path (deeper than $MAX_DEPTH levels)"
            return
        }
        val children = dir.listFiles()
        if (children.isNullOrEmpty()) {
            // The root itself is not "empty" in a way worth reporting as a skipped folder.
            if (depth > 0) data.emptyDirs++
            return
        }

        for (child in children) {
            val name = child.name
            if (!isUsableName(name)) {
                if (!name.isNullOrBlank()) data.skipped += "$name (name not usable as a path)"
                continue
            }
            if (child.isDirectory) {
                collect(ctx, child, "$path/$name", depth + 1, data)
            } else {
                collectFile(ctx, child, "$path/$name", data)
            }
        }
    }

    private suspend fun collectFile(ctx: Context, file: DocumentFile, path: String, data: Collected) {
        // The provider usually reports a size: skip what GitHub would refuse before spending
        // memory on it. An unknown size (0) is still attempted, and GitPush re-checks it.
        val declared = runCatching { file.length() }.getOrDefault(0L)
        if (declared > GitPush.MAX_FILE_BYTES) {
            data.skipped += "$path (${mb(declared)}, above GitHub's " +
                "${GitPush.MAX_FILE_BYTES / (1024 * 1024)} MB API limit)"
            return
        }
        val bytes = runCatching { SafHelper.readBytes(ctx, file.uri) }.getOrNull()
        if (bytes == null) {
            data.skipped += "$path (could not be read)"
        } else {
            data.ops += PushOp.Add(path, bytes)
        }
    }

    /** Provider-supplied names are arbitrary: refuse anything that could escape the directory. */
    private fun isUsableName(name: String?): Boolean =
        !name.isNullOrBlank() && name != "." && name != ".." && !name.contains('/')

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
