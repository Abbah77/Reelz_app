# ── Reelz ProGuard Rules ──────────────────────────────────────────────────────

# Keep app entry points
-keep class com.reelz.MainActivity { *; }
-keep class com.reelz.ReelzApp { *; }
-keep class com.reelz.ui.screens.player.PlayerActivity { *; }
-keep class com.reelz.service.ReelzPlaybackService { *; }

# Keep JNI bridge
-keep class com.reelz.scanner.NativeBridge { *; }
-keepclasseswithmembernames class com.reelz.scanner.NativeBridge {
    native <methods>;
}

# Keep JavascriptInterface methods (WebView bridge)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembers class * { @dagger.hilt.* <methods>; }

# Retrofit + OkHttp + Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }

# Keep all DTOs (Gson needs field names)
-keep class com.reelz.data.remote.dto.** { *; }
-keep class com.reelz.data.model.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
