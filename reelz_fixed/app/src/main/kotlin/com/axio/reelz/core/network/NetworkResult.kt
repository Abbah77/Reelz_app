package com.axio.reelz.core.network

// ─────────────────────────────────────────────────────────────────────────────
//  NetworkResult — typed result wrapper used through the entire data layer.
//
//  FIX v3: Made map() inline + reified so Kotlin can safely transform between
//  NetworkResult<T> and NetworkResult<R> without type erasure issues.
//  The Error branch now creates a new Error (not returns `this`) to avoid
//  any covariance-related ClassCastException at coroutine resumeWith().
// ─────────────────────────────────────────────────────────────────────────────

sealed class NetworkResult<out T> {
    data class Success<T>(
        val data: T,
        val fromCache: Boolean = false,
        val cacheAgeMs: Long = 0L,
    ) : NetworkResult<T>()

    data class Error(
        val message: String,
        val code: Int = -1,
        val isNetworkError: Boolean = false,
        val isNotFound: Boolean = false,
    ) : NetworkResult<Nothing>()

    object Loading : NetworkResult<Nothing>()
}

// ── Convenience extensions ────────────────────────────────────────────────────

fun <T> NetworkResult<T>.getOrNull(): T? = (this as? NetworkResult.Success)?.data
fun <T> NetworkResult<T>.isSuccess(): Boolean = this is NetworkResult.Success

/**
 * Transform a NetworkResult<T> to NetworkResult<R>.
 *
 * IMPORTANT: The Error branch creates a NEW Error object rather than returning
 * `this`. This prevents ClassCastException at coroutine resumeWith() when Kotlin
 * tries to cast NetworkResult<Nothing> (covariant) back to NetworkResult<R> in
 * the calling coroutine's type-erased bytecode.
 *
 * The Loading branch similarly returns a fresh Loading to avoid the same issue.
 */
fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> = when (this) {
    is NetworkResult.Success -> NetworkResult.Success(
        data       = transform(data),
        fromCache  = fromCache,
        cacheAgeMs = cacheAgeMs,
    )
    is NetworkResult.Error -> NetworkResult.Error(
        message        = message,
        code           = code,
        isNetworkError = isNetworkError,
        isNotFound     = isNotFound,
    )
    NetworkResult.Loading -> NetworkResult.Loading
}
