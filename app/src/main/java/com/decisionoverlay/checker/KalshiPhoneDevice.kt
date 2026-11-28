package com.decisionoverlay.checker

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature

/**
 * The private half never leaves Android Keystore. Share ONLY publicKeyBase64()
 * to pair this phone with Vercel's KALSHI_DEVICE_PUBLIC_KEY env variable.
 */
internal object KalshiPhoneDevice {
    private const val KEY_ALIAS = "kalshi-device-pairing-v1"

    private fun pair(): KeyPair {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val privateKey=ks.getKey(KEY_ALIAS,null) as? java.security.PrivateKey
        val certificate=ks.getCertificate(KEY_ALIAS)
        if(privateKey!=null&&certificate!=null)
            return KeyPair(certificate.publicKey,privateKey)
        val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .build())
        return generator.generateKeyPair()
    }

    fun publicKeyBase64(): String = Base64.encodeToString(pair().public.encoded,Base64.NO_WRAP)

    fun sign(timestamp:String, method:String, route:String, body:String): String {
        val sha=MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val payload=listOf(timestamp,method,route,sha).joinToString("\n")
        val sig=Signature.getInstance("SHA256withRSA")
        sig.initSign(pair().private)
        sig.update(payload.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(sig.sign(),Base64.NO_WRAP)
    }
}
