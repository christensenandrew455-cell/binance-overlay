package com.decisionoverlay.checker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stores only the owner's separate Vercel access code, never a Kalshi API key.
 * The code is entered once on the device, encrypted under Android Keystore,
 * and is never included in the APK, GitHub source, logs, or network requests.
 */
internal object KalshiPhoneAccess {
    private const val ALIAS = "kalshi-access-aes-v1"
    private const val PREFS = "kalshi_owner_access"
    private const val VALUE = "owner_encrypted_access_v1"
    private val utf8 = StandardCharsets.UTF_8

    fun hasCode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(VALUE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    fun save(context: Context, code: String) {
        // Preserve Unicode and all symbols exactly as entered.
        require(code.length in 20..256) { "Access code must contain at least 20 characters" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(code.toByteArray(utf8))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(VALUE, encoded).commit()) { "Could not save access code" }
    }

    private fun read(context: Context): ByteArray {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(VALUE, null) ?: error("Access code not set up yet")
        val encrypted = Base64.decode(value, Base64.NO_WRAP)
        require(encrypted.size > 28) { "Invalid stored access code" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }

    fun sign(context: Context, timestamp: String, method: String, path: String, body: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(utf8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val message = listOf(timestamp, method, path, digest).joinToString("\n")
        val code = read(context)
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(code, "HmacSHA256"))
            Base64.encodeToString(mac.doFinal(message.toByteArray(utf8)), Base64.NO_WRAP)
        } finally {
            code.fill(0)
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).commit()
    }
}
