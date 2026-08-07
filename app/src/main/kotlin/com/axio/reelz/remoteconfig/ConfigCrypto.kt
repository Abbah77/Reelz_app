package com.axio.reelz.remoteconfig

import android.util.Base64
import java.util.zip.InflaterInputStream
import java.io.ByteArrayInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the remote config payload.
 *
 * The AES key is loaded from a native C++ library (NDK).
 * This prevents trivial key extraction via jadx/apktool — Kotlin bytecode is
 * easily decompiled, but native .so requires significantly more effort to reverse.
 *
 * Payload format (after base64 decode):
 *   byte[0]      = version byte
 *                  1 = AES-CBC only (plain JSON after decrypt)
 *                  2 = AES-CBC + zlib (compress before encrypt, decompress after decrypt)
 *   byte[1..16]  = IV (16 bytes, random per-encrypt)
 *   byte[17..]   = AES-CBC ciphertext (PKCS7 padded)
 *
 * To generate a new 32-byte key:
 *   python3 -c "import secrets; print(secrets.token_hex(16))"  # → 32 hex chars = 16 bytes
 *   OR: openssl rand -base64 32 | head -c 32   # → 32 ASCII chars
 *
 * NDK setup (app/src/main/cpp/native-lib.cpp):
 *   #include <jni.h>
 *   extern "C" JNIEXPORT jstring JNICALL
 *   Java_com_axio_reelz_remoteconfig_ConfigCrypto_nativeKey(JNIEnv* env, jobject) {
 *       return env->NewStringUTF("your-32-byte-key-here");
 *   }
 *
 * app/build.gradle.kts (inside android {} block):
 *   externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
 *
 * app/src/main/cpp/CMakeLists.txt:
 *   cmake_minimum_required(VERSION 3.22.1)
 *   project("reelz")
 *   add_library(reelz SHARED native-lib.cpp)
 *   find_library(log-lib log)
 *   target_link_libraries(reelz ${log-lib})
 */
object ConfigCrypto {

    // Load the native library containing nativeKey().
    // Library name must match the CMakeLists.txt project name.
    init {
        try {
            System.loadLibrary("reelz")
        } catch (e: UnsatisfiedLinkError) {
            // NDK library not present — falls back to null key below, which will
            // fail decryption. This makes it obvious during development that the
            // native library was not built, rather than silently using a weak key.
            android.util.Log.e("ConfigCrypto", "Native library 'reelz' not found. " +
                "Build the NDK module or the config cannot be decrypted.", e)
        }
    }

    /**
     * Native function — returns the 32-byte AES key as a UTF-8 string.
     * Implemented in app/src/main/cpp/native-lib.cpp.
     *
     * The @JvmStatic is intentional: the JNI name resolution uses the class name,
     * not the companion object, when declared on the object directly.
     */
    private external fun nativeKey(): String

    /**
     * Derive the key bytes. If the native library isn't loaded, this throws
     * UnsatisfiedLinkError, which propagates to decrypt() and is caught there.
     */
    private val KEY: ByteArray
        get() = nativeKey().toByteArray(Charsets.UTF_8)

    fun decrypt(base64Payload: String): String? = runCatching {
        val raw = Base64.decode(base64Payload, Base64.DEFAULT)
        require(raw.size >= 33) { "Payload too short: ${raw.size} bytes" }

        val version    = raw[0].toInt()
        val iv         = raw.copyOfRange(1, 17)
        val ciphertext = raw.copyOfRange(17, raw.size)

        val keyBytes = KEY
        require(keyBytes.size == 32) {
            "AES key must be exactly 32 bytes for AES-256; got ${keyBytes.size}"
        }

        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec  = IvParameterSpec(iv)
        val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(ciphertext)

        when (version) {
            2    -> String(InflaterInputStream(ByteArrayInputStream(decrypted)).readBytes(), Charsets.UTF_8)
            else -> String(decrypted, Charsets.UTF_8)
        }
    }.getOrElse { e ->
        android.util.Log.e("ConfigCrypto", "Decryption failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** Convenience — decrypt a single encrypted key string. */
    fun decryptKey(encrypted: String): String? = decrypt(encrypted)
}
