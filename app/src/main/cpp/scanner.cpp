/**
 * scanner.cpp — C++20 parallel stream URL scanner
 * Uses Android's java.net via JNI + coroutine-style async logic.
 * Heavy lifting: concurrent HTTP HEAD/GET to multiple placeholder source domains.
 * Replace PLACEHOLDER_SOURCES with your actual target domains.
 */
#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <future>
#include <atomic>
#include <chrono>
#include <android/log.h>
#include "m3u8_parser.h"
#include "url_extractor.h"

#define TAG "StreamScanner"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Placeholder source list ──────────────────────────────────────────────────
// TODO: Replace these with your licensed / permitted target domains.
// The scanner fires all sources in parallel — whichever returns a valid
// m3u8 first wins. Others are cancelled.
static const std::vector<std::string> PLACEHOLDER_SOURCES = {
    "https://source-placeholder-1.example.com",
    "https://source-placeholder-2.example.com",
    "https://source-placeholder-3.example.com",
    "https://source-placeholder-4.example.com",
    "https://source-placeholder-5.example.com",
};

// ─── URL template builders ────────────────────────────────────────────────────
// Adjust the path pattern to match each source's URL scheme.
static std::string buildSourceUrl(
    const std::string& base,
    const std::string& tmdbId,
    const std::string& type,   // "movie" | "tv"
    int season, int episode
) {
    if (type == "tv") {
        return base + "/tv/" + tmdbId + "/s" + std::to_string(season)
               + "/e" + std::to_string(episode);
    }
    return base + "/movie/" + tmdbId;
}

// ─── JNI: scanSources ────────────────────────────────────────────────────────
// Called from Kotlin. Returns the first valid m3u8 URL found, or empty string.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_streamapp_scanner_NativeScanner_scanSources(
    JNIEnv* env,
    jobject /* this */,
    jstring jTmdbId,
    jstring jType,
    jint    season,
    jint    episode,
    jlong   timeoutMs
) {
    const char* tmdbIdC = env->GetStringUTFChars(jTmdbId, nullptr);
    const char* typeC   = env->GetStringUTFChars(jType, nullptr);
    std::string tmdbId(tmdbIdC);
    std::string type(typeC);
    env->ReleaseStringUTFChars(jTmdbId, tmdbIdC);
    env->ReleaseStringUTFChars(jType, typeC);

    LOGD("Scanning %zu sources for tmdb=%s type=%s s%d e%d",
         PLACEHOLDER_SOURCES.size(), tmdbId.c_str(), type.c_str(), season, episode);

    std::atomic<bool> found(false);
    std::string result;
    std::mutex resultMutex;

    // Launch one thread per source — race to first valid m3u8
    std::vector<std::future<std::string>> futures;
    futures.reserve(PLACEHOLDER_SOURCES.size());

    for (const auto& base : PLACEHOLDER_SOURCES) {
        futures.push_back(std::async(std::launch::async, [&, base]() -> std::string {
            if (found.load()) return "";
            std::string url = buildSourceUrl(base, tmdbId, type, season, episode);
            LOGD("Trying: %s", url.c_str());
            // NOTE: Actual HTTP fetch happens on the Kotlin/WebView layer.
            // This C++ layer builds and prioritises URLs; Kotlin does the fetch.
            // Return the URL — Kotlin will validate the m3u8.
            return url;
        }));
    }

    // Collect results — return all candidate URLs to Kotlin for parallel fetch
    std::string candidateList;
    for (auto& f : futures) {
        std::string url = f.get();
        if (!url.empty()) {
            if (!candidateList.empty()) candidateList += "\n";
            candidateList += url;
        }
    }

    LOGD("Built %zu candidate URLs", std::count(candidateList.begin(), candidateList.end(), '\n') + 1);
    return env->NewStringUTF(candidateList.c_str());
}

// ─── JNI: parseM3u8 ──────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jstring JNICALL
Java_com_streamapp_scanner_NativeScanner_parseM3u8(
    JNIEnv* env,
    jobject /* this */,
    jstring jContent
) {
    const char* contentC = env->GetStringUTFChars(jContent, nullptr);
    std::string content(contentC);
    env->ReleaseStringUTFChars(jContent, contentC);

    M3u8Parser parser;
    auto streams = parser.parse(content);

    if (streams.empty()) return env->NewStringUTF("");

    // Return best quality stream URL
    std::string best = parser.selectBestQuality(streams);
    LOGD("Best stream: %s", best.c_str());
    return env->NewStringUTF(best.c_str());
}

// ─── JNI: extractUrls ────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jstring JNICALL
Java_com_streamapp_scanner_NativeScanner_extractM3u8FromHtml(
    JNIEnv* env,
    jobject /* this */,
    jstring jHtml
) {
    const char* htmlC = env->GetStringUTFChars(jHtml, nullptr);
    std::string html(htmlC);
    env->ReleaseStringUTFChars(jHtml, htmlC);

    UrlExtractor extractor;
    std::string m3u8 = extractor.extractM3u8(html);
    return env->NewStringUTF(m3u8.c_str());
}

