package com.example.githubdesktop.data

import android.util.Base64
import com.example.githubdesktop.data.api.GitHubClient
import com.example.githubdesktop.data.model.Blob
import com.example.githubdesktop.data.model.BlobBody
import com.example.githubdesktop.data.model.CreateRefBody
import com.example.githubdesktop.data.model.CreateRepoBody
import com.example.githubdesktop.data.model.DeleteFileBody
import com.example.githubdesktop.data.model.GitCommit
import com.example.githubdesktop.data.model.GitTree
import com.example.githubdesktop.data.model.MergeBody
import com.example.githubdesktop.data.model.NewCommit
import com.example.githubdesktop.data.model.PutFileBody
import com.example.githubdesktop.data.model.RateLimitResponse
import com.example.githubdesktop.data.model.Repo
import com.example.githubdesktop.data.model.TreeBody
import com.example.githubdesktop.data.model.TreeEntry
import com.example.githubdesktop.data.model.UpdateRefBody
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * The only place GitHub is talked to.
 *
 * Thin on purpose: each call returns the raw [Response] so the caller can inspect
 * `isSuccessful` and turn a failure into a `GitHubFailure` (status, GitHub's message,
 * missing scopes, retry delay). Swallowing that here is what made failures look like
 * "403, nothing happened".
 */
class RepoRepository {

    val api get() = GitHubClient.api

    // ---- account / repositories ----

    suspend fun me() = api.me()

    suspend fun listRepos() = api.listRepos()

    suspend fun getRepo(owner: String, repo: String): Response<Repo> = api.getRepo(owner, repo)

    suspend fun createRepo(name: String, desc: String?, private: Boolean): Response<Repo> =
        api.createRepo(CreateRepoBody(name, desc, private, autoInit = true))

    /**
     * Does this credential get to create repositories? Uses an intentionally invalid payload:
     * GitHub checks permissions before validating, so a 403 here means "not allowed" and a
     * 422 means "allowed, just a bad name". Nothing can be created by this call.
     */
    suspend fun probeCanCreateRepos(): Response<ResponseBody> = api.probeCreateRepo(mapOf("name" to ""))

    suspend fun deleteRepo(owner: String, repo: String) = api.deleteRepo(owner, repo)

    suspend fun setVisibility(owner: String, repo: String, private: Boolean) =
        api.updateRepo(owner, repo, mapOf("private" to private))

    suspend fun rateLimit(): Response<RateLimitResponse> = api.rateLimit()

    // ---- contents ----

    suspend fun listContents(owner: String, repo: String, path: String, ref: String?) =
        api.listContents(owner, repo, path, ref)

    suspend fun getFile(owner: String, repo: String, path: String, ref: String?) =
        api.getFile(owner, repo, path, ref)

    suspend fun putFile(
        owner: String, repo: String, path: String,
        contentBytes: ByteArray, sha: String?, branch: String, message: String
    ) = api.putFile(
        owner, repo, path,
        PutFileBody(
            message,
            // NO_WRAP: a trailing newline inside base64 is accepted by GitHub but makes the
            // stored blob differ from what the user saved.
            Base64.encodeToString(contentBytes, Base64.NO_WRAP),
            sha,
            branch
        )
    )

    suspend fun deleteFile(
        owner: String, repo: String, path: String, sha: String, branch: String, message: String
    ) = api.deleteFile(owner, repo, path, DeleteFileBody(message, sha, branch))

    /**
     * The blob sha of [path] on [branch], or null when it does not exist there.
     * GitHub requires this sha to overwrite a file through the Contents API.
     */
    suspend fun existingFileSha(owner: String, repo: String, path: String, branch: String): String? =
        runCatching {
            api.getFile(owner, repo, path, branch).takeIf { it.isSuccessful }?.body()?.sha
        }.getOrNull()

    // ---- branches / refs ----

    suspend fun listBranches(owner: String, repo: String) = api.listBranches(owner, repo)

    /** Head commit sha of [branch], or null when the branch does not exist. */
    suspend fun branchSha(owner: String, repo: String, branch: String): String? =
        runCatching {
            api.getBranchRef(owner, repo, branch).takeIf { it.isSuccessful }?.body()?.obj?.sha
        }.getOrNull()

    /** Raw response so a 404 on an empty repository can be told apart from a real failure. */
    suspend fun branchRef(owner: String, repo: String, branch: String) = api.getBranchRef(owner, repo, branch)

    suspend fun createBranch(owner: String, repo: String, name: String, sha: String): Response<ResponseBody> =
        api.createRef(owner, repo, CreateRefBody("refs/heads/$name", sha))

    suspend fun updateBranch(owner: String, repo: String, name: String, sha: String): Response<ResponseBody> =
        api.updateRef(owner, repo, name, UpdateRefBody(sha, force = true))

    suspend fun deleteBranch(owner: String, repo: String, name: String) = api.deleteBranch(owner, repo, name)

    suspend fun merge(owner: String, repo: String, base: String, head: String) =
        api.merge(owner, repo, MergeBody(base, head, "Merge $head into $base"))

    suspend fun listCommits(owner: String, repo: String, branch: String) =
        api.listCommits(owner, repo, branch)

    // ---- git data API (batched writes) ----

    suspend fun createBlob(owner: String, repo: String, base64: String): Response<Blob> =
        api.createBlob(owner, repo, BlobBody(base64))

    suspend fun createTree(
        owner: String, repo: String, baseTree: String?, entries: List<TreeEntry>
    ): Response<GitTree> = api.createTree(owner, repo, TreeBody(baseTree, entries))

    suspend fun createCommit(
        owner: String, repo: String, message: String, treeSha: String, parent: String?
    ): Response<GitCommit> = api.createCommit(owner, repo, NewCommit(message, treeSha, parent?.let { listOf(it) }))
}
