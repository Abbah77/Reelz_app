package com.reelz.remoteconfig

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the AES-256-CBC encrypted remote config payload.
 *
 * Payload format (after base64 decode):
 *   byte[0]      = version byte (currently 1 — reserved for future re-keying)
 *   byte[1..16]  = IV (16 bytes)
 *   byte[17..]   = AES-CBC ciphertext (PKCS7 padded)
 *
 * Key must be exactly 32 bytes. Store it via NDK / BuildConfig — never in plain
 * Kotlin source in production. For this implementation it comes from ConfigCrypto.KEY.
 */
object ConfigCrypto {

    // ⚠️  Match the Python encryption script exactly.
    //     In production, load this from a native function via JNI rather than
    //     keeping it in Kotlin bytecode.
    private val KEY: ByteArray = "ReelzCfgKey2024!ReelzCfgKey2024!".toByteArray(Charsets.UTF_8)

    /**
     * @param base64Payload The raw `d` field from the JSON envelope
     * @return Decrypted UTF-8 JSON string, or null on any failure
     */
    fun decrypt(base64Payload: String): String? = runCatching {
        val raw = Base64.decode(base64Payload, Base64.DEFAULT)

        // Sanity check: need at least version(1) + IV(16) + 1 block(16)
        require(raw.size >= 33) { "Payload too short: ${raw.size} bytes" }

        // byte[0] is the version tag — skip it
        val iv         = raw.copyOfRange(1, 17)
        val ciphertext = raw.copyOfRange(17, raw.size)

        val keySpec = SecretKeySpec(KEY, "AES")
        val ivSpec  = IvParameterSpec(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val plain = cipher.doFinal(ciphertext)
        String(plain, Charsets.UTF_8)
    }.getOrElse { e ->
        android.util.Log.e("ConfigCrypto", "Decryption failed", e)
        null
    }
}
