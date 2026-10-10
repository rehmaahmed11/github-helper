package com.example.githubdesktop.data.model

import com.google.gson.annotations.SerializedName

/**
 * Request bodies are plain data classes: Gson reads the property names, so snake_case JSON
 * keys need [SerializedName]. Response models mark everything GitHub may omit as nullable,
 * because Gson fills absent fields with `null` regardless of the Kotlin type.
 */

data class User(
    val login: String,
    val id: Long,
    val name: String?,
    @SerializedName("avatar_url") val avatarUrl: String?
)

/** What the *current credential* may do with a repository - scope problems show up here. */
data class RepoPermissions(
    val admin: Boolean = false,
    val maintain: Boolean = false,
    val push: Boolean = false,
    val pull: Boolean = false,
    val triage: Boolean = false
)

data class Repo(
    val id: Long,
    val name: String,
    @SerializedName("full_name") val fullName: String,
    val owner: User,
    val private: Boolean,
    val description: String?,
    @SerializedName("default_branch") val defaultBranch: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("clone_url") val cloneUrl: String?,
    val permissions: RepoPermissions? = null
)

/**
 * `auto_init` decides whether GitHub seeds a README/`.gitignore`. The app can push the very
 * first commit itself (see `GitPush`), so leaving it off is fine - but `auto_init` keeps the
 * default branch name consistent with the account setting.
 */
data class CreateRepoBody(
    val name: String,
    val description: String?,
    val private: Boolean,
    @SerializedName("auto_init") val autoInit: Boolean = true,
    @SerializedName("has_issues") val hasIssues: Boolean = true
)

data class ContentItem(
    val name: String,
    val path: String,
    val sha: String,
    val size: Int = 0,
    val type: String,
    @SerializedName("download_url") val downloadUrl: String?
)

data class FileContent(
    val name: String,
    val path: String,
    val sha: String,
    val content: String?,
    val encoding: String?,
    val size: Int = 0
)

data class PutFileBody(
    val message: String,
    val content: String,
    val sha: String?,
    val branch: String
)

data class DeleteFileBody(
    val message: String,
    val sha: String,
    val branch: String
)

data class Branch(
    val name: String,
    val commit: BranchCommit,
    val protected: Boolean = false
)

data class BranchCommit(val sha: String, val url: String?)

data class CreateRefBody(val ref: String, val sha: String)

/** Body for `PATCH /git/refs/heads/{branch}`: move a branch to [sha]. */
data class UpdateRefBody(val sha: String, val force: Boolean = true)

data class GitRef(val ref: String, @SerializedName("object") val obj: GitObject?)

data class GitObject(val sha: String, val type: String?)

// ---- Git data API: one commit for a whole folder ----

/** `POST /git/blobs` - content-addressed, so sending the same bytes again is a no-op. */
data class BlobBody(val content: String, val encoding: String = "base64")

data class Blob(val sha: String, val url: String? = null, val size: Int? = null)

/**
 * One entry of a new tree. `mode` 100644 is an ordinary file, 100755 an executable,
 * 040000 a submodule/gitlink (`type` "commit").
 *
 * A **null [sha] deletes [path]** - and it has to stay in the JSON as an explicit null, so this
 * type is serialized by `TreeEntrySerializer`: Gson's default is to *omit* null fields, and
 * `{"path": "x"}` with no `sha` at all is a 422 from GitHub, not a removal.
 */
data class TreeEntry(
    val path: String,
    val mode: String = MODE_FILE,
    val type: String = "blob",
    val sha: String? = null
) {
    companion object {
        const val MODE_FILE = "100644"
        const val MODE_EXEC = "100755"
        const val MODE_SYMLINK = "120000"
    }
}

/** `base_tree` may be a commit sha or a tree sha; it is what makes the update incremental. */
data class TreeBody(@SerializedName("base_tree") val baseTree: String?, val tree: List<TreeEntry>)

data class GitTree(
    val sha: String,
    val url: String? = null,
    val truncated: Boolean = false,
    val tree: List<TreeEntry>? = null
)

/** `parents` is omitted (null) for the first commit of an empty repository. */
data class NewCommit(
    val message: String,
    val tree: String,
    val parents: List<String>? = null
)

data class GitCommit(val sha: String, val url: String? = null, @SerializedName("html_url") val htmlUrl: String?)

data class MergeBody(
    val base: String,
    val head: String,
    val commit_message: String? = null
)

data class MergeResult(
    val sha: String?,
    val merged: Boolean?,
    val message: String?
)

data class Commit(
    val sha: String,
    val commit: CommitInfo,
    @SerializedName("html_url") val htmlUrl: String?
)

data class CommitInfo(val message: String, val author: CommitAuthor?)

data class CommitAuthor(val name: String?, val email: String?, val date: String?)

// ---- rate limits (diagnostics) ----

data class RateLimitResource(
    val limit: Int = 0,
    val remaining: Int = 0,
    val used: Int = 0,
    val reset: Long = 0
)

data class RateLimitResources(val core: RateLimitResource? = null)

data class RateLimitResponse(
    @SerializedName("resources") val resources: RateLimitResources? = null,
    @SerializedName("rate") val rate: RateLimitResource? = null
)
