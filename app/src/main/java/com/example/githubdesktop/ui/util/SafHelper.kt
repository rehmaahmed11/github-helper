package com.example.githubdesktop.ui.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.api.githubError
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helpers for writing to a repository through a working branch:
 * create the branch off [base], push the change, and let the caller decide
 * whether to merge and clean up.
 */
object SafHelper {

    private const val BRANCH_PREFIX = "mobile-edit"

    private val random = SecureRandom()

    /**
     * A unique working-branch name.
     *
     * The timestamp alone is only second-resolution, so two uploads started in
     * the same second produced the same name and GitHub rejected the second
     * branch creation with 422. The random suffix keeps it readable but unique.
     */
    fun newWorkingBranch(prefix: String = BRANCH_PREFIX): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // SecureRandom has no nextInt(from, until); the two-argument overload exists
        // only on kotlin.random.Random. Bound + zero-pad to a fixed 4 hex digits.
        val suffix = "%04x".format(random.nextInt(0x10000))
        return "$prefix-$stamp-$suffix"
    }

    fun readBytes(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not open $uri")

    fun fileName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    /**
     * Creates [working] off the current head of [base].
     *
     * Returns [OpResult.Success] when the branch exists, otherwise a Failure that
     * carries GitHub's own reason (for example "Reference already exists").
     */
    suspend fun createWorkingBranch(
        r: RepoRepository, owner: String, repo: String, working: String, base: String
    ): OpResult {
        val head = r.branchRef(owner, repo, base)
        if (!head.isSuccessful) {
            return OpResult.Failure(
                "Could not read branch `$base` to branch from: ${head.githubError()}"
            )
        }
        val sha = head.body()?.obj?.sha
            ?: return OpResult.Failure("GitHub did not return a commit for `$base`.")

        val created = r.createBranch(owner, repo, working, sha)
        return if (created.isSuccessful) {
            OpResult.Success("Created `$working`.")
        } else {
            OpResult.Failure(
                "Could not create branch `$working` from `$base`: ${created.githubError()}"
            )
        }
    }

    /**
     * Writes [bytes] to [path] on [branch]. Looks up the existing blob sha first,
     * because GitHub rejects an overwrite that does not carry it ("sha wasn't supplied").
     */
    suspend fun putFileOnBranch(
        r: RepoRepository, owner: String, repo: String, path: String,
        bytes: ByteArray, branch: String, message: String
    ): OpResult {
        val sha = r.existingFileSha(owner, repo, path, branch)
        return putFile(r, owner, repo, path, bytes, sha, branch, message)
    }

    suspend fun putFile(
        r: RepoRepository,
        owner: String, repo: String, path: String,
        bytes: ByteArray, sha: String?, branch: String, message: String
    ): OpResult {
        val resp = r.putFile(owner, repo, path, bytes, sha, branch, message)
        return if (resp.isSuccessful) {
            OpResult.Success("Saved `$path` on `$branch`.")
        } else {
            OpResult.Failure("Could not save `$path`: ${resp.githubError()}")
        }
    }

    /**
     * Creates [working] off [base] and pushes the picked [uris] into [basePath].
     * [onProgress] reports one step per file.
     */
    suspend fun pushFiles(
        ctx: Context, r: RepoRepository,
        owner: String, repo: String,
        basePath: String, uris: List<Uri>, working: String, base: String,
        onProgress: (TransferProgress) -> Unit = {}
    ): OpResult {
        if (uris.isEmpty()) return OpResult.Failure("No files were selected.")

        val started = createWorkingBranch(r, owner, repo, working, base)
        if (started is OpResult.Failure) return started

        var uploaded = 0
        val failures = mutableListOf<String>()

        uris.forEachIndexed { index, uri ->
            val name = fileName(ctx, uri)
            onProgress(
                TransferProgress(
                    "Uploading $name (${index + 1} of ${uris.size})",
                    index.toFloat() / uris.size
                )
            )
            val path = if (basePath.isBlank()) name else "$basePath/$name"
            val bytes = runCatching { readBytes(ctx, uri) }.getOrNull()
            if (bytes == null) {
                failures += "$name (could not read)"
                return@forEachIndexed
            }
            val resp = putFileOnBranch(r, owner, repo, path, bytes, working, "Add $name via mobile")
            if (resp is OpResult.Success) uploaded++ else failures += "$name (${(resp as OpResult.Failure).message})"
        }
        onProgress(TransferProgress("Finishing…", 1f))

        return finishPush(r, owner, repo, working, uploaded, failures)
    }

    suspend fun deleteViaBranch(
        r: RepoRepository,
        owner: String, repo: String,
        path: String, sha: String, working: String, base: String
    ): OpResult {
        val started = createWorkingBranch(r, owner, repo, working, base)
        if (started is OpResult.Failure) return started
        val resp = r.deleteFile(owner, repo, path, sha, working, "Delete $path via mobile")
        if (!resp.isSuccessful) {
            // Don't leave an empty branch behind for a delete that never happened.
            runCatching { r.deleteBranch(owner, repo, working) }
            return OpResult.Failure("Could not delete `$path`: ${resp.githubError()}")
        }
        return OpResult.Success("Deleted `$path` on `$working`.")
    }

    suspend fun mergeBranch(
        r: RepoRepository, owner: String, repo: String, base: String, head: String
    ): OpResult {
        val resp = r.merge(owner, repo, base, head)
        if (!resp.isSuccessful) {
            return OpResult.Failure("Could not merge `$head` into `$base`: ${resp.githubError()}")
        }
        val body = resp.body()
        if (body?.merged == false) {
            return OpResult.Failure(
                "GitHub could not merge `$head` into `$base`: " +
                    (body.message ?: "the branches conflict, or the merge was blocked.")
            )
        }
        return OpResult.Success("Merged `$head` into `$base`.")
    }

    suspend fun deleteBranch(
        r: RepoRepository, owner: String, repo: String, branch: String
    ): OpResult {
        val resp = r.deleteBranch(owner, repo, branch)
        return if (resp.isSuccessful) {
            OpResult.Success("Deleted branch `$branch`.")
        } else {
            OpResult.Failure("Could not delete branch `$branch`: ${resp.githubError()}")
        }
    }

    /**
     * Turns per-file counts into one report, and drops the working branch
     * entirely if nothing actually made it up.
     */
    suspend fun finishPush(
        r: RepoRepository,
        owner: String, repo: String, working: String,
        uploaded: Int, failures: List<String>
    ): OpResult {
        if (uploaded == 0) {
            runCatching { r.deleteBranch(owner, repo, working) }
            return if (failures.isEmpty()) {
                OpResult.Failure("Nothing was uploaded.")
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
}
