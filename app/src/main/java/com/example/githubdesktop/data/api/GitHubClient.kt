package com.example.githubdesktop.data.api

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The current core rate-limit window, as reported in GitHub's response headers. */
data class RateWindow(
    val limit: Int,
    val remaining: Int,
    val resetEpochSeconds: Long,
    val resource: String
) {
    /** Seconds until GitHub hands the budget back; never negative. */
    fun secondsUntilReset(nowEpochSeconds: Long): Long =
        (resetEpochSeconds - nowEpochSeconds).coerceAtLeast(0L)
}

private const val API_VERSION = "2022-11-28"

/** Throttle retries: 3 extra attempts is plenty when GitHub asks for seconds, not minutes. */
private const val MAX_ATTEMPTS = 3
private const val MAX_WAIT_MS = 25_000L
private const val PEEK_BYTES = 4096

/** The credential every request carries. Kept here so signing out really does stop traffic. */
private object Session {
    @Volatile
    var token: String? = null
}

/**
 * Facts observed from GitHub's own headers on the most recent response, used by the access
 * screen. These are answers from GitHub, never guesses by the app.
 */
private object Observations {
    @Volatile
    var rateLimit: RateWindow? = null

    @Volatile
    var grantedScopes: List<String> = emptyList()
}

/**
 * The single OkHttp client behind every GitHub REST call.
 *
 * Beyond "make the request" it has three jobs, each of which exists because of a specific
 * failure a user would otherwise see as "403, nothing happened":
 *
 *  1. attach the credential plus `Accept` / `X-GitHub-Api-Version`,
 *  2. *back off and retry* when GitHub is throttling (429, 5xx, or a 403 that says "secondary
 *     rate limit", honouring `Retry-After`) - a folder of many files used to trip that partway
 *     through and then fail every remaining write,
 *  3. remember the scope and rate-limit headers so the UI can name the missing scope instead of
 *     shrugging at a status code.
 *
 * Retrying a throttled response is safe: GitHub refused the request before acting on it, so
 * nothing was written. Failures where the request may never have arrived are only repeated for
 * GETs.
 */
object GitHubClient {

    /** Replaced with null on sign-out so no further call can carry a stale token. */
    fun setToken(t: String?) {
        Session.token = t?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun token(): String? = Session.token

    /** Newest rate-limit window GitHub reported, or null before the first response. */
    val rateLimit: RateWindow? get() = Observations.rateLimit

    /** Scopes GitHub said this token has; empty for app / fine-grained tokens (they have none). */
    val grantedScopes: List<String> get() = Observations.grantedScopes

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RetryInterceptor())
        .addInterceptor(HeaderInterceptor())
        .addInterceptor(HeaderRecorder())
        .addNetworkInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api: GitHubApi = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GitHubApi::class.java)
}

/** Adds the credential and the media-type headers GitHub expects on every call. */
private class HeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        Session.token?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("Accept", "application/vnd.github+json")
        builder.header("X-GitHub-Api-Version", API_VERSION)
        return chain.proceed(builder.build())
    }
}

/** Records scope / rate-limit headers from whichever response finally came back. */
private class HeaderRecorder : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        remember(response.headers())
        return response
    }

    private fun remember(headers: Headers) {
        val granted = GitHubFailure.scopesOf(headers, "X-OAuth-Scopes")
        if (granted.isNotEmpty()) Observations.grantedScopes = granted

        val limit = headers.get("X-RateLimit-Limit")?.trim()?.toIntOrNull()
        val remaining = headers.get("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
        val reset = headers.get("X-RateLimit-Reset")?.trim()?.toLongOrNull()
        if (limit != null && remaining != null && reset != null) {
            Observations.rateLimit = RateWindow(
                limit = limit,
                remaining = remaining,
                resetEpochSeconds = reset,
                resource = headers.get("X-RateLimit-Resource")?.trim() ?: "core"
            )
        }
    }
}

/**
 * Repeats a request that GitHub refused *because of load*, waiting as long as GitHub asked.
 * Each attempt re-sends the identical request, after the whole chain below it.
 */
private class RetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS || !request.method.equals("GET", ignoreCase = true)) throw e
                attempt++
                Thread.sleep(backoffMillis(attempt))
                continue
            }

            val wait = throttleWaitMillis(response, attempt)
            if (wait == null || attempt >= MAX_ATTEMPTS) return response
            response.close()
            attempt++
            Thread.sleep(wait)
        }
    }

    /** How long to wait before repeating, or null when this response is not about load. */
    private fun throttleWaitMillis(response: Response, attempt: Int): Long? {
        val code = response.code
        if (code != 403 && code != 429 && code < 500) return null

        val asked = response.header("Retry-After")?.trim()?.toLongOrNull()
        val remaining = response.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
        val body = if (code == 403 && asked == null && remaining != 0) {
            runCatching { response.peekBody(PEEK_BYTES.toLong()).string() }.getOrNull()
                ?.lowercase().orEmpty()
        } else {
            ""
        }

        val throttled = code == 429 || code >= 500 || (asked != null && asked > 0) || remaining == 0 ||
            body.contains("secondary rate limit") || body.contains("rate limit exceeded") ||
            body.contains("abuse detection") || body.contains("too many requests") ||
            body.contains("retry later") || body.contains("service unavailable")
        if (!throttled) return null

        val fromHeader = asked?.times(1000L)
        return (fromHeader ?: backoffMillis(attempt + 1))
            .coerceIn(250L, MAX_WAIT_MS)
    }
}

/** 500 ms, 1 s, 2 s, ... capped at [MAX_WAIT_MS], plus jitter so parallel calls do not sync up. */
private fun backoffMillis(attempt: Int): Long {
    val base = 250L * (1L shl (attempt - 1).coerceIn(0, 6))
    val capped = base.coerceAtMost(MAX_WAIT_MS)
    return capped + Random.nextLong(capped / 3 + 1)
}
