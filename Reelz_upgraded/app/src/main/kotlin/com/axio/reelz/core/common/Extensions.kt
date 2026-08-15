package com.axio.reelz.core.common

import com.axio.reelz.core.network.NetworkResult

// ─────────────────────────────────────────────────────────────────────────────
//  Extensions.kt — Kotlin extension functions shared across the app.
//
//  Rule: only pure Kotlin extensions go here. No Android framework imports.
//  No @Composable. No context. No Room. No Retrofit.
// ─────────────────────────────────────────────────────────────────────────────

// NetworkResult convenience aliases (re-exported here so callers can import
// from core.common rather than core.network)
fun <T> NetworkResult<T>.getOrNull(): T? = (this as? NetworkResult.Success)?.data
fun <T> NetworkResult<T>.isSuccess(): Boolean = this is NetworkResult.Success

/** Format milliseconds as mm:ss or hh:mm:ss */
fun Long.formatMs(): String {
    val totalSeconds = this / 1000
    val hours   = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0)
        "%d:%02d:%02d".format(hours, minutes, seconds)
    else
        "%d:%02d".format(minutes, seconds)
}

/** Clamp a value between min and max */
fun Long.clamp(min: Long, max: Long): Long = maxOf(min, minOf(max, this))

/** Return the string if not blank, else the fallback */
fun String?.orFallback(fallback: String): String =
    if (isNullOrBlank()) fallback else this
