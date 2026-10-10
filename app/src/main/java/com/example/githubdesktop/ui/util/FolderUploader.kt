package com.example.githubdesktop.ui.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.githubdesktop.data.RepoRepository

object FolderUploader {

    /** Guards against pathologically deep provider trees blowing the stack. */
    private const val MAX_DEPTH = 32

    /**
     * Recursively uploads the picked folder onto a new working branch.
     *
     * [basePath] is the in-repo directory the folder's contents land under;
     * the picked folder itself becomes one more path segment unless [basePath]
     * is empty, in which case its contents land at the repo root.
     *
     * [onProgress] is called once per file with a determinate fraction.
     */
    suspend fun uploadTree(
        ctx: Context, r: RepoRepository,
        owner: String, repo: String,
        basePath: String, treeUri: Uri, working: String, base: String,
        onProgress: (TransferProgress) -> Unit = {}
    ): OpResult {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: return OpResult.Failure("Could not open the selected folder.")

        // Count first so the progress bar can be exact, and so an empty folder
        // is reported before any branch is created for it.
        val total = countFiles(root, 0)
        if (total == 0) {
            return OpResult.Failure("That folder was empty - nothing to upload.")
        }

        val started = SafHelper.createWorkingBranch(r, owner, repo, working, base)
        if (started is OpResult.Failure) return started

        val rootName = root.name?.takeIf { it.isNotBlank() } ?: "folder"
        val prefix = if (basePath.isBlank()) rootName else "$basePath/$rootName"

        val tracker = Tracker(total, onProgress)
        val tally = walk(ctx, r, owner, repo, prefix, root, working, 0, tracker)
        onProgress(TransferProgress("Finishing…", 1f))

        return SafHelper.finishPush(r, owner, repo, working, tally.uploaded, tally.failures)
    }

    private class Tracker(val total: Int, val onProgress: (TransferProgress) -> Unit) {
        var done = 0

        fun begin(name: String) {
            onProgress(
                TransferProgress("Uploading $name (${done + 1} of $total)", done.toFloat() / total)
            )
        }

        fun finishOne() {
            done++
        }
    }

    private class Tally(var uploaded: Int = 0, val failures: MutableList<String> = mutableListOf())

    /** Counts uploadable files using the same name rules as [walk]. */
    private fun countFiles(dir: DocumentFile, depth: Int): Int {
        if (depth > MAX_DEPTH) return 0
        val children = dir.listFiles() ?: return 0
        var count = 0
        for (child in children) {
            val name = child.name
            if (!isUsableName(name)) continue
            count += if (child.isDirectory) countFiles(child, depth + 1) else 1
        }
        return count
    }

    /**
     * Provider-supplied names are arbitrary: refuse anything that could escape
     * the target directory or is not a usable path segment.
     */
    private fun isUsableName(name: String?): Boolean =
        !name.isNullOrBlank() && name != "." && name != ".." && !name.contains('/')

    private suspend fun walk(
        ctx: Context, r: RepoRepository,
        owner: String, repo: String,
        path: String, dir: DocumentFile, branch: String, depth: Int, tracker: Tracker
    ): Tally {
        val tally = Tally()
        if (depth > MAX_DEPTH) return tally

        val children = dir.listFiles() ?: return tally

        for (child in children) {
            val name = child.name
            if (!isUsableName(name)) continue
            name!!

            if (child.isDirectory) {
                val sub = walk(ctx, r, owner, repo, "$path/$name", child, branch, depth + 1, tracker)
                tally.uploaded += sub.uploaded
                tally.failures += sub.failures
            } else {
                tracker.begin(name)
                val bytes = runCatching { SafHelper.readBytes(ctx, child.uri) }.getOrNull()
                if (bytes == null) {
                    tally.failures += "$name (could not read)"
                } else {
                    val result = SafHelper.putFileOnBranch(
                        r, owner, repo, "$path/$name", bytes, branch, "Upload $name"
                    )
                    when (result) {
                        is OpResult.Success -> tally.uploaded++
                        is OpResult.Failure -> tally.failures += "$name (${result.message})"
                    }
                }
                tracker.finishOne()
            }
        }
        return tally
    }
}
