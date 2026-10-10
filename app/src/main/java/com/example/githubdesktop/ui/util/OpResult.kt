package com.example.githubdesktop.ui.util

/**
 * Outcome of a mutating operation (upload, delete, merge, clone).
 *
 * These helpers used to return `Boolean` or `Unit` and the callers ignored it, so a failed
 * push still showed "Push complete". Every one now reports back, with the reason.
 */
sealed interface OpResult {

    /**
     * @param workingBranch the branch that was pushed and now needs a merge decision, or
     *   null when the change went straight where it belongs (for example the very first
     *   commit of an empty repository, where there is nothing to merge into).
     */
    data class Success(val message: String, val workingBranch: String? = null) : OpResult

    data class Failure(val message: String) : OpResult
}

/**
 * Progress of a long-running transfer (upload or download), shown as a progress bar.
 * [fraction] is 0f..1f, or null when the total size is unknown (indeterminate bar).
 */
data class TransferProgress(val label: String, val fraction: Float?)

/** One change inside a batched push. */
sealed class PushOp {
    /** Repository-relative path, e.g. `docs/readme.md`. No leading slash, no `..`. */
    data class Add(val path: String, val bytes: ByteArray) : PushOp() {
        override fun equals(other: Any?): Boolean =
            other is Add && other.path == path && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
    }

    data class Remove(val path: String) : PushOp()
}

/** The repository-relative path either kind of [PushOp] targets. */
val PushOp.path: String
    get() = when (this) {
        is PushOp.Add -> path
        is PushOp.Remove -> path
    }
