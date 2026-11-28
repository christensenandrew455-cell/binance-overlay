package com.decisionoverlay.checker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secret is imported directly into the phone; it is never uploaded to GitHub or Vercel. */
internal object KalshiPhoneCredentials {
    private const val ALIAS = "kalshi-phone-key-v1"
    private const val PREFS = "kalshi_encrypted_credentials"
    private const val ID = "encrypted_id"
    private const val PRIVATE = "encrypted_private"
    private val utf8 = StandardCharsets.UTF_8

    fun exists(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.contains(ID) && p.contains(PRIVATE)
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .setRandomizedEncryptionRequired(true)
         .build())
        return generator.generateKey()
    }

    private fun encrypt(s: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val bytes = cipher.doFinal(s.toByteArray(utf8))
        return Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val data = Base64.decode(s, Base64.NO_WRAP)
        require(data.size > 28) { "Invalid encrypted credentials" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), utf8)
    }

    fun privateKey(pem: String): PrivateKey {
        val normalized = pem.replace("\\n", "\n").trim()
        val pkcs1 = normalized.startsWith("-----BEGIN RSA PRIVATE KEY-----")
        require(pkcs1 || normalized.startsWith("-----BEGIN PRIVATE KEY-----")) {
            "Use a PEM file beginning BEGIN PRIVATE KEY or BEGIN RSA PRIVATE KEY"
        }
        require(normalized.endsWith(if(pkcs1) "-----END RSA PRIVATE KEY-----"
            else "-----END PRIVATE KEY-----")) { "Incomplete PEM key" }
        val data = normalized.lines().filter { !it.startsWith("-----") }.joinToString("")
        val raw = Base64.decode(data, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(if(pkcs1) wrapPkcs1(raw) else raw)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
            .getOrElse { KeyFactory.getInstance("Ed25519").generatePrivate(spec) }

    }

    private fun asnLength(n: Int): ByteArray = when {
        n < 128 -> byteArrayOf(n.toByte())
        n < 256 -> byteArrayOf(0x81.toByte(),n.toByte())
        n < 65536 -> byteArrayOf(0x82.toByte(),(n shr 8).toByte(),n.toByte())
        else -> error("Unsupported key length")
    }
    private fun asn(tag: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + asnLength(bytes.size) + bytes
    private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
        val algorithmId = byteArrayOf(
            0x30,0x0d,0x06,0x09,0x2a,0x86.toByte(),0x48,0x86.toByte(),
            0xf7.toByte(),0x0d,0x01,0x01,0x01,0x05,0x00
        )
        return asn(0x30,byteArrayOf(0x02,0x01,0x00)+algorithmId+asn(0x04,pkcs1))
    }

    fun save(context: Context, keyId: String, pem: String) {
        val id = keyId.trim()
        require(id.length in 8..256 && !id.contains("\n")) { "Invalid Kalshi key ID" }
        require(pem.length in 120..20000) { "Invalid PEM file size" }
        privateKey(pem) // Verify key is readable before writing.
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(p.edit().putString(ID, encrypt(id)).putString(PRIVATE, encrypt(pem)).commit()) {
            "Couldn't securely store credentials"
        }
    }

    fun get(context: Context): Pair<String, PrivateKey> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString(ID, null) ?: error("Import Kalshi credentials first")
        val pem = p.getString(PRIVATE, null) ?: error("Import Kalshi credentials first")
        return Pair(decrypt(id), privateKey(decrypt(pem)))
    }

    fun sign(key: PrivateKey, message: ByteArray): String {
        val signer = if (key.algorithm.equals("RSA", ignoreCase = true)) {
            Signature.getInstance("RSASSA-PSS").apply {
                setParameter(PSSParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, 32, 1))
            }
        } else {
            Signature.getInstance("Ed25519")
        }
        signer.initSign(key)
        signer.update(message)
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    fun delete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(ALIAS)) deleteEntry(ALIAS)
        }
    }
}
