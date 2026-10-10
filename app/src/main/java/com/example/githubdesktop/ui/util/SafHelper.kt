package com.example.githubdesktop.ui.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.api.githubError
import com.example.githubdesktop.data.api.toGitHubFailure
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writing to a repository: decide where the commit goes, hand the work to [GitPush], then let
 * the caller decide whether to merge.
 *
 * Two rules the rest of the app depends on:
 *  - a push never targets the base branch directly, unless the repository is empty (there is
 *    nothing to branch *from*, and an empty repository cannot merge);
 *  - a failure says why, in GitHub's own words, plus what to do about it.
 */
object SafHelper {

    private const val BRANCH_PREFIX = "mobile-edit"

    /** Files are held in memory while being base64 encoded, so big folders are pushed in parts. */
    private const val BATCH_BYTES = 8L * 1024 * 1024

    private val random = SecureRandom()

    /**
     * A unique working-branch name.
     *
     * A timestamp alone is second-resolution, so two uploads started in the same second produced
     * the same name and GitHub rejected the second one with "Reference already exists" (422).
     * The random suffix keeps the name readable but unique.
     */
    fun newWorkingBranch(prefix: String = BRANCH_PREFIX): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // SecureRandom has no nextInt(from, until); that two-argument overload exists only on
        // kotlin.random.Random. Bound it, then zero-pad to a fixed 4 hex digits.
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
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /**
     * Chooses the target ref and its current head, then pushes [ops] as one commit (more, if the
     * payload is too big to keep in memory at once).
     *
     * The result carries `workingBranch` when a merge decision is pending, and null when the
     * change already sits where it belongs.
     */
    suspend fun push(
        r: RepoRepository,
        owner: String,
        repo: String,
        working: String,
        base: String,
        ops: List<PushOp>,
        message: String,
        onProgress: (TransferProgress) -> Unit = {}
    ): OpResult {
        if (ops.isEmpty()) return OpResult.Failure("Nothing to upload - the selection was empty.")

        val ref = r.branchRef(owner, repo, base)
        val branch: String
        val headSha: String?
        when {
            ref.isSuccessful -> {
                branch = working
                headSha = ref.body()?.obj?.sha
                    ?: return OpResult.Failure("GitHub returned no commit for branch `$base`.")
            }
            // A brand-new repository has no commits, so the first commit has to create the
            // default branch itself. Ask GitHub what that name is rather than trusting the
            // cached repo list: an account whose default is `master` must not get a `main`.
            ref.code() == 404 -> {
                val fresh = runCatching { r.getRepo(owner, repo) }.getOrNull()
                val named = if (fresh != null && fresh.isSuccessful) fresh.body()?.defaultBranch else null
                branch = named ?: base
                headSha = null
            }
            else -> return OpResult.Failure("Could not read branch `$base`: ${ref.githubError()}")
        }

        val batches = ops.chunkedBySize(BATCH_BYTES)
        var added = 0
        var removed = 0
        var parent = headSha
        val problems = mutableListOf<String>()

        batches.forEachIndexed { index, batch ->
            val label = if (batches.size == 1) message else "$message (part ${index + 1} of ${batches.size})"
            val result = GitPush.push(r, owner, repo, branch, parent, batch, label, base, onProgress)
            when (result) {
                is OpResult.Success -> {
                    added += batch.count { it is PushOp.Add }
                    removed += batch.count { it is PushOp.Remove }
                    // Each later batch must build on the commit just made, not on the base.
                    parent = r.branchSha(owner, repo, branch) ?: parent
                }
                is OpResult.Failure -> {
                    // Nothing landed at all: report it as-is. Otherwise keep what did land.
                    if (index == 0) return result
                    problems += "part ${index + 1}: ${result.message}"
                }
            }
        }

        if (added == 0 && removed == 0) {
            return OpResult.Failure(problems.firstOrNull() ?: "Nothing was uploaded.")
        }

        val text = buildString {
            append(if (added == 1) "Pushed 1 file" else "Pushed $added files")
            if (removed > 0) append(" and removed $removed")
            append(" to `$branch`")
            if (batches.size > 1) append(" in ${batches.size} commits")
            if (problems.isNotEmpty()) append("; ${problems.joinToString("; ")}")
            append('.')
        }
        return OpResult.Success(text, branch.takeIf { branch != base })
    }

    /** Groups ops so each batch stays under [maxBytes]; an oversized single file stays alone. */
    private fun List<PushOp>.chunkedBySize(maxBytes: Long): List<List<PushOp>> {
        val out = mutableListOf<List<PushOp>>()
        var current = mutableListOf<PushOp>()
        var size = 0L
        for (op in this) {
            val bytes = (op as? PushOp.Add)?.bytes?.size?.toLong() ?: 0L
            if (current.isNotEmpty() && size + bytes > maxBytes) {
                out += current
                current = mutableListOf()
                size = 0L
            }
            current += op
            size += bytes
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * Creates a working branch and pushes the picked [uris] into [basePath] as one commit.
     * Files that cannot be read are listed in the result instead of silently dropping.
     */
    suspend fun pushFiles(
        ctx: Context, r: RepoRepository,
        owner: String, repo: String,
        basePath: String, uris: List<Uri>, working: String, base: String,
        onProgress: (TransferProgress) -> Unit = {}
    ): OpResult {
        if (uris.isEmpty()) return OpResult.Failure("No files were selected.")

        val ops = mutableListOf<PushOp>()
        val unreadable = mutableListOf<String>()
        uris.forEach { uri ->
            val name = fileName(ctx, uri)
            val path = if (basePath.isBlank()) name else "$basePath/$name"
            val bytes = runCatching { readBytes(ctx, uri) }.getOrNull()
            if (bytes == null) unreadable += "$name (could not be read)" else ops += PushOp.Add(path, bytes)
        }
        if (ops.isEmpty()) {
            return OpResult.Failure(
                "Nothing could be read from the selection" +
                    if (unreadable.isEmpty()) "." else ": ${unreadable.joinToString("; ")}"
            )
        }

        val result = push(r, owner, repo, working, base, ops, "Upload ${ops.size} file(s) via mobile", onProgress)
        if (unreadable.isEmpty()) return result
        // An explicit branch on the sealed type: a disjunctive `if` above would not smart-cast.
        return when (result) {
            is OpResult.Success -> OpResult.Success(
                "${result.message} Skipped ${unreadable.size}: ${unreadable.joinToString("; ")}",
                result.workingBranch
            )
            is OpResult.Failure -> result
        }
    }

    /** Deletes [path] by removing it in one commit on a working branch. */
    suspend fun deleteViaBranch(
        r: RepoRepository,
        owner: String, repo: String,
        path: String, working: String, base: String
    ): OpResult = push(r, owner, repo, working, base, listOf(PushOp.Remove(path)), "Delete $path via mobile")

    suspend fun mergeBranch(
        r: RepoRepository, owner: String, repo: String, base: String, head: String
    ): OpResult {
        val resp = r.merge(owner, repo, base, head)
        if (!resp.isSuccessful) {
            val failure = resp.toGitHubFailure("merge failed")
            return OpResult.Failure(
                "Could not merge `$head` into `$base`: ${failure.render()} ${failure.fix}"
            )
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
}
