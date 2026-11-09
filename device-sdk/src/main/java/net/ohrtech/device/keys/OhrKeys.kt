package net.ohrtech.device.keys

import androidx.annotation.WorkerThread
import net.ohrtech.device.Agent
import net.ohrtech.device.OhrException
import net.ohrtech.device.agentCall
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrKeys
import org.json.JSONArray

/**
 * Post-quantum and classical KEM keys whose private halves never leave the device's TEE (the
 * OhrTech KEM app): ML-KEM-768, ML-KEM-1024, X-Wing, and X25519 for HPKE. Each app has its own keys,
 * by alias; no other app can use them, and they go when the app is uninstalled. Every app the
 * organisation installed may use them.
 *
 * For signatures, use Android's own Keystore: ML-DSA-65 and Ed25519 keys there are TEE-backed too.
 *
 * The same keys through the JCA: [OhrTechProvider], [OhrKem] (shaped like `javax.crypto.KEM`) and
 * [OhrHpke] (an HPKE recipient). To prove to your server that a key is in this device's TEE, send it
 * [attest]'s result; your server checks it with the Management API
 * (`POST /v1/devices/{deviceId}/key-attestations/verify`).
 */
object OhrKeys {

    enum class Algorithm(internal val id: Int, val standardName: String) {
        ML_KEM_768(IOhrKeys.ALG_MLKEM768, "ML-KEM-768"),
        ML_KEM_1024(IOhrKeys.ALG_MLKEM1024, "ML-KEM-1024"),
        X_WING(IOhrKeys.ALG_XWING, "X-Wing"),

        /** HPKE DHKEM(X25519) only: [OhrHpke] opens; there is no raw encapsulate or decapsulate. */
        X25519(IOhrKeys.ALG_X25519, "X25519");

        companion object {
            @JvmStatic
            fun of(id: Int) = entries.firstOrNull { it.id == id } ?: throw OhrException.Unsupported("unknown key algorithm $id")

            @JvmStatic
            fun named(name: String) = entries.firstOrNull { it.standardName.equals(name, ignoreCase = true) }
                ?: throw OhrException.Unsupported("unknown key algorithm $name")
        }
    }

    data class KeyInfo(val alias: String, val algorithm: Algorithm, val createdAtMs: Long)

    /** An encapsulation: [ciphertext] to send, [sharedSecret] (32 bytes) to keep. */
    class Encapsulated(val ciphertext: ByteArray, val sharedSecret: ByteArray)

    /**
     * Proof that [publicKey] was made in this device's TEE for this app: send all of it, with the
     * challenge you used, to `POST /v1/devices/{deviceId}/key-attestations/verify` (base64 fields).
     * @property owner how the TEE names this app ("app:<the Agent's UUID for the app>:<package>"), as signed.
     */
    class Attestation(val algorithm: Algorithm, val publicKey: ByteArray, val owner: String, val coseSign1: ByteArray,
                      val diceChain: ByteArray)

    private val api get() = IOhrKeys.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_KEYS))

    /** Whether this device's OS has the OhrTech KEM app. */
    @JvmStatic
    @WorkerThread
    fun isAvailable(): Boolean = agentCall { api.available() }

    /**
     * A new key under [alias] (1-64 of A-Z a-z 0-9 . _ -, not starting "ohr."), replacing any key of
     * that alias. Returns its public key. At most 32 keys per app.
     */
    @JvmStatic
    @WorkerThread
    fun generate(alias: String, algorithm: Algorithm): ByteArray = agentCall { api.generate(alias, algorithm.id) }

    @JvmStatic
    @WorkerThread
    fun list(): List<KeyInfo> = JSONArray(agentCall { api.list() }).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { KeyInfo(it.getString("alias"), Algorithm.of(it.getInt("alg")), it.optLong("created")) }
    }

    /** The public key, or null if there's no such key. */
    @JvmStatic
    @WorkerThread
    fun publicKey(alias: String): ByteArray? = agentCall { api.publicKey(alias) }

    @JvmStatic
    @WorkerThread
    fun delete(alias: String) = agentCall { api.delete(alias) }

    /** The 32-byte shared secret of [ciphertext] (ML-KEM and X-Wing keys). */
    @JvmStatic
    @WorkerThread
    fun decapsulate(alias: String, ciphertext: ByteArray): ByteArray = agentCall { api.decapsulate(alias, ciphertext) }

    /** Encapsulates to anyone's public key of [algorithm] (ML-KEM, X-Wing); needs no key of yours. */
    @JvmStatic
    @WorkerThread
    fun encapsulate(algorithm: Algorithm, publicKey: ByteArray): Encapsulated =
        agentCall { api.encapsulate(algorithm.id, publicKey) }.let { Encapsulated(it.ciphertext, it.sharedSecret) }

    /**
     * HPKE (RFC 9180) base mode, one message, with the key's own KEM: [kdf] [OhrHpke.KDF_HKDF_SHA256],
     * [aead] one of OhrHpke's AEADs. Plaintext at most 8,192 bytes. Failed ("does not open") if it isn't for this key.
     */
    @JvmStatic
    @WorkerThread
    fun hpkeOpen(alias: String, kdf: Int, aead: Int, enc: ByteArray, info: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        agentCall { api.hpkeOpen(alias, kdf, aead, enc, info, aad, ciphertext) }

    /** Proof the key is this app's, in this device's TEE, bound to [challenge] (at most 128 bytes; your server's nonce). */
    @JvmStatic
    @WorkerThread
    fun attest(alias: String, challenge: ByteArray): Attestation = agentCall { api.attest(alias, challenge) }.let {
        Attestation(Algorithm.of(it.alg), it.publicKey, it.owner, it.coseSign1, it.diceChain)
    }
}
