package net.ohrtech.device.keys

import androidx.annotation.WorkerThread
import net.ohrtech.device.OhrException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A key encapsulation mechanism over the TEE keys, shaped like Java's `javax.crypto.KEM` (which
 * Android doesn't offer for these algorithms):
 * ```
 * OhrTechProvider.install(sdk)
 * val kem = OhrKem.getInstance("ML-KEM-768")
 * val (secret, ciphertext) = kem.newEncapsulator(theirPublicKey).encapsulate()   // the sender
 * val secret = kem.newDecapsulator(myPrivateKey).decapsulate(ciphertext)          // the TEE key's owner
 * ```
 * ML-KEM-768, ML-KEM-1024 and X-Wing. X25519 keys have no raw KEM here: open HPKE with [OhrHpke].
 * Each call is a binder call into the Agent and the TEE: not on the main thread.
 */
class OhrKem private constructor(val algorithm: OhrKeys.Algorithm) {

    /** [key]: the 32-byte shared secret (algorithm "Generic"); [encapsulation]: the ciphertext to send. */
    class Encapsulated(val key: SecretKey, private val ciphertext: ByteArray) {
        fun encapsulation(): ByteArray = ciphertext.clone()
        operator fun component1() = key
        operator fun component2() = encapsulation()
    }

    inner class Encapsulator internal constructor(private val publicKey: ByteArray) {
        fun secretSize() = SECRET_SIZE
        fun encapsulationSize() = encapsulationSize(algorithm)

        @WorkerThread
        fun encapsulate(): Encapsulated {
            val e = OhrTechProvider.sdk.keys.encapsulate(algorithm, publicKey)
            return Encapsulated(secret(e.sharedSecret), e.ciphertext)
        }
    }

    inner class Decapsulator internal constructor(private val key: OhrPrivateKey) {
        fun secretSize() = SECRET_SIZE
        fun encapsulationSize() = encapsulationSize(algorithm)

        /** The shared secret of [encapsulation]; an [OhrException] if the TEE refuses. */
        @WorkerThread
        fun decapsulate(encapsulation: ByteArray): SecretKey {
            require(encapsulation.size == encapsulationSize()) { "a ${algorithm.standardName} encapsulation is ${encapsulationSize()} bytes" }
            return secret(OhrTechProvider.sdk.keys.decapsulate(key.alias, encapsulation))
        }
    }

    /** Encapsulates to [publicKey]: an [OhrPublicKey], or any key of this algorithm in format "RAW". */
    fun newEncapsulator(publicKey: PublicKey): Encapsulator {
        if (!publicKey.algorithm.equals(algorithm.standardName, ignoreCase = true) || publicKey.format != "RAW")
            throw InvalidKeyException("not a raw ${algorithm.standardName} public key")
        return newEncapsulator(publicKey.encoded)
    }

    /** Encapsulates to a raw public key of this algorithm. */
    fun newEncapsulator(publicKey: ByteArray): Encapsulator {
        if (publicKey.size != publicKeySize(algorithm))
            throw InvalidKeyException("a ${algorithm.standardName} public key is ${publicKeySize(algorithm)} bytes")
        return Encapsulator(publicKey.clone())
    }

    /** Decapsulates with a TEE key of this algorithm (from KeyPairGenerator or the "OhrTech" KeyStore). */
    fun newDecapsulator(privateKey: PrivateKey): Decapsulator {
        val k = privateKey as? OhrPrivateKey ?: throw InvalidKeyException("not an OhrTech TEE key")
        if (k.keyAlgorithm != algorithm) throw InvalidKeyException("a ${k.keyAlgorithm.standardName} key, not ${algorithm.standardName}")
        return Decapsulator(k)
    }

    companion object {
        const val SECRET_SIZE = 32

        /** "ML-KEM-768", "ML-KEM-1024" or "X-Wing". */
        @JvmStatic
        fun getInstance(algorithm: String): OhrKem {
            val alg = runCatching { OhrKeys.Algorithm.named(algorithm) }.getOrNull()
            if (alg == null || alg == OhrKeys.Algorithm.X25519) throw NoSuchAlgorithmException("OhrKem has no $algorithm")
            return OhrKem(alg)
        }

        @JvmStatic
        fun publicKeySize(algorithm: OhrKeys.Algorithm) = when (algorithm) {
            OhrKeys.Algorithm.ML_KEM_768 -> 1184
            OhrKeys.Algorithm.ML_KEM_1024 -> 1568
            OhrKeys.Algorithm.X_WING -> 1216
            OhrKeys.Algorithm.X25519 -> 32
        }

        @JvmStatic
        fun encapsulationSize(algorithm: OhrKeys.Algorithm) = when (algorithm) {
            OhrKeys.Algorithm.ML_KEM_768 -> 1088
            OhrKeys.Algorithm.ML_KEM_1024 -> 1568
            OhrKeys.Algorithm.X_WING -> 1120
            OhrKeys.Algorithm.X25519 -> 32
        }

        private fun secret(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "Generic").also { bytes.fill(0) }
    }
}
