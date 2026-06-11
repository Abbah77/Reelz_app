# ── Kotlin ────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepclassmembers class kotlinx.** { *; }

# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclassmembers class * { @javax.inject.Inject <fields>; }
-keepclassmembers class * { @dagger.hilt.android.lifecycle.HiltViewModel <init>(...); }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * { @androidx.room.* <methods>; }

# ── Retrofit / OkHttp ─────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ── Gson / data models ────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keep class com.reelz.data.model.** { *; }
-keep class com.reelz.data.remote.dto.** { *; }
# Remote config models parsed by Gson - must not be obfuscated
-keep class com.reelz.remoteconfig.** { *; }
-keepclassmembers class com.reelz.remoteconfig.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Media3 / ExoPlayer ────────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Coil ──────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── Google Auth / Credentials ─────────────────────────────────────────────────
-keep class com.google.android.libraries.identity.** { *; }
-keep class androidx.credentials.** { *; }

# ── Native JNI ────────────────────────────────────────────────────────────────
-keep class com.reelz.scanner.NativeBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ── WebView JS interfaces ─────────────────────────────────────────────────────
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Suppress common warnings ──────────────────────────────────────────────────
-dontwarn com.google.errorprone.**
-dontwarn sun.misc.**
-dontwarn javax.annotation.**
