# Reelz Ads System — Implementation Guide

## Architecture Overview

```
AdEngine (singleton, Hilt)
  ├── App Open Ad      → fires once per cold start (MainActivity.onResume)
  ├── Interstitial     → pre-play gate in DetailScreen.launchPlayer()
  ├── Rewarded         → preloaded, exposed via AdEngine.showRewarded()
  ├── Native (Browse)  → NativeAdCard injected every 3 feed rows
  ├── Native (Shorts)  → ShortsNativeAdPage injected every 5 reels
  └── VAST Pre-roll    → IMA SDK, plays before movie content in PlayerActivity

Ad Clicks → routeAdUrl()
  ├── Play Store URL   → opens Play Store app directly
  ├── intent://        → opens target app, falls back to browser sheet
  └── http(s)          → ReelzBrowserSheet (in-app WebView bottom sheet)
```

## Files Added

| File | Purpose |
|------|---------|
| `ads/AdEngine.kt` | Singleton: init, preload, frequency caps, show logic for all ad types |
| `ads/AdModule.kt` | Hilt DI module providing `AdEngine` |
| `ads/DetailBannerAd.kt` | AppLovin MAX 320×50 banner, lifecycle-safe, for DetailScreen bottom |
| `ads/ImaPreRollView.kt` | IMA SDK VAST pre-roll composable wrapping VideoView + VideoAdPlayer |
| `ads/NativeAdCard.kt` | Full-width native ad card with shimmer skeleton for BrowseScreen |
| `ads/ReelzBrowserSheet.kt` | In-app WebView bottom sheet + `routeAdUrl()` URL router |
| `ads/ShortsNativeAdPage.kt` | Full-screen native ad page for Shorts VerticalPager |
| `ads/VastTagProvider.kt` | Pre-roll eligibility gate + VAST URL accessor |
| `di/AdModule.kt` | Hilt `@Module` binding for `AdEngine` |

## Files Modified

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Added AppLovin MAX, IMA SDK, Media3 IMA extension deps + new `BuildConfig` fields |
| `AndroidManifest.xml` | Added AppLovin SDK key meta-data + required fullscreen ad activities |
| `ReelzApp.kt` | Inject `AdEngine`, call `adEngine.initialize(this)` in `onCreate()` |
| `ui/MainActivity.kt` | Inject `AdEngine`, show App Open ad on cold start in `onResume()` |
| `ui/AppNavigation.kt` | Thread `AdEngine` param to `BrowseScreen` and `ShortsScreen` |
| `ui/screens/browse/BrowseScreen.kt` | Added `NativeAdPlacement` to `FeedRow`, inject every 3 rows, render `NativeAdCard` |
| `ui/screens/shorts/ShortsScreen.kt` | Added `ShortsItem` sealed class, inject `AdSlot` every 5 videos, update both VerticalPagers |
| `ui/screens/detail/DetailScreen.kt` | Add `adEngine` param; interstitial gate in `launchPlayer()`; `DetailBannerAd` at bottom; `AdEngine.incrementContentOpen()` in VM |
| `ui/screens/player/PlayerViewModel.kt` | Add `AdEngine`, `preRollVastUrl`/`isPreRollPlaying` in `PlayerUiState`, pre-roll gate in `resolveAndPlay()`, `preRollCompleted()` callback |
| `ui/screens/player/PlayerActivity.kt` | IMA imports, `ImaPreRollView` overlay in Compose tree |

---

## Setup Checklist

### 1. AppLovin MAX Dashboard

1. Create a **MAX account** at https://dash.applovin.com
2. Add your app → get your **SDK Key**
3. Create ad units for each format:
   - Banner → copy Unit ID → `AD_BANNER_ID`
   - Interstitial → `AD_INTERSTITIAL_ID`
   - Rewarded → `AD_REWARDED_ID`
   - Native → `AD_NATIVE_ID`
   - App Open → `AD_APP_OPEN_ID`

### 2. Replace Placeholder IDs

In `app/build.gradle.kts` — `defaultConfig` block:
```kotlin
buildConfigField("String", "AD_BANNER_ID",       "\"YOUR_MAX_BANNER_AD_UNIT_ID\"")
buildConfigField("String", "AD_INTERSTITIAL_ID", "\"YOUR_MAX_INTERSTITIAL_AD_UNIT_ID\"")
buildConfigField("String", "AD_REWARDED_ID",     "\"YOUR_MAX_REWARDED_AD_UNIT_ID\"")
buildConfigField("String", "AD_NATIVE_ID",       "\"YOUR_MAX_NATIVE_AD_UNIT_ID\"")
buildConfigField("String", "AD_APP_OPEN_ID",     "\"YOUR_MAX_APP_OPEN_AD_UNIT_ID\"")
buildConfigField("String", "AD_VAST_TAG_URL",    "\"YOUR_VAST_TAG_URL\"")
```

In `AndroidManifest.xml`:
```xml
<meta-data
    android:name="applovin.sdk.key"
    android:value="YOUR_APPLOVIN_SDK_KEY" />
```

### 3. VAST Tag URL

For Pangle (TikTok for Business):
```
https://pangle.io/api/vast?app_id=YOUR_APP_ID&placement_id=YOUR_PLACEMENT_ID
```
For AppLovin MAX VAST:
```
https://ms.applovin.com/vast?sdk_key=YOUR_SDK_KEY&ad_unit_id=YOUR_UNIT_ID
```

### 4. Native Ad Loader (MaxNativeAdLoader)

`AdEngine.loadNativeAd()` contains a stub — wire in `MaxNativeAdLoader`:

```kotlin
fun loadNativeAd(onLoaded: (NativeAdState.Loaded) -> Unit, onFailed: () -> Unit) {
    val loader = MaxNativeAdLoader(BuildConfig.AD_NATIVE_ID, appContext)
    loader.setNativeAdListener(object : MaxNativeAdLoadListener {
        override fun onNativeAdLoaded(loader: MaxNativeAdLoader, ad: MaxNativeAd) {
            onLoaded(NativeAdState.Loaded(
                headline       = ad.title ?: "",
                body           = ad.body ?: "",
                callToAction   = ad.callToAction ?: "Learn More",
                advertiserName = ad.advertiser ?: "",
                clickUrl       = ad.clickUrl ?: "",
                imageUrl       = ad.mainImage?.url ?: "",
                iconUrl        = ad.icon?.url ?: "",
            ))
        }
        override fun onNativeAdLoadFailed(id: String, err: MaxError) { onFailed() }
    })
    loader.loadAd()
}
```

### 5. Mediation Networks (optional but recommended)

In AppLovin MAX dashboard → Mediation → enable:
- **Meta Audience Network** — highest CPMs for native/interstitial
- **ironSource** — strong rewarded fill
- **Pangle** — fills well in Asia/LatAm
- **Google AdMob** — broad fill, required for VAST pre-roll via IMA

Add their adapter dependencies to `build.gradle.kts`:
```kotlin
// Meta
implementation("com.applovin.mediation:facebook-adapter:6.17.0.0")
// ironSource
implementation("com.applovin.mediation:ironsource-adapter:8.2.0.0")
// Pangle
implementation("com.applovin.mediation:bytedance-adapter:6.1.0.9.0")
// AdMob
implementation("com.applovin.mediation:google-adapter:23.1.0.0")
```

---

## Frequency Cap Rules (AdEngine.kt)

| Cap | Value | Constant |
|-----|-------|----------|
| Min time between interstitials | 3 minutes | `MIN_MS_BETWEEN_INTERSTITIALS` |
| Max interstitials per session | 6 | `MAX_INTERSTITIALS_PER_SESSION` |
| Content opens before first ad | 2 | `CONTENT_OPENS_BEFORE_FIRST_AD` |
| Interstitial every N play taps | Every 2nd | `INTERSTITIAL_EVERY_N_PLAYS` |
| Pre-roll min gap | 30 minutes | `VastTagProvider` |
| App Open | Once per cold start | `appOpenShownThisSession` flag |
| Native ads (Browse) | Every 3 feed rows | `BrowseScreen.buildList` |
| Native ads (Shorts) | Every 5 reels | `buildShortsItemList()` |

Adjust these constants in `AdEngine.kt` / `VastTagProvider.kt` to tune
revenue vs. user experience after A/B testing.

---

## Ad Click Flow

```
User taps ad CTA
       │
       ▼
routeAdUrl(context, url, openBrowserSheet)
       │
       ├─ play.google.com / market:// → opens Play Store app
       ├─ intent://                   → opens target app (or sheet fallback)
       └─ http(s)://                  → ReelzBrowserSheet
                                             │
                                             ├─ Back / Forward navigation
                                             ├─ Reload
                                             ├─ Open in external browser
                                             └─ Close → returns to app
```

The in-app browser sheet keeps the user inside Reelz, reducing churn
while satisfying advertiser attribution requirements.

---

## Testing

1. **AppLovin test ads** — use `MaxDebugger` to verify each ad unit:
   ```kotlin
   AppLovinSdk.getInstance(context).showMediationDebugger(activity)
   ```
2. **Force interstitial** — set `CONTENT_OPENS_BEFORE_FIRST_AD = 0` and `INTERSTITIAL_EVERY_N_PLAYS = 1` temporarily.
3. **Force pre-roll** — comment out the `isFirstPlayThisSession` check in `VastTagProvider.shouldShowPreRoll()`.
4. **Native ads** — if `loadNativeAd()` stub is not yet wired, ads silently collapse. Wire the `MaxNativeAdLoader` first.
5. **Browser sheet** — test Play Store URL, intent://, and https:// routing paths separately.
