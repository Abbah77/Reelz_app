-keep class com.streamapp.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class androidx.media3.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
