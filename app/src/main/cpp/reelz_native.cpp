#include <jni.h>
#include <string>
#include <android/log.h>
#include "url_builder.h"
#include "m3u8_parser.h"
#include "header_forge.h"

#define LOG_TAG "ReelzNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// ── URL builder: constructs source embed URL from TMDB ID ─────────────────────
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_buildEmbedUrl(
    JNIEnv* env,
    jobject /* this */,
    jstring baseUrl,
    jint    tmdbId,
    jint    mediaType,   // 0=movie, 1=tv
    jint    season,
    jint    episode)
{
    const char* base = env->GetStringUTFChars(baseUrl, nullptr);
    std::string result = reelz::buildEmbedUrl(base, tmdbId, mediaType, season, episode);
    env->ReleaseStringUTFChars(baseUrl, base);
    return env->NewStringUTF(result.c_str());
}

// ── M3U8 parser: extracts highest-quality variant playlist URL ────────────────
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_extractBestVariant(
    JNIEnv* env,
    jobject /* this */,
    jstring m3u8Content)
{
    const char* content = env->GetStringUTFChars(m3u8Content, nullptr);
    std::string best = reelz::extractBestVariant(content);
    env->ReleaseStringUTFChars(m3u8Content, content);
    return env->NewStringUTF(best.c_str());
}

// ── Header forge: builds complete request headers for a source ─────────────────
JNIEXPORT jstring JNICALL
Java_com_reelz_scanner_NativeBridge_forgeHeaders(
    JNIEnv* env,
    jobject /* this */,
    jstring referer,
    jstring origin,
    jstring userAgent)
{
    const char* ref = env->GetStringUTFChars(referer,   nullptr);
    const char* ori = env->GetStringUTFChars(origin,    nullptr);
    const char* ua  = env->GetStringUTFChars(userAgent, nullptr);
    std::string headers = reelz::forgeHeaders(ref, ori, ua);
    env->ReleaseStringUTFChars(referer,   ref);
    env->ReleaseStringUTFChars(origin,    ori);
    env->ReleaseStringUTFChars(userAgent, ua);
    return env->NewStringUTF(headers.c_str());
}

// ── Segment estimator: estimates buffer needed for N episodes at ~4.5 min avg ──
JNIEXPORT jlong JNICALL
Java_com_reelz_scanner_NativeBridge_estimateBufferBytes(
    JNIEnv* /* env */,
    jobject /* this */,
    jint  episodeCount,
    jint  avgBitrateKbps)
{
    // 4.5 min average episode × 60 s × bitrate in bits ÷ 8 bytes
    constexpr double AVG_EPISODE_SECS = 4.5 * 60.0;
    long long bytes = static_cast<long long>(
        episodeCount * AVG_EPISODE_SECS * avgBitrateKbps * 1000.0 / 8.0
    );
    LOGI("Buffer estimate: %lld bytes for %d episodes @ %d kbps", bytes, episodeCount, avgBitrateKbps);
    return static_cast<jlong>(bytes);
}

} // extern "C"
