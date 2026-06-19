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
 * Payload format (after base64 decode):
 *   byte[0]      = version byte
 *                  1 = AES-CBC only (plain JSON)
 *                  2 = AES-CBC + zlib compress (everything secret, smallest size)
 *   byte[1..16]  = IV (16 bytes)
 *   byte[17..]   = AES-CBC ciphertext (PKCS7 padded)
 */
object ConfigCrypto {

    // ⚠️ In production, load this from a native JNI function instead.
    private val KEY = "ReelzCfgKey2024!ReelzCfgKey2024!".toByteArray(Charsets.UTF_8)

    fun decrypt(base64Payload: String): String? = runCatching {
        val raw = Base64.decode(base64Payload, Base64.DEFAULT)
        require(raw.size >= 33) { "Payload too short" }

        val version    = raw[0].toInt()
        val iv         = raw.copyOfRange(1, 17)
        val ciphertext = raw.copyOfRange(17, raw.size)

        val keySpec = SecretKeySpec(KEY, "AES")
        val ivSpec  = IvParameterSpec(iv)
        val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(ciphertext)

        when (version) {
            2 -> {
                // version 2: decompress after decrypt
                val inflater = InflaterInputStream(ByteArrayInputStream(decrypted))
                String(inflater.readBytes(), Charsets.UTF_8)
            }
            else -> {
                // version 1: plain JSON after decrypt
                String(decrypted, Charsets.UTF_8)
            }
        }
    }.getOrElse { e ->
        android.util.Log.e("ConfigCrypto", "Decryption failed", e)
        null
    }

    /** Decrypt a single encrypted key string (used in v2 per-key format if needed). */
    fun decryptKey(encrypted: String): String? = decrypt(encrypted)
}
