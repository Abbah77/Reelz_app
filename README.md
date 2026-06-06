# Reelz Native — Android App

A 100% native Android streaming app with the Flutter UI design, ExoPlayer engine,
offline-first architecture, in-app downloads, and Wi-Fi file transfer.

---

## Project Structure

```
Reelz_Native/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/reelz/
│   │   │   ├── ReelzApp.kt                    ← Application + Coil image cache
│   │   │   ├── data/
│   │   │   │   ├── local/Database.kt          ← Room DB + all DAOs
│   │   │   │   ├── model/Models.kt            ← All domain models + entities
│   │   │   │   ├── remote/api/TmdbApi.kt      ← Retrofit TMDB interface
│   │   │   │   ├── remote/dto/TmdbDtos.kt     ← TMDB response DTOs
│   │   │   │   └── repository/
│   │   │   │       ├── MediaRepository.kt     ← TMDB + offline cache logic
│   │   │   │       └── DownloadRepository.kt  ← Download queue management
│   │   │   ├── di/
│   │   │   │   ├── AppModule.kt               ← Hilt: OkHttp, Retrofit, Room
│   │   │   │   └── UtilModule.kt              ← Hilt: Gson, DownloadRepo
│   │   │   ├── scanner/
│   │   │   │   ├── StreamEngine.kt            ← Parallel source racing (fast!)
│   │   │   │   ├── WebViewScanner.kt          ← Cookie-isolated WebView scanner
│   │   │   │   ├── DirectScanner.kt           ← OkHttp direct source scanner
│   │   │   │   ├── StreamSource.kt            ← Source registry (VidSrc etc.)
│   │   │   │   └── NativeBridge.kt            ← JNI bridge to C++ parser
│   │   │   ├── service/
│   │   │   │   ├── ReelzPlaybackService.kt    ← Media3 background playback
│   │   │   │   └── DownloadService.kt         ← Foreground download service
│   │   │   ├── transfer/
│   │   │   │   └── TransferService.kt         ← Wi-Fi P2P file transfer
│   │   │   └── ui/
│   │   │       ├── MainActivity.kt            ← Entry point
│   │   │       ├── AppNavigation.kt           ← 5-tab nav + route defs
│   │   │       ├── components/CommonComponents.kt ← Reusable UI
│   │   │       ├── theme/
│   │   │       │   ├── Tokens.kt              ← Colors matching Flutter design
│   │   │       │   └── Theme.kt               ← MaterialTheme dark scheme
│   │   │       └── screens/
│   │   │           ├── browse/BrowseScreen.kt ← Home + hero pager + genres
│   │   │           ├── shorts/ShortsScreen.kt ← TikTok-style vertical pager
│   │   │           ├── detail/DetailScreen.kt ← Movie/TV detail + episodes
│   │   │           ├── player/
│   │   │           │   ├── PlayerActivity.kt  ← Fullscreen landscape player
│   │   │           │   └── PlayerViewModel.kt ← ExoPlayer + quality + resume
│   │   │           ├── search/SearchScreen.kt ← Debounced multi-search
│   │   │           ├── downloads/DownloadsScreen.kt ← In-app file manager
│   │   │           ├── transfer/TransferScreen.kt   ← Wi-Fi send/receive + QR
│   │   │           └── profile/ProfileScreen.kt     ← Auth + library + settings
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt                 ← Native build config
│   │   │   ├── reelz_jni.cpp                  ← JNI entry points
│   │   │   ├── m3u8_parser.cpp                ← Fast native HLS parser
│   │   │   └── header_forge.cpp               ← Browser header spoofing
│   │   └── res/
│   │       ├── values/strings.xml
│   │       ├── values/colors.xml
│   │       ├── values/themes.xml
│   │       ├── drawable/ic_reelz_logo.xml
│   │       └── xml/
│   │           ├── network_security_config.xml
│   │           └── data_extraction_rules.xml
│   ├── build.gradle.kts                       ← All dependencies declared here
│   └── proguard-rules.pro
├── gradle/libs.versions.toml                  ← Version catalog
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## Setup Steps

### 1. Open in Android Studio
Open the `Reelz_Native/` folder in Android Studio Hedgehog (2023.1.1) or newer.

### 2. Add your Google Client ID (for Sign In)
In `ProfileScreen.kt`, replace:
```kotlin
.setServerClientId("YOUR_WEB_CLIENT_ID")
```
With your actual Web Client ID from [Google Cloud Console](https://console.cloud.google.com/).

### 3. Add your TMDB API key (optional override)
The key is already embedded in `build.gradle.kts`:
```kotlin
buildConfigField("String", "TMDB_KEY", "\"1eef1496d59aa06f62e201ddce2741b4\"")
```
Replace with your own key from [themoviedb.org](https://www.themoviedb.org/settings/api).

### 4. Add Ads (when ready)
In `build.gradle.kts`, replace the test ad unit IDs:
```kotlin
buildConfigField("String", "AD_BANNER_ID",       "\"your-real-banner-id\"")
buildConfigField("String", "AD_INTERSTITIAL_ID", "\"your-real-interstitial-id\"")
buildConfigField("String", "AD_REWARDED_ID",     "\"your-real-rewarded-id\"")
```
Then in `CommonComponents.kt`, uncomment the real `AdView` implementation inside `AdBannerPlaceholder`.

### 5. Add launcher icons
Place your icon files at:
- `app/src/main/res/mipmap-*/ic_launcher.png`
- `app/src/main/res/mipmap-*/ic_launcher_round.png`

Or use Android Studio's Image Asset tool (right-click `res` → New → Image Asset).

### 6. Build
```bash
./gradlew assembleDebug
```
For release:
```bash
./gradlew assembleRelease
```

---

## Key Fixes vs Old Native App

| Problem | Fix |
|---|---|
| Movie plays once, then needs reinstall | Each WebView scan wipes cookies before AND after — isolated per request |
| Slow stream loading | Parallel source racing — all sources tried simultaneously, first win used |
| Dev error messages (403, init fail…) | `friendlyError()` maps all technical errors to plain language |
| No quality selection | ExoPlayer `TrackSelector` + native HLS parser exposes all quality tracks |
| Coin system | Removed entirely |
| No continue watching | Room `watch_history` saves position every 500ms, auto-resumes |
| Downloads go to phone gallery | All files stored in `filesDir/downloads/` — private to app only |
| No genre discovery | Genre pill filters + TMDB discover API |
| Slow image loading | Coil with 256MB disk cache + 25% RAM memory cache |

---

## Architecture

```
UI (Compose) → ViewModel → Repository → { Room (offline) | Retrofit (online) }
                                      ↓
                              StreamEngine (parallel race)
                                      ↓
                    [WebViewScanner] or [DirectScanner]
                                      ↓
                              ExoPlayer (HLS / MP4)
```

---

## Adding More Stream Sources

Edit `StreamSource.kt` → `SourceRegistry.ALL`. Each source needs:
```kotlin
StreamSource(
    name     = "MySource",
    priority = 5,           // lower = tried first
    buildUrl = { id, type, s, e -> "https://mysource.com/embed/$id" },
    referer  = "https://mysource.com/",
    origin   = "https://mysource.com",
)
```

---

## File Transfer Protocol

Both devices must be on the **same Wi-Fi network**.

**Send:** Sender opens a TCP socket on port 49200.
Receiver scans QR code (or enters IP) → connects → file streams at full LAN speed.

**Receive:** Device listens on port 49200, accepts one connection at a time.
Files are saved to internal private storage (not visible in phone's file manager).
Only Reelz can read and play them.

---

## Offline Mode

- Metadata cached in Room for 48 hours
- Thumbnails cached by Coil on disk (256MB)
- On launch with no network: cached data loads instantly
- Network detection: refreshes once per online session
- Watchlist / Liked / History: always 100% local, never need network
