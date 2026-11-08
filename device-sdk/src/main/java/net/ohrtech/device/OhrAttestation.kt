package net.ohrtech.device

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.WorkerThread
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Device attestation: proof, for the app's server, that a request comes from **this app,
 * unmodified, on a specific, genuine and secure OhrTech device**, with no secret in the APK.
 *
 * [attest] makes a key in the app's own Android Keystore, in the TEE, bound to the server's
 * challenge. Its certificate chain runs up to the OhrTech Attestation Root: the key's certificate
 * (KeyMint's attestation: the app's package and signer, verified boot, OS patch levels, the
 * challenge), the unit's attestation key certificate (which names the device, `dev_…`), the OhrTech
 * intermediate and root. The server checks the chain once, with the Management API
 * (`POST /v1/attestations/verify`) or offline against the published root, and keeps the key; after
 * that, [token] proves each request.
 *
 * Two suites: [Suite.EC] (P-256), and [Suite.EC_MLDSA65], post-quantum: an EC key and an ML-DSA-65
 * key, both attested, everything signed with both. ML-DSA alone is not offered.
 */
class OhrAttestation {

    enum class Suite { EC, EC_MLDSA65 }

    /**
     * The attested key(s): [ec] and, for [Suite.EC_MLDSA65], [mlDsa65], each a certificate chain from
     * the key's certificate to the OhrTech root. Send them to your server with the challenge.
     */
    class Attestation(val suite: Suite, val ec: List<X509Certificate>, val mlDsa65: List<X509Certificate>?) {
        /** The chains as base64 DER, as `POST /v1/attestations/verify` takes them. */
        fun toJson(): JSONObject = JSONObject()
            .put("suite", suite.name.lowercase().replace('_', '-'))
            .put("ec", org.json.JSONArray(ec.map { b64(it.encoded) }))
            .apply { mlDsa65?.let { put("mlDsa65", org.json.JSONArray(it.map { c -> b64(c.encoded) })) } }
    }

    private val keyStore: KeyStore get() = KeyStore.getInstance(STORE).apply { load(null) }

    /**
     * A new attested key (or keys) bound to [challenge] (your server's nonce, at most 128 bytes),
     * replacing the one before. Takes a moment: the TEE makes and certifies the key.
     */
    @WorkerThread
    @JvmOverloads
    fun attest(challenge: ByteArray, suite: Suite = Suite.EC): Attestation {
        require(challenge.size in 1..128) { "the challenge is 1 to 128 bytes" }
        return keystore { attestNow(challenge, suite) }
    }

    /** The Keystore's checked exceptions as [OhrException.Failed], so Java needn't catch Exception. */
    private inline fun <T> keystore(block: () -> T): T = try {
        block()
    } catch (e: java.security.GeneralSecurityException) {
        throw OhrException.Failed("the device's keystore: ${e.message}")
    } catch (e: java.io.IOException) {
        throw OhrException.Failed("the device's keystore: ${e.message}")
    }

    private fun attestNow(challenge: ByteArray, suite: Suite): Attestation {
        val ec = generate(EC_ALIAS, KeyProperties.KEY_ALGORITHM_EC, challenge) { setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256) }
        val pq = if (suite == Suite.EC_MLDSA65) generate(MLDSA_ALIAS, "ML-DSA-65", challenge) { this } else {
            runCatching { keyStore.deleteEntry(MLDSA_ALIAS) }
            null
        }
        return Attestation(suite, ec, pq)
    }

    private fun generate(alias: String, algorithm: String, challenge: ByteArray,
                         spec: KeyGenParameterSpec.Builder.() -> KeyGenParameterSpec.Builder): List<X509Certificate> {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setAttestationChallenge(challenge).spec()
        KeyPairGenerator.getInstance(algorithm, STORE).apply { initialize(builder.build()) }.generateKeyPair()
        return keyStore.getCertificateChain(alias).map { it as X509Certificate }
    }

    /** The suite of the current attested key, or null before [attest]. */
    val suite: Suite?
        get() = keystore {
            when {
                !keyStore.containsAlias(EC_ALIAS) -> null
                keyStore.containsAlias(MLDSA_ALIAS) -> Suite.EC_MLDSA65
                else -> Suite.EC
            }
        }

    /**
     * A short-lived proof for one request, signed by the attested key(s):
     * `header.payload.ecSignature[.mlDsa65Signature]`, base64url, with
     * header `{"typ":"ohr-attest","alg":"ES256"|"ES256+ML-DSA-65"}` and payload
     * `{"aud", "iat", "exp", ?"nonce"}`. The EC signature is JWS's (r ‖ s), so an ES256 token is a
     * standard JWS; the ML-DSA-65 signature (pure, empty context) covers `header.payload` too.
     */
    @WorkerThread
    @JvmOverloads
    fun token(audience: String, ttlSeconds: Long = 300, nonce: String? = null): String {
        val s = suite ?: throw OhrException.Failed("attest() first: there is no attested key")
        return keystore { tokenNow(s, audience, ttlSeconds, nonce) }
    }

    private fun tokenNow(s: Suite, audience: String, ttlSeconds: Long, nonce: String?): String {
        val now = System.currentTimeMillis() / 1000
        val header = JSONObject().put("typ", "ohr-attest").put("alg", if (s == Suite.EC) "ES256" else "ES256+ML-DSA-65")
        val payload = JSONObject().put("aud", audience).put("iat", now).put("exp", now + ttlSeconds).apply { nonce?.let { put("nonce", it) } }
        val signed = "${b64url(header.toString().toByteArray())}.${b64url(payload.toString().toByteArray())}"
        val ec = p1363(sign("SHA256withECDSA", EC_ALIAS, signed.toByteArray()))
        val pq = if (s == Suite.EC_MLDSA65) "." + b64url(sign("ML-DSA-65", MLDSA_ALIAS, signed.toByteArray())) else ""
        return "$signed.${b64url(ec)}$pq"
    }

    private fun sign(algorithm: String, alias: String, data: ByteArray): ByteArray =
        Signature.getInstance(algorithm).run {
            initSign(keyStore.getKey(alias, null) as PrivateKey)
            update(data)
            sign()
        }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val EC_ALIAS = "net.ohrtech.attestation.ec"
        const val MLDSA_ALIAS = "net.ohrtech.attestation.mldsa65"
        /** An ECDSA P-256 signature from DER (SEQUENCE of two INTEGERs) to JWS's 64 bytes, r ‖ s. */
        fun p1363(der: ByteArray): ByteArray {
            val out = ByteArray(64)
            var i = 2 + (if (der[1].toInt() and 0x80 != 0) der[1].toInt() and 0x7f else 0)
            for (half in 0..1) {
                require(der[i].toInt() == 0x02) { "not an ECDSA signature" }
                var len = der[i + 1].toInt()
                var start = i + 2
                while (len > 32) { start++; len-- }  // a leading 0x00
                System.arraycopy(der, start, out, half * 32 + 32 - len, len)
                i = start + len
            }
            return out
        }
        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
