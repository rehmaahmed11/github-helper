package com.example.githubdesktop.ui.util

/**
 * Outcome of a mutating operation (upload, delete, merge, clone).
 *
 * These helpers used to return `Boolean` or `Unit` and the callers ignored it,
 * so a failed push still showed "Push complete". Every one now reports back.
 */
sealed interface OpResult {
    data class Success(val message: String) : OpResult
    data class Failure(val message: String) : OpResult
}

/**
 * Progress of a long-running transfer (upload or download), shown as a progress bar.
 * [fraction] is 0f..1f, or null when the total size is unknown (indeterminate bar).
 */
data class TransferProgress(val label: String, val fraction: Float?)
