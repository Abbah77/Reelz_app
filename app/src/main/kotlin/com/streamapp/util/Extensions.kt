package com.streamapp.util

import com.streamapp.data.model.MediaItem
import com.streamapp.data.remote.dto.MovieDto
import com.streamapp.data.remote.dto.TvDto

fun Int.minutesToHuman(): String {
    if (this <= 0) return ""
    val h = this / 60
    val m = this % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun String.truncate(max: Int = 120): String =
    if (length <= max) this else take(max).trimEnd() + "…"
