#include <jni.h>
#include <string>
#include <android/log.h>
#include "m3u8_parser.cpp"
#include "header_forge.cpp"

#define TAG "ReelzNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

// ── M3U8 parsing ─────────────────────────────────────────────────────────────

/**
 * Parse an m3u8 playlist string and return the best-quality variant URL.
 * Returns empty string if not a master playlist or no variants found.
 */
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_parseBestVariantUrl(
        JNIEnv* env, jobject /* this */,
        jstring jContent, jstring jBaseUrl) {

    const char* content = env->GetStringUTFChars(jContent, nullptr);
    const char* baseUrl = env->GetStringUTFChars(jBaseUrl, nullptr);

    std::string result;
    try {
        HlsPlaylist pl = parseM3u8(content, baseUrl);
        if (pl.isMaster) {
            const HlsVariant* best = pl.bestVariant();
            if (best) result = best->url;
        }
    } catch (...) {
        LOGE("parseBestVariantUrl: exception");
    }

    env->ReleaseStringUTFChars(jContent, content);
    env->ReleaseStringUTFChars(jBaseUrl, baseUrl);
    return env->NewStringUTF(result.c_str());
}

/**
 * Parse an m3u8 playlist and return ALL segment URLs as newline-separated string.
 * Used during download to enumerate segments for parallel fetching.
 */
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_parseSegmentUrls(
        JNIEnv* env, jobject /* this */,
        jstring jContent, jstring jBaseUrl) {

    const char* content = env->GetStringUTFChars(jContent, nullptr);
    const char* baseUrl = env->GetStringUTFChars(jBaseUrl, nullptr);

    std::string result;
    try {
        HlsPlaylist pl = parseM3u8(content, baseUrl);
        for (const auto& seg : pl.segments) {
            result += seg.url + "\n";
        }
    } catch (...) {
        LOGE("parseSegmentUrls: exception");
    }

    env->ReleaseStringUTFChars(jContent, content);
    env->ReleaseStringUTFChars(jBaseUrl, baseUrl);
    return env->NewStringUTF(result.c_str());
}

/**
 * Return all variant URLs sorted by bandwidth (highest first),
 * pipe-separated as "url|bandwidth|resolution\n..."
 */
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_parseVariants(
        JNIEnv* env, jobject /* this */,
        jstring jContent, jstring jBaseUrl) {

    const char* content = env->GetStringUTFChars(jContent, nullptr);
    const char* baseUrl = env->GetStringUTFChars(jBaseUrl, nullptr);

    std::string result;
    try {
        HlsPlaylist pl = parseM3u8(content, baseUrl);
        // Sort variants by bandwidth descending
        std::sort(pl.variants.begin(), pl.variants.end(),
            [](const HlsVariant& a, const HlsVariant& b){ return a.bandwidth > b.bandwidth; });
        for (const auto& v : pl.variants) {
            result += v.url + "|"
                   + std::to_string(v.bandwidth) + "|"
                   + v.resolution + "|"
                   + std::to_string(v.height) + "\n";
        }
    } catch (...) {
        LOGE("parseVariants: exception");
    }

    env->ReleaseStringUTFChars(jContent, content);
    env->ReleaseStringUTFChars(jBaseUrl, baseUrl);
    return env->NewStringUTF(result.c_str());
}

/**
 * Return subtitle tracks as "url|language|name\n..."
 */
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_parseSubtitles(
        JNIEnv* env, jobject /* this */,
        jstring jContent, jstring jBaseUrl) {

    const char* content = env->GetStringUTFChars(jContent, nullptr);
    const char* baseUrl = env->GetStringUTFChars(jBaseUrl, nullptr);

    std::string result;
    try {
        HlsPlaylist pl = parseM3u8(content, baseUrl);
        for (const auto& s : pl.subtitles) {
            result += s.url + "|" + s.language + "|" + s.name + "\n";
        }
    } catch (...) {
        LOGE("parseSubtitles: exception");
    }

    env->ReleaseStringUTFChars(jContent, content);
    env->ReleaseStringUTFChars(jBaseUrl, baseUrl);
    return env->NewStringUTF(result.c_str());
}

// ── Header forging ────────────────────────────────────────────────────────────

/**
 * Build browser-spoofed HTTP headers.
 * Returns "Key: Value\r\n" lines concatenated.
 */
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_forgeHeaders(
        JNIEnv* env, jobject /* this */,
        jstring jReferer, jstring jOrigin,
        jboolean mobile, jboolean isXhr) {

    const char* referer = env->GetStringUTFChars(jReferer, nullptr);
    const char* origin  = env->GetStringUTFChars(jOrigin,  nullptr);

    std::string result;
    try {
        Headers h = forgeHeaders(referer, origin, mobile == JNI_TRUE, isXhr == JNI_TRUE);
        result = serialiseHeaders(h);
    } catch (...) {
        LOGE("forgeHeaders: exception");
    }

    env->ReleaseStringUTFChars(jReferer, referer);
    env->ReleaseStringUTFChars(jOrigin,  origin);
    return env->NewStringUTF(result.c_str());
}

/**
 * Return a single spoofed User-Agent string.
 */
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_getUserAgent(
        JNIEnv* env, jobject /* this */, jboolean mobile) {
    std::string ua;
    try { ua = buildUserAgent(mobile == JNI_TRUE); }
    catch (...) { ua = "Mozilla/5.0"; }
    return env->NewStringUTF(ua.c_str());
}

} // extern "C"
