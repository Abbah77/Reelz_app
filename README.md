# StreamApp — Native Android Streaming App

High-end native Android app built with **Kotlin + Jetpack Compose**, a **C++20 JNI scanner layer**, and **Media3 ExoPlayer**. TMDB powers all metadata. The phone is the engine — no server needed.

---

## Architecture

```
StreamApp/
├── app/src/main/
│   ├── cpp/                        ← C++20 JNI scanner (parallel URL racing)
│   │   ├── scanner.cpp             ← JNI entry points + parallel source racing
│   │   ├── m3u8_parser.cpp/.h      ← HLS playlist parser, best-quality selector
│   │   ├── url_extractor.cpp/.h    ← Regex m3u8 URL extractor from HTML/JS
│   │   └── CMakeLists.txt
│   └── kotlin/com/streamapp/
│       ├── scanner/
│       │   ├── NativeScanner.kt    ← Kotlin JNI wrapper
│       │   └── WebViewScanner.kt   ← Hidden WebView JS extractor
│       ├── data/
│       │   ├── model/Models.kt     ← Movie, TvShow, Episode, Season, StreamResult
│       │   ├── remote/api/         ← Retrofit TMDB API interface
│       │   ├── remote/dto/         ← TMDB response DTOs
│       │   └── repository/
│       │       ├── TmdbRepository.kt    ← All TMDB calls (parallel w/ coroutines)
│       │       └── StreamRepository.kt  ← Parallel stream URL racing
│       ├── di/AppModule.kt         ← Hilt dependency injection
│       ├── ui/
│       │   ├── Navigation.kt       ← Full nav graph
│       │   ├── theme/              ← Dark theme, colors, typography
│       │   ├── components/         ← MediaCard, HeroCarousel
│       │   └── screens/
│       │       ├── home/           ← HomeScreen + ViewModel
│       │       ├── detail/         ← DetailScreen + ViewModel (movies + TV episodes)
│       │       ├── player/         ← PlayerScreen (ExoPlayer, seek, speed, gestures)
│       │       └── search/         ← SearchScreen + ViewModel (debounced)
│       └── util/Extensions.kt
```

---

## What's Fully Built

| Feature | Status |
|---|---|
| Kotlin + Jetpack Compose UI | ✅ Complete |
| Dark cinematic theme (purple + pink accent) | ✅ Complete |
| Hero carousel (auto-scroll, page dots) | ✅ Complete |
| Home screen (Trending, Popular Movies, Popular TV, Top Rated) | ✅ Complete |
| Search screen (debounced, 3-column grid) | ✅ Complete |
| Detail screen (movie + TV, genres, overview, recommendations) | ✅ Complete |
| Episode list with season selector | ✅ Complete |
| ExoPlayer (HLS/m3u8, seek bar, play/pause, speed, double-tap seek) | ✅ Complete |
| C++ JNI parallel URL scanner (all sources race simultaneously) | ✅ Complete |
| C++ m3u8 playlist parser (selects best quality) | ✅ Complete |
| C++ HTML/JS URL extractor (regex) | ✅ Complete |
| WebView JS extractor (5 strategies: DOM, XHR, fetch, MutationObserver, script scan) | ✅ Complete |
| TMDB metadata (trending, popular, top rated, detail, seasons, episodes) | ✅ Complete |
| Hilt dependency injection | ✅ Complete |
| Animated navigation transitions | ✅ Complete |

---

## How to Wire Your Stream Sources

### Step 1 — C++ parallel scanner (fastest path)

Open `app/src/main/cpp/scanner.cpp` and replace the placeholder list:

```cpp
static const std::vector<std::string> PLACEHOLDER_SOURCES = {
    "https://YOUR-REAL-SOURCE-1.com",   // ← replace
    "https://YOUR-REAL-SOURCE-2.com",   // ← replace
    "https://YOUR-REAL-SOURCE-3.com",   // ← replace
    // add as many as you want — they all fire in parallel
};
```

Also adjust `buildSourceUrl()` in the same file to match each site's URL pattern:

```cpp
// Example: site uses /embed/movie/TMDB_ID
return base + "/embed/movie/" + tmdbId;

// Example: TV episodes use /embed/tv/TMDB_ID/season/S/episode/E
return base + "/embed/tv/" + tmdbId + "/" + std::to_string(season) + "/" + std::to_string(episode);
```

### Step 2 — WebView JS extractor (for JS-heavy sites)

Open `app/src/main/kotlin/com/streamapp/scanner/WebViewScanner.kt`.

The JS already covers 5 extraction strategies. You just need to call it with your embed URL:

```kotlin
val scanner = WebViewScanner(context)
val m3u8 = scanner.scan(
    targetUrl = "https://YOUR-SITE.com/embed/${tmdbId}",
    timeoutMs = 12_000
)
```

To integrate it into the racing pipeline, add a call inside `StreamRepository.resolveStream()`:

```kotlin
// After the C++ race, fall back to WebView
if (result is StreamResult.NotFound) {
    val webUrl = webViewScanner.scan("https://YOUR-SITE.com/embed/$tmdbId")
    if (webUrl != null) return StreamResult.Found(webUrl, "webview")
}
```

### Step 3 — Per-source URL patterns (if sites need different paths)

You can add a `when` block in `StreamRepository` to build different URLs per source:

```kotlin
val embedUrl = when {
    source.contains("site1") -> "$source/movie?id=$tmdbId"
    source.contains("site2") -> "$source/embed/$tmdbId"
    else -> "$source/$tmdbId"
}
```

---

## Build Setup

### Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Ladybug (2024.2) or newer |
| Android NDK | r27 or newer |
| CMake | 3.22.1+ |
| JDK | 17 |
| Gradle | 8.9 (via wrapper) |
| Min SDK | 26 (Android 8) |
| Target SDK | 35 (Android 15) |

### Steps

1. **Clone / open project in Android Studio**
2. **Install NDK:** `SDK Manager → SDK Tools → NDK (Side by side)` → install r27
3. **Install CMake:** same panel → CMake 3.22.1
4. **Sync Gradle:** File → Sync Project with Gradle Files
5. **Add your sources** (see "How to Wire" above)
6. **Run on device** (NDK requires a real device or x86_64 emulator)

```bash
# Build release APK
./gradlew assembleRelease

# Build debug APK
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/`

---

## TMDB Key

Already embedded in `BuildConfig`. Key: `1eef1496d59aa06f62e201ddce2741b4`

To rotate it: `app/build.gradle.kts` → `buildConfigField("String", "TMDB_KEY", "\"YOUR_NEW_KEY\"")`

---

## Dependencies (all auto-downloaded by Gradle)

| Library | Purpose |
|---|---|
| Jetpack Compose BOM 2024.10 | UI framework |
| Material3 | Design system |
| Hilt 2.52 | Dependency injection |
| Media3 ExoPlayer 1.4.1 | HLS/m3u8 playback |
| Retrofit 2.11 + OkHttp 4.12 | TMDB API calls |
| Coil 2.7 | Image loading |
| Room 2.6 | Local DB (wired for watchlist) |
| Navigation Compose 2.8 | Screen routing |
| Kotlinx Coroutines | Async/parallel |
| Android NDK C++20 | C++ scanner layer |

---

## Performance Architecture

- **C++ parallel race** — all source URLs built in native C++ and fired simultaneously via `std::async`. Kotlin races them with `CompletableDeferred` — first valid m3u8 wins, all others cancelled.
- **50ms stagger** — prevents thundering herd on sources
- **OkHttp HEAD check** — validates m3u8 endpoint before returning URL (fast, no body download)
- **Coroutine dispatchers** — I/O-bound work on `Dispatchers.IO`, UI on `Dispatchers.Main`
- **TMDB parallel fetch** — home screen loads trending + popular movies + popular TV + top rated concurrently via `async/await`
- **Debounced search** — 400ms debounce, cancels prior search job
- **Coil image cache** — disk + memory cache for instant poster loads

---

## App Name / Branding

Change the app name: `app/src/main/res/values/strings.xml` → `app_name`

Add your custom font: drop `.ttf` into `app/src/main/res/font/`, update `Type.kt`

Replace launcher icon: swap `ic_splash.xml` with your vector, or add PNG mipmaps.

---

## Notes

- `usesCleartextTraffic="true"` and `network_security_config.xml` are set so HTTP stream URLs work alongside HTTPS TMDB calls.
- The WebView `JavascriptInterface` is annotated — ProGuard will not strip it.
- ExoPlayer releases automatically in `DisposableEffect` — no memory leaks.
- All stream source slots are **placeholders** — fill them with your licensed/permitted sources.
