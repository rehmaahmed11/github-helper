package com.example.githubdesktop.ui.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.api.githubError

object FolderUploader {

    /** Guards against pathologically deep provider trees blowing the stack. */
    private const val MAX_DEPTH = 32

    /**
     * Recursively uploads the picked folder onto a new working branch.
     *
     * [basePath] is the in-repo directory the folder's contents land under;
     * the picked folder itself becomes one more path segment unless [basePath]
     * is empty, in which case its contents land at the repo root.
     */
    suspend fun uploadTree(
        ctx: Context, r: RepoRepository,
        owner: String, repo: String,
        basePath: String, treeUri: Uri, working: String, base: String
    ): OpResult {
        if (SafHelper.createWorkingBranch(r, owner, repo, working, base) == null) {
            return OpResult.Failure("Could not create branch `$working` from `$base`.")
        }

        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: return OpResult.Failure("Could not open the selected folder.")

        val rootName = root.name?.takeIf { it.isNotBlank() } ?: "folder"
        val prefix = if (basePath.isBlank()) rootName else "$basePath/$rootName"

        val tally = walk(ctx, r, owner, repo, prefix, root, working, 0)
        val failures = tally.failures
        val uploaded = tally.uploaded

        if (uploaded == 0) {
            runCatching { r.deleteBranch(owner, repo, working) }
            return if (failures.isEmpty()) {
                OpResult.Failure("That folder was empty - nothing to upload.")
            } else {
                OpResult.Failure("Upload failed for: ${failures.joinToString("; ")}")
            }
        }

        val plural = if (uploaded == 1) "file" else "files"
        val head = "Pushed $uploaded $plural to `$working`."
        return if (failures.isEmpty()) {
            OpResult.Success(head)
        } else {
            OpResult.Failure("$head Could not upload: ${failures.joinToString("; ")}")
        }
    }

    private class Tally(var uploaded: Int = 0, val failures: MutableList<String> = mutableListOf())

    private suspend fun walk(
        ctx: Context, r: RepoRepository,
        owner: String, repo: String,
        path: String, dir: DocumentFile, branch: String, depth: Int
    ): Tally {
        val tally = Tally()
        if (depth > MAX_DEPTH) return tally

        val children = dir.listFiles() ?: return tally

        for (child in children) {
            val name = child.name
            // Provider-supplied names are arbitrary: refuse anything that could
            // escape the target directory or is not a usable path segment.
            if (name.isNullOrBlank() || name == "." || name == ".." || name.contains('/')) {
                continue
            }

            if (child.isDirectory) {
                val sub = walk(ctx, r, owner, repo, "$path/$name", child, branch, depth + 1)
                tally.uploaded += sub.uploaded
                tally.failures += sub.failures
            } else {
                val bytes = runCatching { SafHelper.readBytes(ctx, child.uri) }.getOrNull()
                if (bytes == null) {
                    tally.failures += "$name (could not read)"
                    continue
                }
                val resp = r.putFile(
                    owner, repo, "$path/$name", bytes, null, branch, "Upload $name"
                )
                if (resp.isSuccessful) tally.uploaded++
                else tally.failures += "$name (${resp.githubError()})"
            }
        }
        return tally
    }
}