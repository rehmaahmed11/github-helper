package com.example.githubdesktop.ui.util

import android.util.Base64
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.api.GitHubFailure
import com.example.githubdesktop.data.api.githubError
import com.example.githubdesktop.data.api.toGitHubFailure
import com.example.githubdesktop.data.model.TreeEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest

/**
 * Pushes many files as ONE commit through the Git data API:
 *
 * ```
 * blobs (one per distinct content) -> tree -> commit -> ref
 * ```
 *
 * This replaced "one `PUT /contents/{path}` per file", which cost two API calls and one commit
 * *per file*. A few hundred files therefore tripped GitHub's secondary rate limit partway
 * through the folder, and every later write answered
 * `403 You have exceeded a secondary rate limit` - the same status code as a permissions
 * problem, which is what made it look like nothing worked.
 *
 * Other properties that matter:
 *  - nothing exists in the repository until the last call, so a failure part-way through
 *    leaves no half-uploaded folder and no orphan branch;
 *  - overwriting needs no `sha` lookup (another per-file call, and the source of
 *    "sha wasn't supplied" 422s);
 *  - identical bytes are uploaded once, keyed by their own git blob hash;
 *  - an empty repository works: with no commits there is no branch to branch *from*, so the
 *    first commit is created with no parent and the ref is created with it.
 */
object GitPush {

    /**
     * Largest file the app will upload. GitHub's own limit for a blob is 100 MB, but the API
     * takes base64 in a JSON body, so a 100 MB file means ~135 MB of string in memory on top of
     * the bytes - which on a phone means the process is killed and the upload "silently did
     * nothing". 20 MB keeps a big folder well inside any device's heap.
     */
    const val MAX_FILE_BYTES: Long = 20L * 1024 * 1024

    /** Beyond this a tree is rejected outright by GitHub. */
    private const val MAX_ENTRIES = 99_000

    /** Parallel blob uploads. Kept low: GitHub throttles bursty clients, not slow ones. */
    private const val PARALLEL_BLOBS = 3

    /**
     * @param branch branch to point at the new commit (created when it does not exist)
     * @param parent head commit before this push, or null for the first commit of the repo
     * @param baseBranch the branch [parent] came from; when [branch] equals it no merge is
     *   needed, and the result says so
     */
    suspend fun push(
        r: RepoRepository,
        owner: String,
        repo: String,
        branch: String,
        parent: String?,
        ops: List<PushOp>,
        message: String,
        baseBranch: String? = null,
        onProgress: (TransferProgress) -> Unit = {}
    ): OpResult {
        if (ops.isEmpty()) return OpResult.Failure("Nothing to upload - the selection was empty.")
        if (ops.size > MAX_ENTRIES) {
            return OpResult.Failure(
                "That is ${ops.size} files; GitHub accepts at most $MAX_ENTRIES in one commit."
            )
        }

        val adds = ops.filterIsInstance<PushOp.Add>()
        val rejected = mutableListOf<String>()
        val uploaded = mutableListOf<PushOp.Add>()

        // 1. One blob per distinct content.
        val shas = uploadBlobs(r, owner, repo, adds, rejected, uploaded, onProgress)

        // 2. The tree: adds carry their new blob sha, removals use sha = null.
        val entries = mutableListOf<TreeEntry>()
        for (op in ops) {
            when (op) {
                is PushOp.Add -> shas[op]?.let {
                    entries += TreeEntry(op.path, TreeEntry.MODE_FILE, "blob", it)
                }
                is PushOp.Remove -> entries += TreeEntry(op.path, sha = null)
            }
        }
        if (entries.isEmpty()) {
            val why = if (rejected.isEmpty()) "" else " " + rejected.joinToString("; ")
            return OpResult.Failure("Nothing could be uploaded.$why")
        }

        // 3. Tree on top of the current head, so untouched files stay in place.
        val tree = r.createTree(owner, repo, parent, entries)
        if (!tree.isSuccessful) {
            return OpResult.Failure(
                "Could not build the commit tree: " +
                    tree.githubError("GitHub rejected the file list")
            )
        }
        val treeSha = tree.body()?.sha
            ?: return OpResult.Failure("GitHub created no tree.")

        // 4. Commit it.
        val commit = r.createCommit(owner, repo, message, treeSha, parent)
        if (!commit.isSuccessful) {
            return OpResult.Failure(
                "Could not create the commit: " + commit.githubError("GitHub rejected the commit")
            )
        }
        val commitSha = commit.body()?.sha
            ?: return OpResult.Failure("GitHub created no commit.")

        // 5. Point the branch at the commit: move it if it exists, create it if it does not.
        val ref = r.branchRef(owner, repo, branch)
        val moved = when {
            ref.isSuccessful -> r.updateBranch(owner, repo, branch, commitSha)
            ref.code() == 404 -> r.createBranch(owner, repo, branch, commitSha)
            else -> return OpResult.Failure("Could not read branch `$branch`: ${ref.githubError()}")
        }
        if (!moved.isSuccessful) {
            val failure = moved.toGitHubFailure("GitHub would not update `$branch`")
            return OpResult.Failure(
                "Commit ${short(commitSha)} was created but `$branch` was not moved: " +
                    "${failure.render()} ${failure.fix}"
            )
        }

        val added = uploaded.size
        val removed = ops.count { it is PushOp.Remove }
        val summary = buildString {
            append(if (added == 1) "Pushed 1 file" else "Pushed $added files")
            if (removed > 0) append(" and removed $removed")
            append(" to `$branch` as one commit")
            if (rejected.isNotEmpty()) {
                append("; skipped ${rejected.size}: ${rejected.joinToString("; ")}")
            }
            append('.')
        }
        return OpResult.Success(summary, branch.takeIf { baseBranch != null && branch != baseBranch })
    }

    /** Outcome of one blob upload, merged back on the calling coroutine. */
    private class BlobResult(
        val keys: List<String>,
        val sha: String?,
        val problems: List<String>
    )

    /**
     * Returns the blob sha for each accepted op. [rejected] gets a readable reason per skipped
     * file and [uploaded] the ops that landed.
     */
    private suspend fun uploadBlobs(
        r: RepoRepository,
        owner: String,
        repo: String,
        adds: List<PushOp.Add>,
        rejected: MutableList<String>,
        uploaded: MutableList<PushOp.Add>,
        onProgress: (TransferProgress) -> Unit
    ): Map<PushOp.Add, String> {
        val byContent = LinkedHashMap<String, MutableList<PushOp.Add>>()
        for (add in adds) {
            val problem = checkPath(add.path) ?: tooBig(add)
            if (problem != null) {
                rejected += "${add.path} ($problem)"
            } else {
                byContent.getOrPut(gitBlobSha(add.bytes)) { mutableListOf() }.add(add)
            }
        }

        val out = HashMap<PushOp.Add, String>()
        val groups = byContent.entries.toList()
        if (groups.isEmpty()) return out

        groups.chunked(PARALLEL_BLOBS).forEachIndexed { index, batch ->
            onProgress(
                TransferProgress(
                    "Uploading ${batch.first().value.first().path.nameOnly()} " +
                        "(${index * PARALLEL_BLOBS + 1}-${minOf(groups.size, (index + 1) * PARALLEL_BLOBS)}" +
                        " of ${groups.size})",
                    index.toFloat() / groups.size
                )
            )
            val results = coroutineScope {
                batch.map { (contentSha, ops) ->
                    async { uploadOne(r, owner, repo, contentSha, ops) }
                }.awaitAll()
            }
            for (result in results) {
                rejected += result.problems
                val sha = result.sha ?: continue
                for (key in result.keys) {
                    byContent[key]?.forEach { op ->
                        out[op] = sha
                        uploaded += op
                    }
                }
            }
        }
        onProgress(TransferProgress("Writing commit", (groups.size - 1).toFloat() / groups.size))
        return out
    }

    private suspend fun uploadOne(
        r: RepoRepository,
        owner: String,
        repo: String,
        contentSha: String,
        ops: List<PushOp.Add>
    ): BlobResult {
        val names = ops.map { it.path }
        val response = runCatching {
            r.createBlob(owner, repo, Base64.encodeToString(ops.first().bytes, Base64.NO_WRAP))
        }
        if (response.isFailure) {
            val failure = GitHubFailure.ofThrowable(
            response.exceptionOrNull() ?: IllegalStateException("blob upload failed"),
            "network error"
        )
            return BlobResult(listOf(contentSha), null, ops.map { "${it.path} (${failure.render()})" })
        }
        val resp = response.getOrThrow()
        if (!resp.isSuccessful) {
            val message = resp.githubError("GitHub rejected the file")
            return BlobResult(listOf(contentSha), null, names.map { "$it ($message)" })
        }
        val sha = resp.body()?.sha ?: return BlobResult(listOf(contentSha), null, names.map { "${it.path} (no blob id)" })
        return BlobResult(listOf(contentSha), sha, emptyList())
    }

    /** Rejects anything that could escape the target directory. */
    private fun checkPath(path: String): String? = when {
        path.isBlank() -> "empty name"
        path.startsWith("/") -> "path must be relative"
        path.contains("..") -> "path may not contain '..'"
        path.contains('\\') -> "path must use /"
        path.any { it < ' ' } -> "name contains control characters"
        path.length > 1024 -> "path is too long"
        else -> null
    }

    private fun tooBig(add: PushOp.Add): String? =
        if (add.bytes.size.toLong() > MAX_FILE_BYTES) {
            "over ${MAX_FILE_BYTES / (1024 * 1024)} MB (the app's memory-safe cap)"
        } else {
            null
        }

    /** sha1("blob &lt;len&gt;\0" + bytes) - the id git itself gives the blob. */
    private fun gitBlobSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.nameOnly(): String = substringAfterLast('/')

    private fun short(sha: String): String = sha.take(7)
}
