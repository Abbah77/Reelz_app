package com.axio.reelz.network

// ─────────────────────────────────────────────────────────────────────────────
//  NetworkResult — typed result wrapper used through the entire data layer.
//
//  Rules:
//   • Success(data, fromCache) — valid result, fromCache = true means Room hit.
//   • Error — network/server failure with optional user-friendly message.
//   • Loading — only emitted when no cache exists (cold state).
//
//  ViewModels map this to UI state; repositories never throw through this boundary.
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

// Convenience extensions
fun <T> NetworkResult<T>.getOrNull(): T? = (this as? NetworkResult.Success)?.data
fun <T> NetworkResult<T>.isSuccess(): Boolean = this is NetworkResult.Success
fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> = when (this) {
    is NetworkResult.Success -> NetworkResult.Success(transform(data), fromCache, cacheAgeMs)
    is NetworkResult.Error   -> this
    NetworkResult.Loading    -> NetworkResult.Loading
}
