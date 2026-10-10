package com.example.githubdesktop.data.model

import com.google.gson.annotations.SerializedName

data class User(
    val login: String,
    val id: Long,
    val name: String?,
    @SerializedName("avatar_url") val avatarUrl: String?
)

data class Repo(
    val id: Long,
    val name: String,
    @SerializedName("full_name") val fullName: String,
    val owner: User,
    val private: Boolean,
    val description: String?,
    @SerializedName("default_branch") val defaultBranch: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("clone_url") val cloneUrl: String
)

data class CreateRepoBody(
    val name: String,
    val description: String?,
    val private: Boolean,
    @SerializedName("auto_init") val autoInit: Boolean = true
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
    val content: String,
    val encoding: String
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

data class BranchCommit(val sha: String, val url: String)

data class CreateRefBody(val ref: String, val sha: String)

data class GitRef(val ref: String, @SerializedName("object") val obj: GitObject)

data class GitObject(val sha: String, val type: String?)

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
    @SerializedName("html_url") val htmlUrl: String
)

data class CommitInfo(val message: String, val author: CommitAuthor?)
data class CommitAuthor(val name: String, val email: String, val date: String)
