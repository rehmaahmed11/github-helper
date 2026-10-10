package com.example.githubdesktop.data.api

import org.json.JSONObject
import retrofit2.Response

/**
 * GitHub reports most failures as
 * `{"message": "...", "errors": [...], "documentation_url": "..."}`.
 * Show that message instead of a bare status code.
 *
 * Note: this consumes `errorBody()`, so it must be called at most once per response.
 */
fun Response<*>.githubError(fallback: String = "request failed"): String {
    val detail = runCatching { errorBody()?.string() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { body -> runCatching { JSONObject(body).optString("message") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    val status = when (code()) {
        401 -> "Not signed in (401)"
        403 -> "Not permitted or rate limited (403)"
        404 -> "Not found (404)"
        409 -> "Conflict (409)"
        422 -> "Rejected by GitHub (422)"
        else -> "HTTP ${code()}"
    }
    return if (detail != null) "$status - $detail" else "$status - $fallback"
}