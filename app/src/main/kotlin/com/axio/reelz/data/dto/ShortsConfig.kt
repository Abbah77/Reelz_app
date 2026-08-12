package com.axio.reelz.data.dto

/**
 * ShortCategory — one tab/category in the Shorts Discovery feed.
 * Backed by archive.org collection identifiers supplied by the backend config.
 */
data class ShortCategory(
    val label: String,
    val items: List<String> = emptyList(),   // archive.org identifiers for this category
)

/**
 * ArchiveOrgConfig — tuning knobs for archive.org fetches.
 */
data class ArchiveOrgConfig(
    val thumbnailBaseUrl: String = "https://archive.org/services/img",
    val requestTimeoutMs: Long = 15_000L,
    val maxParallelResolves: Int = 4,
    val itemsPerPage: Int = 8,
)

/**
 * ShortsConfig — everything the ShortsViewModel needs.
 * In a future backend-driven iteration this can be deserialized from AppConfigDto.
 */
data class ShortsConfig(
    val categories: List<ShortCategory> = listOf(
        ShortCategory("🔥 Trending", emptyList()),
        ShortCategory("🎭 Comedy",   emptyList()),
        ShortCategory("🎬 Clips",    emptyList()),
    ),
    val archiveOrg: ArchiveOrgConfig = ArchiveOrgConfig(),
    val videoExtensions: List<String> = listOf(".mp4", ".webm", ".ogv", ".mov"),
    val excludedNameContains: List<String> = listOf("sample", "trailer", "ads"),
    val poolSize: Int = 40,
    val itemsPerPage: Int = 8,
)
