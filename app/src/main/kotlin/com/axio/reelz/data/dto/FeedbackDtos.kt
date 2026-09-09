package com.axio.reelz.data.dto

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
// FeedbackDtos — Schema v5
//
// POST /api/v1/feedback        — Submit user feedback
// GET  /api/v1/feedback/schema — Get valid screen/category combos (cached 24h)
//
// Source screen to required context mapping:
//   player   → request_id (ENGINE) + user_id (from auth)
//   detail   → tmdb_id (catalog, no request_id)
//   download → request_id (ENGINE)
//   shorts   → request_id (ENGINE)
//   settings → user_id only (no request_id, no tmdb_id)
// ─────────────────────────────────────────────────────────────────────────────

// ── Request body sent to POST /api/v1/feedback ────────────────────────────────
data class FeedbackBody(
    @SerializedName("source_screen") val sourceScreen: String,
    val category:                       String,
    val description:                    String?    = null,
    @SerializedName("request_id")    val requestId: String?    = null,
    @SerializedName("tmdb_id")       val tmdbId:    String?    = null,
)

// ── Response data field inside ApiResponse<FeedbackResult> ───────────────────
data class FeedbackResult(
    @SerializedName("feedback_id") val feedbackId: Int    = 0,
    val message:                      String = "",
)

// ── Schema endpoint — GET /api/v1/feedback/schema ────────────────────────────
data class FeedbackSchemaData(
    val screens: Map<String, List<String>> = emptyMap(),
)

// ── Domain model — used by FeedbackViewModel ──────────────────────────────────
enum class FeedbackSource(val key: String) {
    PLAYER("player"),
    DETAIL("detail"),
    DOWNLOAD("download"),
    SHORTS("shorts"),
    SETTINGS("settings");

    companion object {
        fun fromKey(key: String): FeedbackSource =
            values().firstOrNull { it.key == key } ?: SETTINGS
    }
}

// Hard-coded shortcut labels for display (backend is the source of truth for keys).
// Display label → backend category key
val FEEDBACK_SHORTCUTS: Map<FeedbackSource, List<Pair<String, String>>> = mapOf(
    FeedbackSource.PLAYER to listOf(
        "Wrong movie"       to "wrong_movie",
        "Bad quality"       to "bad_quality",
        "Playback error"    to "playback_error",
        "Wrong subtitle"    to "wrong_subtitle",
        "No subtitle"       to "no_subtitle",
        "Buffering"         to "buffering",
        "Other"             to "other",
    ),
    FeedbackSource.DETAIL to listOf(
        "Missing title"     to "missing_title",
        "Wrong info"        to "wrong_info",
        "Can't stream"      to "cant_stream",
        "Can't download"    to "cant_download",
        "Wrong poster"      to "wrong_poster",
        "Other"             to "other",
    ),
    FeedbackSource.DOWNLOAD to listOf(
        "Download failed"   to "download_failed",
        "Download slow"     to "download_slow",
        "Wrong movie"       to "wrong_movie",
        "Wrong quality"     to "wrong_quality",
        "Corrupt file"      to "corrupt_file",
        "Other"             to "other",
    ),
    FeedbackSource.SHORTS to listOf(
        "Pornography"       to "pornography",
        "Violence"          to "violence",
        "Misinformation"    to "misinformation",
        "Repeated content"  to "repeated_content",
        "Copyright issue"   to "copyright",
        "Other"             to "other",
    ),
    FeedbackSource.SETTINGS to listOf(
        "App bug"           to "app_bug",
        "Feature request"   to "feature_request",
        "Account issue"     to "account_issue",
        "Payment issue"     to "payment_issue",
        "Content request"   to "content_request",
        "Other"             to "other",
    ),
)
