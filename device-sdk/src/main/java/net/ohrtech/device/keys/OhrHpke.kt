package net.ohrtech.device.keys

import androidx.annotation.WorkerThread
import java.security.InvalidKeyException
import java.security.PrivateKey

/**
 * HPKE (RFC 9180) base mode, the recipient's side, with a TEE key: the sender seals to the key's
 * public half with any HPKE library; the app opens here, and the private key never leaves the TEE.
 * ```
 * val recipient = OhrHpke.recipient(key)                    // an OhrPrivateKey; AES-128-GCM
 * val plaintext = recipient.open(enc, info, aad, ciphertext)
 * ```
 * The KEM is the key's: DHKEM(X25519, HKDF-SHA256) for X25519 keys, X-Wing, ML-KEM-768 or
 * ML-KEM-1024. KDF HKDF-SHA256. One message per sender context (sequence 0); plaintext at most
 * 8,192 bytes.
 */
object OhrHpke {
    const val KDF_HKDF_SHA256 = 0x0001
    const val AEAD_AES_128_GCM = 0x0001
    const val AEAD_AES_256_GCM = 0x0002
    const val AEAD_CHACHA20_POLY1305 = 0x0003

    /** The HPKE KEM id of a key's algorithm, for the sender's library. */
    @JvmStatic
    fun kemId(algorithm: OhrKeys.Algorithm) = when (algorithm) {
        OhrKeys.Algorithm.X25519 -> 0x0020
        OhrKeys.Algorithm.ML_KEM_768 -> 0x0041
        OhrKeys.Algorithm.ML_KEM_1024 -> 0x0042
        OhrKeys.Algorithm.X_WING -> 0x647a
    }

    class Recipient internal constructor(private val key: OhrPrivateKey, private val aead: Int) {
        /** The plaintext; [net.ohrtech.device.OhrException.Failed] ("does not open") if it isn't for this key. */
        @WorkerThread
        @JvmOverloads
        fun open(enc: ByteArray, info: ByteArray, aad: ByteArray = ByteArray(0), ciphertext: ByteArray): ByteArray =
            OhrKeys.hpkeOpen(key.alias, KDF_HKDF_SHA256, aead, enc, info, aad, ciphertext)
    }

    /** A recipient for messages sealed to [privateKey]'s public half, with [aead]. */
    @JvmStatic
    @JvmOverloads
    fun recipient(privateKey: PrivateKey, aead: Int = AEAD_AES_128_GCM): Recipient {
        val k = privateKey as? OhrPrivateKey ?: throw InvalidKeyException("not an OhrTech TEE key")
        net.ohrtech.device.checkArg(aead in AEAD_AES_128_GCM..AEAD_CHACHA20_POLY1305, "aead") { "1 (AES-128-GCM), 2 (AES-256-GCM) or 3 (ChaCha20-Poly1305)" }
        return Recipient(k, aead)
    }
}
