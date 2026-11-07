package net.ohrtech.device.sample

import net.ohrtech.device.keys.OhrHpke
import net.ohrtech.device.keys.OhrKeys
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * An HPKE (RFC 9180) base-mode sender, HKDF-SHA256 / AES-128-GCM, for the device checks: what a
 * server does to a TEE key. X25519 is DHKEM with the platform's XDH; ML-KEM and X-Wing take the
 * TEE's encapsulate (their shared secret is HPKE's directly).
 */
object TestSeal {
    class Sealed(val enc: ByteArray, val ct: ByteArray)

    private val V1 = "HPKE-v1".toByteArray()
    private val X25519_SPKI = hex("302a300506032b656e032100")

    fun seal(keys: OhrKeys, alg: OhrKeys.Algorithm, pkR: ByteArray, info: ByteArray, aad: ByteArray, pt: ByteArray): Sealed {
        val kem = OhrHpke.kemId(alg)
        val (shared, enc) = if (alg == OhrKeys.Algorithm.X25519) dhkem(pkR) else
            keys.encapsulate(alg, pkR).let { it.sharedSecret to it.ciphertext }
        val suite = "HPKE".toByteArray() + i2(kem) + i2(OhrHpke.KDF_HKDF_SHA256) + i2(OhrHpke.AEAD_AES_128_GCM)
        val context = byteArrayOf(0) + extract(suite, ByteArray(0), "psk_id_hash", ByteArray(0)) +
            extract(suite, ByteArray(0), "info_hash", info)
        val secret = extract(suite, shared, "secret", ByteArray(0))
        val key = expand(suite, secret, "key", context, 16)
        val nonce = expand(suite, secret, "base_nonce", context, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return Sealed(enc, c.doFinal(pt))
    }

    private fun dhkem(pkR: ByteArray): Pair<ByteArray, ByteArray> {
        val eph = KeyPairGenerator.getInstance("XDH").generateKeyPair() // X25519 by default; Conscrypt takes no parameter spec
        val enc = eph.public.encoded.takeLast(32).toByteArray()
        val peer = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(X25519_SPKI + pkR))
        val dh = KeyAgreement.getInstance("XDH").run { init(eph.private); doPhase(peer, true); generateSecret() }
        val suite = "KEM".toByteArray() + i2(0x0020)
        val prk = extract(suite, ByteArray(0), "eae_prk", dh)
        return expand(suite, prk, "shared_secret", enc + pkR, 32) to enc
    }

    private fun i2(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        doFinal(data)
    }

    private fun extract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray) = hmac(salt, V1 + suite + label.toByteArray() + ikm)

    private fun expand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, n: Int): ByteArray {
        val labeled = i2(n) + V1 + suite + label.toByteArray() + info
        var t = ByteArray(0)
        var out = ByteArray(0)
        var i = 1
        while (out.size < n) {
            t = hmac(prk, t + labeled + byteArrayOf(i++.toByte()))
            out += t
        }
        return out.copyOf(n)
    }

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
