package com.example.githubdesktop.data

import android.util.Base64
import com.example.githubdesktop.data.api.GitHubClient
import com.example.githubdesktop.data.model.*

class RepoRepository {

    suspend fun me() = GitHubClient.api.me()

    suspend fun listRepos() = GitHubClient.api.listRepos()
    suspend fun createRepo(name: String, desc: String?, private: Boolean) =
        GitHubClient.api.createRepo(CreateRepoBody(name, desc, private, true))

    suspend fun deleteRepo(owner: String, repo: String) =
        GitHubClient.api.deleteRepo(owner, repo)

    suspend fun setVisibility(owner: String, repo: String, private: Boolean) =
        GitHubClient.api.updateRepo(owner, repo, mapOf("private" to private))

    suspend fun listContents(owner: String, repo: String, path: String, ref: String?) =
        GitHubClient.api.listContents(owner, repo, path, ref)

    suspend fun getFile(owner: String, repo: String, path: String, ref: String?) =
        GitHubClient.api.getFile(owner, repo, path, ref)

    suspend fun putFile(
        owner: String, repo: String, path: String,
        contentBytes: ByteArray, sha: String?, branch: String, message: String
    ) = GitHubClient.api.putFile(
        owner, repo, path,
        PutFileBody(
            message,
            Base64.encodeToString(contentBytes, Base64.NO_WRAP),
            sha,
            branch
        )
    )

    suspend fun deleteFile(
        owner: String, repo: String, path: String, sha: String, branch: String, message: String
    ) = GitHubClient.api.deleteFile(
        owner, repo, path, DeleteFileBody(message, sha, branch)
    )

    suspend fun listBranches(owner: String, repo: String) =
        GitHubClient.api.listBranches(owner, repo)

    suspend fun createBranch(owner: String, repo: String, name: String, sha: String) =
        GitHubClient.api.createRef(owner, repo, CreateRefBody("refs/heads/$name", sha))

    suspend fun deleteBranch(owner: String, repo: String, name: String) =
        GitHubClient.api.deleteBranch(owner, repo, name)

    suspend fun merge(owner: String, repo: String, base: String, head: String) =
        GitHubClient.api.merge(owner, repo, MergeBody(base, head, "Merge $head into $base"))

    suspend fun listCommits(owner: String, repo: String, branch: String) =
        GitHubClient.api.listCommits(owner, repo, branch)

    /** Exact ref lookup (not the paginated branch list). Returns the raw response for error reporting. */
    suspend fun branchRef(owner: String, repo: String, branch: String) =
        GitHubClient.api.getBranchRef(owner, repo, branch)

    /**
     * The blob sha of [path] on [branch], or null if the file does not exist there.
     * GitHub requires this sha to overwrite an existing file.
     */
    suspend fun existingFileSha(owner: String, repo: String, path: String, branch: String): String? =
        runCatching {
            GitHubClient.api.getFile(owner, repo, path, branch).takeIf { it.isSuccessful }?.body()?.sha
        }.getOrNull()
}
