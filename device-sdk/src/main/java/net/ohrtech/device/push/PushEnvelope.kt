package net.ohrtech.device.push

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * The sealed, signed message a sender makes (docs: OhrTech push §2): CBOR
 * {"v": 1, "suite", "enc", "ct", "sig": {"ed25519", ? "mldsa65"}}.
 */
internal class PushEnvelope(
    val suite: OhrPush.Suite,
    val enc: ByteArray,
    val ct: ByteArray,
    val ed25519: ByteArray,
    val mlDsa65: ByteArray?,
) {
    /** Whether [key] made every signature the suite has, for [token]. */
    fun signedBy(token: String, key: SenderKey): Boolean {
        val data = signedBytes(token, suite, enc, ct)
        if (!verify(ED25519, ED25519_SPKI, key.ed25519, data, ed25519)) return false
        if (suite != OhrPush.Suite.XWING_MLDSA65) return true
        val pq = key.mlDsa65 ?: return false
        return verify(MLDSA65, MLDSA65_SPKI, pq, data, mlDsa65 ?: return false)
    }

    companion object {
        const val LABEL = "ohrtech-push-v1"
        const val MAX_DATA = 4096
        private const val MAX_CT = MAX_DATA + 128 + 16
        const val ED25519_SIGNATURE = 64
        const val MLDSA65_SIGNATURE = 3309

        private const val ED25519 = "Ed25519"
        private const val MLDSA65 = "ML-DSA-65"

        /** SubjectPublicKeyInfo heads that make the raw keys X.509 encodings (RFC 8410; RFC 9881). */
        private val ED25519_SPKI = hex("302a300506032b6570032100")
        private val MLDSA65_SPKI = hex("308207b2300b0609608648016503040312038207a100")

        /** What the sender signs: ["ohrtech-push-v1", token, suite, enc, ct], shortest heads. */
        fun signedBytes(token: String, suite: OhrPush.Suite, enc: ByteArray, ct: ByteArray): ByteArray =
            Cbor.Writer().array(5).text(LABEL).text(token).text(suite.wireName).bytes(enc).bytes(ct).encode()

        /** The envelope, its shape checked as the hub checks it. [CborException] says why not. */
        fun parse(bytes: ByteArray): PushEnvelope {
            val m = Cbor.decode(bytes) as? Map<*, *> ?: throw CborException("not a map")
            for (k in m.keys) if (k !in FIELDS) throw CborException("unknown field $k")
            if (m["v"] != 1L) throw CborException("not version 1")
            val suite = OhrPush.Suite.entries.firstOrNull { it.wireName == m["suite"] } ?: throw CborException("unknown suite")
            val enc = m["enc"] as? ByteArray ?: throw CborException("no enc")
            if (enc.size != suite.encSize) throw CborException("enc is not ${suite.encSize} bytes")
            val ct = m["ct"] as? ByteArray ?: throw CborException("no ct")
            if (ct.size < 16 || ct.size > MAX_CT) throw CborException("ct is not 16 to $MAX_CT bytes")
            val sig = m["sig"] as? Map<*, *> ?: throw CborException("no sig")
            for (k in sig.keys) if (k != "ed25519" && k != "mldsa65") throw CborException("unknown signature $k")
            val ed = sig["ed25519"] as? ByteArray
            if (ed?.size != ED25519_SIGNATURE) throw CborException("no Ed25519 signature")
            val ml = sig["mldsa65"]?.let { it as? ByteArray ?: throw CborException("mldsa65 is not bytes") }
            if (suite == OhrPush.Suite.XWING_MLDSA65) {
                if (ml?.size != MLDSA65_SIGNATURE) throw CborException("no ML-DSA-65 signature")
            } else if (ml != null) {
                throw CborException("an ML-DSA-65 signature in an x25519 message")
            }
            return PushEnvelope(suite, enc, ct, ed, ml)
        }

        private val FIELDS = setOf("v", "suite", "enc", "ct", "sig")

        private fun verify(alg: String, spki: ByteArray, raw: ByteArray, data: ByteArray, sig: ByteArray): Boolean =
            try {
                val key = KeyFactory.getInstance(alg).generatePublic(X509EncodedKeySpec(spki + raw))
                Signature.getInstance(alg).run {
                    initVerify(key)
                    update(data)
                    verify(sig)
                }
            } catch (e: java.security.GeneralSecurityException) {
                false
            }

        internal fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** What HPKE sealed: CBOR {"id": tstr, "exp": uint (Unix seconds), "data": bstr}. */
internal class PushPlaintext(val id: String, val expSeconds: Long, val data: ByteArray) {
    companion object {
        fun parse(bytes: ByteArray): PushPlaintext {
            val m = Cbor.decode(bytes) as? Map<*, *> ?: throw CborException("not a map")
            val id = m["id"] as? String ?: throw CborException("no id")
            if (id.isEmpty() || id.length > 64) throw CborException("id is not 1 to 64 characters")
            val exp = m["exp"] as? Long ?: throw CborException("no exp")
            if (exp < 0 || exp > 253_402_300_799) throw CborException("exp is not a time from 1970 to 9999")
            val data = m["data"] as? ByteArray ?: throw CborException("no data")
            if (data.size > PushEnvelope.MAX_DATA) throw CborException("data is over ${PushEnvelope.MAX_DATA} bytes")
            return PushPlaintext(id, exp, data)
        }
    }
}
