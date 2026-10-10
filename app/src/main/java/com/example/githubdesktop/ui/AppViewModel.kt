package com.example.githubdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.githubdesktop.data.AccessReport
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.api.GitHubFailure
import com.example.githubdesktop.data.api.FailureKind
import com.example.githubdesktop.data.api.toGitHubFailure
import com.example.githubdesktop.data.model.Branch
import com.example.githubdesktop.data.model.ContentItem
import com.example.githubdesktop.data.model.Repo
import com.example.githubdesktop.data.model.User
import com.example.githubdesktop.ui.util.OpResult
import com.example.githubdesktop.ui.util.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen state, and the one place that decides how a GitHub failure is *explained*.
 *
 * Rules the UI depends on:
 *  - a request that GitHub already told us this credential cannot make is refused before it is
 *    sent, with the reason and the fix (rather than a 403 snack);
 *  - long transfers run on [Dispatchers.IO] and report progress, so reading a folder never
 *    freezes the screen;
 *  - [access] holds what the token can actually do, measured against GitHub.
 */
class AppViewModel(
    private val repo: RepoRepository = RepoRepository()
) : ViewModel() {

    val repos = MutableStateFlow<List<Repo>>(emptyList())
    val user = MutableStateFlow<User?>(null)
    val loading = MutableStateFlow(false)

    /** Set by failed operations; the UI renders it and clears it. */
    val error = MutableStateFlow<String?>(null)

    /** The structured version of [error]: what to do next, not just that something failed. */
    val lastFailure = MutableStateFlow<GitHubFailure?>(null)

    /** Set by successful operations, for the same reason. */
    val notice = MutableStateFlow<String?>(null)

    /** Active upload/download, shown as a progress bar. Null when idle. */
    val progress = MutableStateFlow<TransferProgress?>(null)

    /** True while a repository is being created. */
    val creating = MutableStateFlow(false)

    /** Shown inside the New repository dialog so the reason is visible there, not behind it. */
    val createError = MutableStateFlow<String?>(null)

    val contents = MutableStateFlow<List<ContentItem>>(emptyList())
    val branches = MutableStateFlow<List<Branch>>(emptyList())

    /** Measured capabilities of the stored credential; null until the first probe. */
    val access = MutableStateFlow<AccessReport?>(null)
    val checkingAccess = MutableStateFlow(false)

    fun reportError(message: String) {
        error.value = message
    }

    fun reportFailure(failure: GitHubFailure) {
        lastFailure.value = failure
        error.value = failure.render()
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

    /**
     * Asks GitHub what this token may do. Cheap (3 calls) and the only way to tell a scope
     * problem from a rate limit from a genuine "no such file".
     */
    fun refreshAccess() {
        if (checkingAccess.value) return
        viewModelScope.launch {
            checkingAccess.value = true
            access.value = runCatching { withContext(Dispatchers.IO) { AccessReport.probe(repo) } }
                .getOrNull()
            checkingAccess.value = false
        }
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
                    val failure = me.toGitHubFailure("could not load your profile")
                    lastFailure.value = failure
                    error.value = "Could not load your profile: ${failure.render()}"
                }

                val list = repo.listRepos()
                if (list.isSuccessful) {
                    repos.value = list.body().orEmpty()
                } else {
                    val failure = list.toGitHubFailure("could not load repositories")
                    lastFailure.value = failure
                    error.value = "Could not load repositories: ${failure.render()}"
                }
            } catch (e: Exception) {
                // A 401/403/5xx is not an exception - it arrives as a failed Response, which is
                // why the checks above matter. This branch is for transport failures.
                lastFailure.value = GitHubFailure.ofThrowable(e, "could not reach GitHub")
                error.value = e.message ?: "Could not reach GitHub."
            } finally {
                loading.value = false
            }

            // Refresh capabilities whenever the repo list is refreshed, so the banner and the
            // rate-limit countdown stay honest.
            if (access.value == null || access.value?.failure != null) refreshAccess()
        }
    }

    fun clearCreateError() {
        createError.value = null
    }

    /**
     * Creates a repository. GitHub's `POST /user/repos` needs the `repo` scope and is refused for
     * app tokens, so a blocked credential is reported from [access] without a pointless request.
     */
    fun createRepo(name: String, desc: String?, private: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            clearCreateError()
            val blocked = access.value?.takeIf { it.createBlocked }
            if (blocked != null) {
                createError.value = "GitHub will not let this sign-in create repositories. " +
                    (blocked.advice ?: blocked.blockedReason ?: "")
                return@launch
            }

            creating.value = true
            try {
                val r = withContext(Dispatchers.IO) { repo.createRepo(name, desc, private) }
                if (r.isSuccessful) {
                    notice.value = "Created `$name`."
                    onDone()
                    loadUserAndRepos()
                } else {
                    val failure = r.toGitHubFailure("GitHub refused the request")
                    lastFailure.value = failure
                    createError.value = "Could not create `$name`: ${failure.render()}. ${failure.fix}"
                    // A surprise 403 here almost always means the capability probe was stale.
                    if (failure.kind == FailureKind.ScopeMissing ||
                        failure.kind == FailureKind.IntegrationBlocked ||
                        failure.kind == FailureKind.TokenInvalid
                    ) {
                        refreshAccess()
                    }
                }
            } catch (e: Exception) {
                lastFailure.value = GitHubFailure.ofThrowable(e, "network error")
                createError.value = "Could not create `$name`: ${e.message ?: "network error"}. " +
                    "Check the connection and try again."
            } finally {
                creating.value = false
            }
        }
    }

    /**
     * Runs an upload or download with a progress bar, off the main thread. [work] receives a
     * `report` callback; [onResult] gets the outcome on the main thread.
     */
    fun runTransfer(
        work: suspend (report: (TransferProgress) -> Unit) -> OpResult,
        onResult: (OpResult) -> Unit
    ) {
        viewModelScope.launch {
            progress.value = TransferProgress("Starting…", null)
            val result = try {
                withContext(Dispatchers.IO) { work { progress.value = it } }
            } catch (e: Exception) {
                OpResult.Failure(e.message ?: "Operation failed.")
            } finally {
                progress.value = null
            }
            if (result is OpResult.Failure) {
                error.value = result.message
            }
            onResult(result)
        }
    }

    fun deleteRepo(owner: String, name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { repo.deleteRepo(owner, name) }
                if (r.isSuccessful) {
                    repos.value = repos.value.filterNot { it.fullName == "$owner/$name" }
                    onDone()
                    notice.value = "Deleted `$owner/$name`."
                } else {
                    val failure = r.toGitHubFailure("GitHub refused")
                    lastFailure.value = failure
                    error.value = "Could not delete `$owner/$name`: ${failure.render()}"
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not delete `$owner/$name`."
            }
        }
    }

    fun setVisibility(owner: String, name: String, private: Boolean) {
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { repo.setVisibility(owner, name, private) }
                if (r.isSuccessful) {
                    notice.value = "`$owner/$name` is now ${if (private) "private" else "public"}."
                } else {
                    val failure = r.toGitHubFailure("GitHub refused")
                    lastFailure.value = failure
                    error.value = "Could not change visibility: ${failure.render()}"
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not change visibility."
            } finally {
                // Re-sync either way: the toolbar icon was flipped optimistically.
                loadUserAndRepos()
            }
        }
    }

    fun loadContents(owner: String, name: String, path: String, ref: String? = null) {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                // ref must be passed through: otherwise the file browser silently shows the
                // default branch no matter which branch is selected.
                val r = withContext(Dispatchers.IO) { repo.listContents(owner, name, path, ref) }
                if (r.isSuccessful) {
                    contents.value = r.body().orEmpty()
                } else {
                    val failure = r.toGitHubFailure("GitHub refused")
                    lastFailure.value = failure
                    error.value = "Could not open `/$path`: ${failure.render()}"
                }
            } catch (e: Exception) {
                lastFailure.value = GitHubFailure.ofThrowable(e, "network error")
                error.value = e.message ?: "Could not open `/$path`."
            } finally {
                loading.value = false
            }
        }
    }

    fun loadBranches(owner: String, name: String) {
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { repo.listBranches(owner, name) }
                if (r.isSuccessful) branches.value = r.body().orEmpty()
            } catch (e: Exception) {
                // The branch list is only informational; don't interrupt the user over it.
            }
        }
    }
}
