# ─────────────────────────────────────────────────────────────────────────────
#  Reelz proguard-rules.pro — v4
# ─────────────────────────────────────────────────────────────────────────────

# ── Kotlin / Coroutines ───────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }
-dontwarn kotlin.**
-keep class kotlinx.coroutines.** { *; }
-keepclassmembernames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Retrofit + OkHttp ─────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class retrofit2.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ── NetworkResult sealed class ────────────────────────────────────────────────
# CRITICAL: Must be kept or coroutine resumeWith() casts throw ClassCastException.
# ProGuard strips/renames sealed subclasses by default. The coroutine runtime
# does an unchecked cast on suspend function return — if Success/Error/Loading
# are renamed or merged, the cast fails at runtime even though the code looks fine.
-keep class com.axio.reelz.core.network.NetworkResult { *; }
-keep class com.axio.reelz.core.network.NetworkResult$* { *; }

# ── Backend DTOs — must survive minification so Gson can deserialise them ─────
# DTOs live in com.axio.reelz.data.dto  (NOT data.remote.dto — that package doesn't exist)
# Wrong path = ProGuard silently ignores the rule = Gson gets renamed fields = null/empty objects
-keep class com.axio.reelz.data.dto.** { *; }
-keep class com.axio.reelz.data.remote.api.** { *; }

# ── Domain models ─────────────────────────────────────────────────────────────
-keep class com.axio.reelz.data.model.** { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.**

# ── Hilt ─────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }
-dontwarn dagger.**
-dontwarn hilt_aggregated_deps.**

# ── Gson ──────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# ── Firebase / Crashlytics ────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Media3 / ExoPlayer ────────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Coil ──────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# ── Google Credentials / Auth ─────────────────────────────────────────────────
-keep class com.google.android.gms.** { *; }
-keep class androidx.credentials.** { *; }
-dontwarn com.google.android.gms.**

# ── AppLovin MAX ──────────────────────────────────────────────────────────────
-keep class com.applovin.** { *; }
-dontwarn com.applovin.**

# ── Keep enum names (used in Room as strings) ─────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Suppress common noise warnings ────────────────────────────────────────────
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn java.lang.invoke.**
