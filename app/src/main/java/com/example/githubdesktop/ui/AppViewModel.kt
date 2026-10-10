package com.example.githubdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.api.githubError
import com.example.githubdesktop.data.model.Branch
import com.example.githubdesktop.data.model.ContentItem
import com.example.githubdesktop.data.model.Repo
import com.example.githubdesktop.data.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppViewModel(
    private val repo: RepoRepository = RepoRepository()
) : ViewModel() {

    val repos = MutableStateFlow<List<Repo>>(emptyList())
    val user = MutableStateFlow<User?>(null)
    val loading = MutableStateFlow(false)

    /** Set by failed operations; the UI renders it and clears it. */
    val error = MutableStateFlow<String?>(null)

    /** Set by successful operations, for the same reason. */
    val notice = MutableStateFlow<String?>(null)

    val contents = MutableStateFlow<List<ContentItem>>(emptyList())
    val branches = MutableStateFlow<List<Branch>>(emptyList())

    fun reportError(message: String) {
        error.value = message
    }

    fun clearError() {
        error.value = null
    }

    fun reportNotice(message: String) {
        notice.value = message
    }

    fun clearNotice() {
        notice.value = null
    }

    fun loadUserAndRepos() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                val me = repo.me()
                if (me.isSuccessful) {
                    me.body()?.let { user.value = it }
                } else {
                    error.value = "Could not load your profile: ${me.githubError()}"
                }

                val list = repo.listRepos()
                if (list.isSuccessful) {
                    repos.value = list.body().orEmpty()
                } else {
                    error.value = "Could not load repositories: ${list.githubError()}"
                }
            } catch (e: Exception) {
                // A 401/5xx is not an exception - it arrives as a failed Response,
                // which is why the checks above matter.
                error.value = e.message ?: "Could not reach GitHub."
            } finally {
                loading.value = false
            }
        }
    }

    fun createRepo(name: String, desc: String?, private: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.createRepo(name, desc, private)
                if (r.isSuccessful) {
                    onDone()
                    loadUserAndRepos()
                } else {
                    error.value = "Could not create `$name`: ${r.githubError()}"
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not create `$name`."
            }
        }
    }

    fun deleteRepo(owner: String, name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.deleteRepo(owner, name)
                if (r.isSuccessful) {
                    repos.value = repos.value.filterNot { it.fullName == "$owner/$name" }
                    onDone()
                    notice.value = "Deleted `$owner/$name`."
                } else {
                    error.value = "Could not delete `$owner/$name`: ${r.githubError()}"
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not delete `$owner/$name`."
            }
        }
    }

    fun setVisibility(owner: String, name: String, private: Boolean) {
        viewModelScope.launch {
            try {
                val r = repo.setVisibility(owner, name, private)
                if (r.isSuccessful) {
                    notice.value = "`$owner/$name` is now ${if (private) "private" else "public"}."
                    loadUserAndRepos()
                } else {
                    // Re-sync: the toolbar icon was flipped optimistically.
                    error.value = "Could not change visibility: ${r.githubError()}"
                    loadUserAndRepos()
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not change visibility."
                loadUserAndRepos()
            }
        }
    }

    fun loadContents(owner: String, name: String, path: String, ref: String? = null) {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                // ref must be passed through: otherwise the file browser silently
                // shows the default branch no matter which branch is selected.
                val r = repo.listContents(owner, name, path, ref)
                if (r.isSuccessful) {
                    contents.value = r.body().orEmpty()
                } else {
                    error.value = "Could not open `/$path`: ${r.githubError()}"
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not open `/$path`."
            } finally {
                loading.value = false
            }
        }
    }

    fun loadBranches(owner: String, name: String) {
        viewModelScope.launch {
            try {
                val r = repo.listBranches(owner, name)
                if (r.isSuccessful) branches.value = r.body().orEmpty()
            } catch (e: Exception) {
                // Branch list is only informational; don't interrupt the user.
            }
        }
    }
}