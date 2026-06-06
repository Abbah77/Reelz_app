# Reelz 🎬

A fast, cinematic Android streaming app. Built with Kotlin, Jetpack Compose, ExoPlayer (Media3), C++20, and Hilt.

---

## Architecture

```
com.reelz/
├── MainActivity.kt               # Entry point
├── ReelzApp.kt                   # Hilt Application
├── data/
│   ├── model/Models.kt           # Domain models (Media, Episode, StreamResult …)
│   ├── remote/api/TmdbApi.kt     # TMDB Retrofit API
│   ├── remote/dto/TmdbDtos.kt    # All TMDB response DTOs
│   ├── local/ReelzDatabase.kt    # Room DB + DAOs (watchlist, history)
│   └── repository/MediaRepository.kt
├── scanner/
│   ├── StreamSource.kt           # Source registry — ADD YOUR SOURCES HERE
│   ├── StreamEngine.kt           # Parallel race engine (fires all sources at once)
│   ├── WebViewScanner.kt         # Headless WebView + JS interceptor
│   ├── DirectScanner.kt          # OkHttp scanner for non-JS sources
│   └── NativeBridge.kt           # JNI bridge to C++ layer
├── di/AppModule.kt               # Hilt DI
├── service/ReelzPlaybackService  # Media3 background playback
├── ui/
│   ├── AppNavigation.kt          # Nav graph + bottom bar
│   ├── theme/                    # Colors, typography
│   ├── components/               # Shared composables
│   └── screens/
│       ├── home/                 # HomeScreen + ViewModel
│       ├── detail/               # DetailScreen + ViewModel
│       ├── player/               # PlayerActivity + PlayerScreen + ViewModel
│       ├── search/               # SearchScreen + ViewModel
│       └── watchlist/            # WatchlistScreen + ViewModel
└── cpp/
    ├── reelz_native.cpp          # JNI entry points
    ├── url_builder.cpp/.h        # URL construction
    ├── m3u8_parser.cpp/.h        # HLS master playlist parser (picks best quality)
    └── header_forge.cpp/.h       # Header assembly
```

---

## Stream Engine — How It Works

1. **Source Registry** (`scanner/StreamSource.kt`) — list of provider descriptors.  
   Each has a `buildUrl()` lambda that takes TMDB ID + season/episode → embed URL.

2. **StreamEngine** fires **all sources simultaneously** via Kotlin coroutines.  
   Whichever source responds first with a valid `.m3u8` or `.mp4` URL wins.  
   All other in-flight jobs are cancelled immediately — zero wasted time.

3. **WebViewScanner** loads the embed page in a headless WebView.  
   Injected JavaScript patches `XMLHttpRequest`, `fetch()`, `HTMLVideoElement.src`,  
   `MutationObserver`, and `MediaSource` to intercept every network URL.  
   The first `.m3u8` or `.mp4` found is sent back via `JavascriptInterface`.

4. **ExoPlayer** receives the raw `.m3u8` with all headers (Referer, Origin, UA).  
   HLS segment downloads begin immediately — buffer starts filling in ~1.5 s.  
   If ExoPlayer hits a playback error, `StreamEngine.resolveWithFallback()` kicks in  
   and tries the next source automatically.

5. **C++ layer** handles URL building, M3U8 variant selection (picks highest BANDWIDTH),  
   and header string assembly at native speed.

---

## Setup — Add Your Sources

Open `app/src/main/kotlin/com/reelz/scanner/StreamSource.kt`  
Replace every `SOURCE_N_PLACEHOLDER` with your real provider base URLs:

```kotlin
StreamSource(
    name     = "MySource",
    buildUrl = { tmdbId, type, season, episode ->
        if (type == MediaType.MOVIE)
            "https://YOURSITE.com/embed/movie/$tmdbId"
        else
            "https://YOURSITE.com/embed/tv/$tmdbId/$season/$episode"
    },
    referer  = "https://YOURSITE.com/",
    origin   = "https://YOURSITE.com",
    headers  = mapOf("User-Agent" to StreamHeaders.UA_CHROME_ANDROID),
    requiresJs = true,   // true = WebView scanner, false = direct OkHttp
    priority = 0,        // lower = tried first
)
```

---

## TMDB API Key

The key in `app/build.gradle.kts` → `buildConfigField("String", "TMDB_KEY", ...)`.  
Replace with your own key from [themoviedb.org](https://www.themoviedb.org/settings/api).

---

## Build & Deploy

### GitHub → Codemagic

1. Push this repo to GitHub.
2. Connect repo in [codemagic.io](https://codemagic.io).
3. Add environment variable group `reelz_env`:
   - `DEVELOPER_EMAIL` — your email
   - `GCLOUD_SERVICE_ACCOUNT_CREDENTIALS` — for Play Store publish (optional)
4. Add your signing keystore as `reelz_keystore` in Codemagic.
5. Trigger build → gets `.aab` + `.apk` as artifacts.

### Local build (optional)
```bash
./gradlew assembleRelease
```

---

## Tech Stack

| Layer | Library |
|-------|---------|
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose |
| Player | Media3 ExoPlayer + HLS |
| DI | Hilt |
| Network | Retrofit + OkHttp |
| Images | Coil |
| DB | Room |
| Async | Kotlin Coroutines + Flow |
| Native | C++20 via NDK CMake |
| Scanner | Android WebView + JS injection |
