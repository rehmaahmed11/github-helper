package com.example.githubdesktop.data.api

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response

/**
 * Why GitHub refused a request, in terms the app can act on.
 *
 * GitHub answers almost every failure with a 4xx *response* (not an exception), and the
 * bare status code is useless on its own: the same 403 means "your token has no `repo`
 * scope", "you are rate limited", or "this is an app token, which cannot do that" - three
 * completely different fixes. So every failure is classified from three signals:
 *
 *  1. the JSON body (`message`, `errors[]`, `documentation_url`),
 *  2. `X-OAuth-Scopes` (what the token actually has) vs `X-Accepted-OAuth-Scopes`
 *     (what this endpoint requires) - GitHub tells us both, on every response,
 *  3. `X-RateLimit-Remaining` / `Retry-After` (back off instead of failing).
 */
enum class FailureKind {
    /** Token is expired/revoked/wrong account (401). */
    TokenInvalid,

    /** Token is valid but lacks a scope the endpoint requires (403). */
    ScopeMissing,

    /** Signed in with a GitHub App / installation token that GitHub does not allow here. */
    IntegrationBlocked,

    /** Primary or secondary rate limit (403 + Retry-After, or 429). */
    RateLimited,

    /** The org requires SAML SSO and the token was not authorized for it. */
    SsoRequired,

    /** 404 - also what GitHub returns for a private repo the token cannot see. */
    NotFound,

    /** 422 - GitHub understood, but rejected the payload. */
    Validation,

    /** 409 - someone else moved the ref, or it already exists. */
    Conflict,

    /** 5xx - GitHub's side. */
    Server,

    /** Request never got a usable answer. */
    Network,

    Unknown
}

/**
 * A parsed GitHub failure. [render] is what the user sees; [fix] is the single next step.
 */
data class GitHubFailure(
    val kind: FailureKind,
    val status: Int,
    val message: String,
    val detail: String? = null,
    val grantedScopes: List<String> = emptyList(),
    val requiredScopes: List<String> = emptyList(),
    val retryAfterSeconds: Long? = null,
    val requestId: String? = null,
    val documentationUrl: String? = null
) {
    /** True when waiting and repeating the same request can succeed (used by the retry layer). */
    val isTransient: Boolean
        get() = kind == FailureKind.RateLimited || kind == FailureKind.Server ||
            kind == FailureKind.Network

    /** One or two sentences of instructions, shown under the error wherever there is room. */
    val fix: String
        get() = when (kind) {
            FailureKind.TokenInvalid ->
                "Sign in again with a classic personal access token that has the `repo` scope."
            FailureKind.ScopeMissing -> {
                val need = if (requiredScopes.isNotEmpty()) requiredScopes.joinToString("/") else "repo"
                "Your token does not have the `$need` scope. Create a classic token with `$need` " +
                    "checked at github.com -> Settings -> Developer settings -> Personal access " +
                    "tokens -> Tokens (classic), then paste it in the app."
            }
            FailureKind.IntegrationBlocked ->
                "GitHub refuses this for app / integration tokens: creating repositories (and " +
                    "often writing files) needs a user token. Sign in with a classic personal " +
                    "access token that has the `repo` scope instead of a GitHub App token."
            FailureKind.RateLimited ->
                if (retryAfterSeconds != null && retryAfterSeconds > 0)
                    "GitHub is throttling this device; the app waits ${retryAfterSeconds}s and " +
                        "retries. Upload a smaller folder if it keeps happening."
                else
                    "GitHub is throttling this device. Wait a minute and try again; the app " +
                        "batches uploads into one commit so this is rare."
            FailureKind.SsoRequired ->
                "This organization requires SAML SSO. Authorize the token for that org at " +
                    "github.com -> Settings -> Applications, grant it, then retry."
            FailureKind.NotFound ->
                "Check the repository, branch and path. A private repository your token cannot " +
                    "see is reported as \"not found\" by GitHub, so a missing `repo` scope looks " +
                    "the same."
            FailureKind.Validation ->
                if (documentationUrl != null) "See $documentationUrl" else "Check the values you entered."
            FailureKind.Conflict ->
                "The branch changed while this was in flight. Refresh and try again."
            FailureKind.Server ->
                "GitHub returned a server error, so nothing was written. Try again shortly."
            FailureKind.Network ->
                "Check the device's connection and try again."
            FailureKind.Unknown ->
                if (documentationUrl != null) "Details: $documentationUrl" else "Try again."
        }

    /** Short text for a snackbar: status + GitHub's own message. */
    fun render(): String {
        val head = if (status > 0) "HTTP $status - $message" else message
        return if (detail.isNullOrBlank()) head else "$head ($detail)"
    }

    /** Long text for a dialog or the access sheet: what happened, what GitHub said, what to do. */
    fun renderLong(): String = buildString {
        append(render())
        append("\n\n")
        append(fix)
        if (requiredScopes.isNotEmpty() || grantedScopes.isNotEmpty()) {
            append("\n\n")
            append("Token scopes: ")
            append(if (grantedScopes.isEmpty()) "none" else grantedScopes.joinToString(", "))
            append(if (requiredScopes.isEmpty()) "" else " · required: ${requiredScopes.joinToString(", ")}")
        }
        requestId?.let { append("\nRequest ID: $it") }
    }

    companion object {

        /**
         * Builds a failure from an HTTP response. Consumes `errorBody()`, so call it once.
         */
        fun from(response: Response<*>, fallback: String): GitHubFailure {
            val status = response.code()
            val headers = response.headers()
            val granted = scopesOf(headers, "X-OAuth-Scopes")
            val accepted = scopesOf(headers, "X-Accepted-OAuth-Scopes")
            val retryAfter = headers.get("Retry-After")?.trim()?.toLongOrNull()
            val remaining = headers.get("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
            val requestId = headers.get("X-GitHub-Request-Id")

            val raw = runCatching { response.errorBody()?.string() }.getOrNull()
            val json = raw?.takeIf { it.isNotBlank() && it.trimStart().startsWith("{") }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }

            val bodyMessage = json?.optString("message")?.takeIf { it.isNotBlank() }
            val errors = json?.optJSONArray("errors").details()
            val docs = json?.optString("documentation_url")?.takeIf { it.isNotBlank() }

            val lowered = (bodyMessage ?: "").lowercase()
            val kind = classify(status, lowered, remaining, granted, accepted)
            val message = bodyMessage ?: when (kind) {
                FailureKind.NotFound -> "not found"
                FailureKind.RateLimited -> "too many requests"
                else -> fallback
            }

            return GitHubFailure(
                kind = kind,
                status = status,
                message = message,
                detail = errors ?: retryHint(retryAfter, remaining),
                grantedScopes = granted,
                requiredScopes = accepted.filter { it !in granted },
                retryAfterSeconds = retryAfter,
                requestId = requestId,
                documentationUrl = docs
            )
        }

        /** A failure for an exception (no HTTP response at all). */
        fun ofThrowable(t: Throwable, fallback: String): GitHubFailure = GitHubFailure(
            kind = FailureKind.Network,
            status = 0,
            message = t.message ?: fallback,
            detail = t::class.java.simpleName
        )

        /** Scopes are comma separated; GitHub sends the header empty when the token has none. */
        fun scopesOf(headers: Headers, name: String): List<String> =
            headers.get(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        private fun retryHint(retryAfter: Long?, remaining: Int?): String? = when {
            retryAfter != null && retryAfter > 0 -> "retry in ${retryAfter}s"
            remaining != null && remaining <= 0 -> "rate limit exhausted"
            else -> null
        }

        private fun classify(
            status: Int,
            message: String,
            remaining: Int?,
            granted: List<String>,
            accepted: List<String>
        ): FailureKind = when {
            status == 401 -> FailureKind.TokenInvalid
            status == 404 -> FailureKind.NotFound
            status == 409 -> FailureKind.Conflict
            status == 422 -> FailureKind.Validation
            status == 429 -> FailureKind.RateLimited
            status >= 500 -> FailureKind.Server
            status != 403 -> FailureKind.Unknown
            // 403 from here on: rate limit vs permission.
            message.contains("secondary rate limit") || message.contains("abuse detection") ||
                message.contains("too many requests") || remaining == 0 -> FailureKind.RateLimited
            message.contains("saml") || message.contains("identity provider") -> FailureKind.SsoRequired
            message.contains("not accessible by integration") ||
                message.contains("not allowed as an integration") -> FailureKind.IntegrationBlocked
            message.contains("resource not accessible by personal access token") ||
                message.contains("must have admin") ||
                message.contains("not have the appropriate permissions") ||
                message.contains("insufficient scope") ||
                message.contains("requires the repo scope") -> FailureKind.ScopeMissing
            accepted.isNotEmpty() && accepted.any { it !in granted } -> FailureKind.ScopeMissing
            else -> FailureKind.Unknown
        }

        /** Turns GitHub's `errors[]` array into one readable line. */
        private fun JSONArray?.details(): String? {
            if (this == null || length() == 0) return null
            val parts = mutableListOf<String>()
            for (i in 0 until length()) {
                val e = optJSONObject(i) ?: continue
                val field = e.optString("field").takeIf { it.isNotBlank() }
                val msg = e.optString("message").takeIf { it.isNotBlank() } ?: continue
                val resource = e.optString("resource").takeIf { it.isNotBlank() }
                val code = e.optString("code").takeIf { it.isNotBlank() }
                val label = listOfNotNull(resource, field).joinToString(".").ifBlank { "error" }
                parts += "$label: $msg" + (code?.let { " [$it]" } ?: "")
            }
            return parts.joinToString("; ").takeIf { it.isNotBlank() }
        }
    }
}

/**
 * Everything the UI needs to explain a failure: GitHub's own status text plus a fix.
 *
 * Kept as a `String` producer so the many existing call sites
 * (`"Could not save: ${resp.githubError()}"`) get better messages for free.
 */
fun Response<*>.githubError(fallback: String = "request failed"): String =
    toGitHubFailure(fallback).render()

fun Response<*>.toGitHubFailure(fallback: String = "request failed"): GitHubFailure =
    GitHubFailure.from(this, fallback)

/** Success responses still carry the scope/rate-limit headers, so capture those too. */
fun Response<*>.grantedScopes(): List<String> =
    GitHubFailure.scopesOf(headers(), "X-OAuth-Scopes")

fun Response<*>.rateLimitRemaining(): Int? =
    headers().get("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
