// reelz_jni.cpp — upgraded with AES-128 TS validation stubs
// Uses the actual m3u8_parser.h API (parseM3u8 → HlsPlaylist) and
// header_forge.h API (forgeHeaders → Headers, serialiseHeaders, buildUserAgent).

#include <jni.h>
#include <string>
#include <sstream>
#include <cstring>
#include <cstdint>
#include "m3u8_parser.h"
#include "header_forge.h"

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Serialise all variant tracks as "url|bandwidth|resolution|height\n" lines. */
static std::string variantsToString(const HlsPlaylist& pl) {
    std::ostringstream oss;
    for (const auto& v : pl.variants) {
        oss << v.url << "|"
            << v.bandwidth << "|"
            << v.resolution << "|"
            << v.height << "\n";
    }
    return oss.str();
}

/** Serialise all segment URLs as one URL per line. */
static std::string segmentsToString(const HlsPlaylist& pl) {
    std::ostringstream oss;
    for (const auto& s : pl.segments) {
        oss << s.url << "\n";
    }
    return oss.str();
}

/** Serialise subtitle tracks as "url|language|name\n" lines. */
static std::string subtitlesToString(const HlsPlaylist& pl) {
    std::ostringstream oss;
    for (const auto& s : pl.subtitles) {
        oss << s.url << "|" << s.language << "|" << s.name << "\n";
    }
    return oss.str();
}

// ─── Existing JNI functions ────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_axio_reelz_scanner_NativeBridge_parseBestVariantUrl(
    JNIEnv* env, jobject, jstring content, jstring baseUrl)
{
    const char* c = env->GetStringUTFChars(content, nullptr);
    const char* b = env->GetStringUTFChars(baseUrl, nullptr);
    HlsPlaylist pl = parseM3u8(c, b);
    env->ReleaseStringUTFChars(content, c);
    env->ReleaseStringUTFChars(baseUrl, b);
    const HlsVariant* best = pl.bestVariant();
    std::string result = best ? best->url : "";
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_axio_reelz_scanner_NativeBridge_parseSegmentUrls(
    JNIEnv* env, jobject, jstring content, jstring baseUrl)
{
    const char* c = env->GetStringUTFChars(content, nullptr);
    const char* b = env->GetStringUTFChars(baseUrl, nullptr);
    HlsPlaylist pl = parseM3u8(c, b);
    env->ReleaseStringUTFChars(content, c);
    env->ReleaseStringUTFChars(baseUrl, b);
    std::string result = segmentsToString(pl);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_axio_reelz_scanner_NativeBridge_parseVariants(
    JNIEnv* env, jobject, jstring content, jstring baseUrl)
{
    const char* c = env->GetStringUTFChars(content, nullptr);
    const char* b = env->GetStringUTFChars(baseUrl, nullptr);
    HlsPlaylist pl = parseM3u8(c, b);
    env->ReleaseStringUTFChars(content, c);
    env->ReleaseStringUTFChars(baseUrl, b);
    std::string result = variantsToString(pl);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_axio_reelz_scanner_NativeBridge_parseSubtitles(
    JNIEnv* env, jobject, jstring content, jstring baseUrl)
{
    const char* c = env->GetStringUTFChars(content, nullptr);
    const char* b = env->GetStringUTFChars(baseUrl, nullptr);
    HlsPlaylist pl = parseM3u8(c, b);
    env->ReleaseStringUTFChars(content, c);
    env->ReleaseStringUTFChars(baseUrl, b);
    std::string result = subtitlesToString(pl);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_axio_reelz_scanner_NativeBridge_forgeHeaders(
    JNIEnv* env, jobject, jstring referer, jstring origin, jboolean mobile, jboolean isXhr)
{
    const char* r = env->GetStringUTFChars(referer, nullptr);
    const char* o = env->GetStringUTFChars(origin,  nullptr);
    Headers h = forgeHeaders(r, o, (bool)mobile, (bool)isXhr);
    env->ReleaseStringUTFChars(referer, r);
    env->ReleaseStringUTFChars(origin,  o);
    // serialiseHeaders converts Headers (unordered_map) → "Key: Value\r\n" string
    std::string result = serialiseHeaders(h);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_axio_reelz_scanner_NativeBridge_getUserAgent(
    JNIEnv* env, jobject, jboolean mobile)
{
    // buildUserAgent is the actual function name in header_forge.h
    std::string result = buildUserAgent((bool)mobile);
    return env->NewStringUTF(result.c_str());
}

// ─── NEW: AES-128-CBC decryption stub ─────────────────────────────────────────
// Returns input unchanged until an AES implementation is linked.
// The JNI entry point is wired so Kotlin can call it without future app changes.

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_axio_reelz_scanner_NativeBridge_nativeDecryptAES128(
    JNIEnv* env, jobject,
    jbyteArray keyArr, jbyteArray ivArr, jbyteArray dataArr)
{
    (void)keyArr; (void)ivArr;  // suppress unused warnings — stub
    jsize len = env->GetArrayLength(dataArr);
    jbyteArray out = env->NewByteArray(len);
    jbyte* src = env->GetByteArrayElements(dataArr, nullptr);
    env->SetByteArrayRegion(out, 0, len, src);
    env->ReleaseByteArrayElements(dataArr, src, JNI_ABORT);
    return out;
}

// ─── NEW: MPEG-TS sync byte validation ────────────────────────────────────────
// Validates that a .ts segment has valid sync bytes (0x47 every 188 bytes).
// Prevents corrupt segments from silently breaking the merge step.

extern "C" JNIEXPORT jboolean JNICALL
Java_com_axio_reelz_scanner_NativeBridge_nativeValidateTsSync(
    JNIEnv* env, jobject, jbyteArray dataArr)
{
    jsize len = env->GetArrayLength(dataArr);
    if (len < 188) return JNI_FALSE;

    jbyte* data = env->GetByteArrayElements(dataArr, nullptr);

    // Find first sync byte
    int offset = -1;
    for (int i = 0; i < 188 && i < len; i++) {
        if ((uint8_t)data[i] == 0x47) { offset = i; break; }
    }

    bool valid = false;
    if (offset >= 0) {
        valid = true;
        for (int pos = offset; pos + 188 <= len; pos += 188) {
            if ((uint8_t)data[pos] != 0x47) { valid = false; break; }
        }
    }

    env->ReleaseByteArrayElements(dataArr, data, JNI_ABORT);
    return valid ? JNI_TRUE : JNI_FALSE;
}
