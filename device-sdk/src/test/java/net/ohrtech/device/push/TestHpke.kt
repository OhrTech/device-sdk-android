package net.ohrtech.device.push

import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 9180 base mode, recipient side, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-128-GCM: what
 * the TEE does for an x25519 push key, so the tests can open the .NET sealer's vectors on the JVM.
 */
internal object TestHpke {
    private val V1 = "HPKE-v1".toByteArray()

    fun open(skR: ByteArray, pkR: ByteArray, enc: ByteArray, info: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray {
        val kemId = byteArrayOf(0x00, 0x20)
        val kemSuite = "KEM".toByteArray() + kemId
        val suite = "HPKE".toByteArray() + kemId + byteArrayOf(0, 1, 0, 1)

        val dh = x25519(skR, enc)
        val eaePrk = labeledExtract(kemSuite, ByteArray(0), "eae_prk", dh)
        val shared = labeledExpand(kemSuite, eaePrk, "shared_secret", enc + pkR, 32)

        val pskIdHash = labeledExtract(suite, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(suite, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(suite, shared, "secret", ByteArray(0))
        val key = labeledExpand(suite, secret, "key", context, 16)
        val nonce = labeledExpand(suite, secret, "base_nonce", context, 12)

        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(ct)
    }

    private fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("XDH")
        val priv = kf.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, scalar))
        val le = u.reversedArray().also { it[0] = (it[0].toInt() and 0x7f).toByte() }
        val pub = kf.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, le)))
        return KeyAgreement.getInstance("XDH").run {
            init(priv)
            doPhase(pub, true)
            generateSecret()
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            // HKDF's empty salt is HashLen zeros (RFC 5869), the same HMAC key.
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
            doFinal(data)
        }

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray) =
        hmac(salt, V1 + suite + label.toByteArray() + ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
        val labeled = byteArrayOf((length shr 8).toByte(), length.toByte()) + V1 + suite + label.toByteArray() + info
        var t = ByteArray(0)
        var out = ByteArray(0)
        var i = 1
        while (out.size < length) {
            t = hmac(prk, t + labeled + byteArrayOf(i++.toByte()))
            out += t
        }
        return out.copyOf(length)
    }
}
