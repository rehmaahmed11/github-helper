package com.example.githubdesktop.data.api

import com.example.githubdesktop.data.model.Blob
import com.example.githubdesktop.data.model.BlobBody
import com.example.githubdesktop.data.model.Branch
import com.example.githubdesktop.data.model.Commit
import com.example.githubdesktop.data.model.ContentItem
import com.example.githubdesktop.data.model.CreateRefBody
import com.example.githubdesktop.data.model.CreateRepoBody
import com.example.githubdesktop.data.model.DeleteFileBody
import com.example.githubdesktop.data.model.FileContent
import com.example.githubdesktop.data.model.GitCommit
import com.example.githubdesktop.data.model.GitRef
import com.example.githubdesktop.data.model.GitTree
import com.example.githubdesktop.data.model.MergeBody
import com.example.githubdesktop.data.model.MergeResult
import com.example.githubdesktop.data.model.NewCommit
import com.example.githubdesktop.data.model.PutFileBody
import com.example.githubdesktop.data.model.RateLimitResponse
import com.example.githubdesktop.data.model.Repo
import com.example.githubdesktop.data.model.TreeBody
import com.example.githubdesktop.data.model.UpdateRefBody
import com.example.githubdesktop.data.model.User
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * GitHub REST API v3, api.github.com.
 *
 * Every method returns [Response] rather than the body directly: GitHub reports failures as
 * 4xx/5xx *responses*, and unwrapping them (or returning `Unit`) hides the reason. Callers
 * check `isSuccessful` and pass failures through [toGitHubFailure].
 *
 * Paths that carry user-visible names (file paths, branch names) use
 * `@Path(..., encoded = true)` so a `/` inside a branch or directory name is not escaped into
 * `%2F`, which GitHub's router does not match.
 */
interface GitHubApi {

    @GET("user")
    suspend fun me(): Response<User>

    /**
     * Capability probe for repository creation, with a deliberately invalid name.
     *
     * GitHub checks permissions *before* validating the payload, so this never creates
     * anything: 403 means the credential is not allowed to create repos, 422 means it is.
     */
    @POST("user/repos")
    suspend fun probeCreateRepo(@Body body: Map<String, String>): Response<ResponseBody>

    @GET("user/repos?per_page=100&sort=updated")
    suspend fun listRepos(): Response<List<Repo>>

    @POST("user/repos")
    suspend fun createRepo(@Body body: CreateRepoBody): Response<Repo>

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Repo>

    @DELETE("repos/{owner}/{repo}")
    suspend fun deleteRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Unit>

    @PATCH("repos/{owner}/{repo}")
    suspend fun updateRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: Map<String, Any>
    ): Response<Repo>

    /**
     * Directory listing. Pass "" for the repository root.
     *
     * Note: if [path] points at a single file, GitHub answers with one JSON object instead of
     * an array, so use [getFile] for files and reserve this for directories.
     */
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun listContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String = "",
        @Query("ref") ref: String? = null
    ): Response<List<ContentItem>>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Query("ref") ref: String? = null
    ): Response<FileContent>

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Body body: PutFileBody
    ): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "repos/{owner}/{repo}/contents/{path}", hasBody = true)
    suspend fun deleteFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Body body: DeleteFileBody
    ): Response<ResponseBody>

    @GET("repos/{owner}/{repo}/branches?per_page=100")
    suspend fun listBranches(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<List<Branch>>

    /**
     * Exact lookup of one branch. Unlike [listBranches] it is not paginated, so it still finds
     * the base branch when the repo has hundreds of branches, and a 404 here is how an
     * *empty* repository is detected (no commits, so no branch at all).
     */
    @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
    suspend fun getBranchRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch", encoded = true) branch: String
    ): Response<GitRef>

    @POST("repos/{owner}/{repo}/git/refs")
    suspend fun createRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateRefBody
    ): Response<ResponseBody>

    /** Moves a branch to [UpdateRefBody.sha]. `force = true` makes it a rewrite, not a fast-forward. */
    @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun updateRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch", encoded = true) branch: String,
        @Body body: UpdateRefBody
    ): Response<ResponseBody>

    @DELETE("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun deleteBranch(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch", encoded = true) branch: String
    ): Response<Unit>

    // ---- Git data API: used to push a whole folder as ONE commit ----

    /** Uploads one file's bytes. Content-addressed, so repeating it is harmless. */
    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createBlob(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: BlobBody
    ): Response<Blob>

    @POST("repos/{owner}/{repo}/git/trees")
    suspend fun createTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: TreeBody
    ): Response<GitTree>

    @POST("repos/{owner}/{repo}/git/commits")
    suspend fun createCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: NewCommit
    ): Response<GitCommit>

    @POST("repos/{owner}/{repo}/merges")
    suspend fun merge(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: MergeBody
    ): Response<MergeResult>

    @GET("repos/{owner}/{repo}/commits")
    suspend fun listCommits(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("sha") branch: String? = null,
        @Query("per_page") perPage: Int = 30
    ): Response<List<Commit>>

    /**
     * Rate-limit budget. Needs no scope, and is the honest answer to "is this a permissions
     * problem or is GitHub just throttling me?".
     */
    @GET("rate_limit")
    suspend fun rateLimit(): Response<RateLimitResponse>
}
