/**
 * native-lib.cpp — AES config key storage in native code.
 *
 * ─── SECURITY NOTICE ────────────────────────────────────────────────────────
 * Replace "your-32-byte-key-here!!!!!!!!" with your real 32-byte AES key
 * before building a release APK. The key stored here is much harder to
 * extract than one in Kotlin bytecode (requires native disassembly vs jadx),
 * but is NOT impossible. For defense-in-depth:
 *   1. Keep this key different from any other secret in your system.
 *   2. Rotate the config encryption key every 6–12 months.
 *   3. Enable R8/ProGuard + code obfuscation in your release build.
 *   4. Consider splitting the key across multiple native calls with XOR
 *      assembly if you need higher assurance in the future.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * JNI function name convention:
 *   Java_<package_underscored>_<class>_<method>
 *   Package: com.axio.reelz.remoteconfig → com_axio_reelz_remoteconfig
 *   Class:   ConfigCrypto (Kotlin object compiles to ConfigCrypto class)
 *   Method:  nativeKey
 */

#include <jni.h>
#include <string>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_axio_reelz_remoteconfig_ConfigCrypto_nativeKey(
        JNIEnv *env,
        jobject /* this */) {
    // ⚠️ Replace this with your actual 32-character AES-256 key before release.
    // Generate one with: python3 -c "import secrets; print(secrets.token_urlsafe(24))"
    const char* key = "your-32-byte-key-here!!!!!!!!!!!";
    return env->NewStringUTF(key);
}
