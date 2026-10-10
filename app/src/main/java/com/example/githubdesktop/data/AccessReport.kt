package com.example.githubdesktop.data

import com.example.githubdesktop.data.api.GitHubClient
import com.example.githubdesktop.data.api.GitHubFailure
import com.example.githubdesktop.data.api.grantedScopes
import com.example.githubdesktop.data.api.rateLimitRemaining
import com.example.githubdesktop.data.api.toGitHubFailure
import kotlin.math.max

/** How the current credential was obtained. GitHub treats these very differently. */
enum class TokenKind(val label: String, val detail: String) {
    ClassicPat(
        "Personal access token (classic)",
        "Full REST access, limited by the scopes you ticked. `repo` covers reading and writing " +
            "private repositories and creating them."
    ),
    FineGrainedPat(
        "Fine-grained personal access token",
        "Permission-by-permission and repository-by-repository. Needs Contents: Read and write, " +
            "and Metadata: Read-only. Cannot reach some account-level endpoints, so creating or " +
            "deleting repositories may be refused."
    ),
    GitHubAppUser(
        "GitHub App user token",
        "Comes from a GitHub App, so its powers are the app's permission list, not OAuth scopes. " +
            "GitHub refuses repository creation for app tokens."
    ),
    Installation(
        "GitHub App installation token",
        "Acts as the app itself, not as you. Repository creation is unavailable."
    ),
    OAuthApp(
        "OAuth App token",
        "Granted by an OAuth App's authorization flow; whatever the app asked for in `scope`."
    ),
    Unknown("Token", "Unrecognised credential format.")
}

/**
 * What the stored credential can actually do, measured against GitHub rather than assumed.
 *
 * This exists because "403" alone is not a diagnosis: a missing `repo` scope, a fine-grained
 * token without Contents: Write, a GitHub App token (which cannot create repositories at all)
 * and a rate limit all answer 403, and each has a different fix.
 */
data class AccessReport(
    val checkedAt: Long,
    val login: String? = null,
    val kind: TokenKind = TokenKind.Unknown,
    val grantedScopes: List<String> = emptyList(),
    val canReadRepos: Boolean = false,
    /** null = could not be determined. */
    val canCreateRepos: Boolean? = null,
    val canDeleteRepos: Boolean = false,
    /** GitHub's own words when a capability is missing, so the UI can show the real reason. */
    val blockedReason: String? = null,
    val coreRemaining: Int? = null,
    val coreLimit: Int? = null,
    val coreResetEpochSeconds: Long? = null,
    /** Set when GitHub refused even the read probe (expired or revoked token). */
    val failure: GitHubFailure? = null
) {

    val signedIn: Boolean get() = failure == null

    /** True when the credential can create repositories - the app's most-limited operation. */
    val createBlocked: Boolean get() = canCreateRepos == false

    /** One paragraph for the UI, or null when everything checks out. */
    val advice: String?
        get() = when {
            failure != null -> failure.fix
            createBlocked -> when (kind) {
                TokenKind.FineGrainedPat ->
                    "This fine-grained token cannot create repositories. Give it Administration: " +
                        "Read and write, or sign in with a classic token that has the `repo` scope."
                TokenKind.GitHubAppUser, TokenKind.Installation ->
                    "GitHub does not let app tokens create repositories. Sign in with a classic " +
                        "personal access token that has the `repo` scope."
                else -> blockedReason?.takeIf { it.isNotBlank() }
                    ?: "Sign in with a token that has the `repo` scope to create repositories."
            }
            !canWriteAnything ->
                "This token cannot write files either: it has none of the `repo` / `public_repo` " +
                    "scopes, or the matching Contents permission. Re-create it with `repo`, then " +
                    "paste the new value in Sign in."
            coreRemaining != null && coreRemaining <= 0 ->
                "GitHub's API budget for this device is used up; it refills in " +
                    "${minutesUntilReset(System.currentTimeMillis() / 1000)} min."
            else -> null
        }

    /**
     * Read-only credentials are the classic "browsing works, uploading 403s" case: GitHub lets
     * them read a public repository with no scope at all, and every write then fails with 403.
     */
    private val canWriteAnything: Boolean
        get() = when {
            grantedScopes.isNotEmpty() ->
                grantedScopes.contains("repo") || grantedScopes.contains("public_repo")
            // No scope list means either an app / fine-grained token - whose powers are a
            // permission table rather than scopes, so they are measured per call - or a classic
            // token created without ticking anything, which can only read public data.
            else -> kind != TokenKind.ClassicPat
        }

    private fun minutesUntilReset(nowEpochSeconds: Long): Int =
        max(1, ((coreResetEpochSeconds ?: nowEpochSeconds) - nowEpochSeconds) / 60).toInt()

    companion object {

        /**
         * Prefills GitHub's classic-token form with exactly what the app needs: `repo` for
         * reading and writing private repos and their contents, `delete_repo` for the delete
         * button. The user only has to press "Generate token".
         */
        const val CLASSIC_TOKEN_URL =
            "https://github.com/settings/tokens/new?scopes=repo,delete_repo" +
                "&description=GitHub%20Helper%20(Android)"

        /** The same, for a fine-grained token: enough to browse, edit and upload. */
        const val FINE_GRAINED_TOKEN_URL =
            "https://github.com/settings/personal-access-tokens/new" +
                "?name=GitHub+Helper+(Android)" +
                "&description=Browse,+edit+and+upload+files+from+the+phone" +
                "&contents=write&metadata=read&pull_requests=write&expires_in=90"

        /**
         * Runs the probes. Never throws: an unreachable GitHub or a revoked token comes back as
         * a report with [failure] set, because the login screen needs to explain it.
         */
        suspend fun probe(repo: RepoRepository): AccessReport {
            val me = runCatching { repo.me() }
            if (me.isFailure) {
                return AccessReport(
                    checkedAt = System.currentTimeMillis(),
                    failure = GitHubFailure.ofThrowable(
                        me.exceptionOrNull() ?: IllegalStateException("unreachable"),
                        "Could not reach GitHub"
                    )
                )
            }
            val meResponse = me.getOrThrow()
            if (!meResponse.isSuccessful) {
                return AccessReport(
                    checkedAt = System.currentTimeMillis(),
                    kind = TokenKind.Unknown,
                    failure = meResponse.toGitHubFailure("GitHub rejected this token")
                )
            }

            val scopes = meResponse.grantedScopes()
            val kind = findKind(GitHubClient.token())
            val remaining = meResponse.rateLimitRemaining()

            // Repository creation is the operation most likely to be refused, so it is measured
            // rather than inferred. The payload is deliberately invalid (see the API doc), which
            // means nothing can be created by probing it.
            val create = runCatching { repo.probeCanCreateRepos() }.getOrNull()
            val canCreate: Boolean? = when (create?.code()) {
                null -> null
                401, 403 -> false
                else -> true
            }

            val rate = runCatching { repo.rateLimit() }.getOrNull()?.body()
            val core = rate?.resources?.core ?: rate?.rate

            return AccessReport(
                checkedAt = System.currentTimeMillis(),
                login = meResponse.body()?.login,
                kind = kind,
                grantedScopes = scopes,
                canReadRepos = true,
                canCreateRepos = canCreate,
                canDeleteRepos = scopes.contains("delete_repo"),
                // Only explain a refusal we actually measured; a 422 from the probe means
                // "allowed, bad payload" and is not a problem to report.
                blockedReason = if (canCreate == false) {
                    create?.let { resp ->
                        GitHubFailure.from(resp, "GitHub refused").let { f -> "${f.render()}. ${f.fix}" }
                    }
                } else {
                    null
                },
                coreRemaining = core?.remaining ?: remaining,
                coreLimit = core?.limit,
                coreResetEpochSeconds = core?.reset
            )
        }

        /** Token prefixes are stable enough to tell the credential types apart. */
        fun findKind(token: String?): TokenKind {
            val t = token?.trim().orEmpty()
            return when {
                t.startsWith("github_pat_") -> TokenKind.FineGrainedPat
                t.startsWith("ghp_") -> TokenKind.ClassicPat
                t.startsWith("gho_") -> TokenKind.OAuthApp
                t.startsWith("ghu_") -> TokenKind.GitHubAppUser
                t.startsWith("ghs_") -> TokenKind.Installation
                t.isBlank() -> TokenKind.Unknown
                else -> TokenKind.OAuthApp
            }
        }
    }
}
