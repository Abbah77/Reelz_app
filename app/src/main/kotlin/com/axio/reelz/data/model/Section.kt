package com.axio.reelz.data.model

/**
 * Canonical section registry.
 *
 * Each section has:
 *   id       — stable DB key; never rename or the cache breaks
 *   ttlHours — how long cached data for this section is considered fresh
 *   emoji    — displayed in UI section headers
 *   label    — displayed section title
 *
 * Default home-screen order (for new users with no tap history):
 *   TRENDING → POPULAR_MOVIES → NEW_RELEASES → KDRAMA → NOLLYWOOD → INDIAN
 *   → HOT_TV → TOP_RATED → ACTION → TURKISH → ANIME → COMEDY → ROMANCE
 *   → CDRAMA → HORROR → CRIME → DOCUMENTARY → AFRICAN → FAMILY → SCIFI
 */
enum class Section(
    val id: String,
    val ttlHours: Int,
    val emoji: String,
    val label: String,
) {
    TRENDING        ("trending",    24, "🔥", "Trending Now"),
    NEW_RELEASES    ("new_release", 24, "🆕", "New Releases"),
    POPULAR_MOVIES  ("pop_movies",  48, "🎬", "Popular Movies"),
    HOT_TV          ("hot_tv",      48, "📺", "Hot TV"),
    TOP_RATED       ("top_rated",   72, "⭐", "Top Rated"),
    KDRAMA          ("kdrama",      48, "🇰🇷", "K-Drama"),
    ANIME           ("anime",       48, "🇯🇵", "Anime"),
    INDIAN          ("indian",      48, "🇮🇳", "Indian"),
    NOLLYWOOD       ("nollywood",   48, "🇳🇬", "Nollywood"),
    CDRAMA          ("cdrama",      48, "🇨🇳", "C-Drama"),
    ACTION          ("action",      72, "💥", "Action"),
    COMEDY          ("comedy",      72, "😂", "Comedy"),
    ROMANCE         ("romance",     72, "❤️", "Romance"),
    HORROR          ("horror",      72, "👻", "Horror"),
    CRIME           ("crime",       72, "🔪", "Crime"),
    DRAMA           ("drama",       72, "📖", "Drama"),
    TURKISH         ("turkish",     48, "🇹🇷", "Turkish"),
    DOCUMENTARY     ("documentary", 72, "🎭", "Documentary"),
    FAMILY          ("family",      72, "👨‍👩‍👧", "Family"),
    SCIFI           ("scifi",       72, "🚀", "Sci-Fi"),
    AFRICAN         ("african",     48, "🌍", "African Originals"),
    ;

    companion object {
        /** Ordered list for new users — sorted by broad appeal. */
        val DEFAULT_ORDER = listOf(
            TRENDING, POPULAR_MOVIES, NEW_RELEASES, KDRAMA, NOLLYWOOD, INDIAN,
            HOT_TV, TOP_RATED, ACTION, TURKISH, ANIME, COMEDY, ROMANCE, CDRAMA,
            HORROR, CRIME, DRAMA, DOCUMENTARY, AFRICAN, FAMILY, SCIFI,
        )

        fun fromId(id: String): Section? = values().firstOrNull { it.id == id }
    }
}

/** Returns true when the cached-at timestamp for this section is older than its TTL. */
fun Section.isSectionStale(cachedAtMs: Long): Boolean {
    val ageMs = System.currentTimeMillis() - cachedAtMs
    return ageMs > ttlHours * 3_600_000L
}
