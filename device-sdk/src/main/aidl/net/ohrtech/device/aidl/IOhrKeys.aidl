package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.OhrKemResult;
import net.ohrtech.device.aidl.OhrKeyAttestation;

/**
 * Area "keys", allowed for every app the hub installed: ML-KEM and X-Wing keys whose private
 * halves never leave the TEE (the OhrTech KEM Trusty app). Each app has its own keys, by alias;
 * no other app can use them, and they are deleted when the app is uninstalled. Needs an OS with
 * the OhrTech KEM app: ERROR_UNSUPPORTED otherwise (available() says).
 */
interface IOhrKeys {
    const int ALG_MLKEM768 = 1;
    const int ALG_MLKEM1024 = 2;
    const int ALG_XWING = 3;
    /** X25519 (HPKE DHKEM(X25519, HKDF-SHA256)): hpkeOpen and attest only; decapsulate and encapsulate are ERROR_UNSUPPORTED. */
    const int ALG_X25519 = 4;

    /** Whether this device has the TEE app. */
    boolean available() = 1;

    /**
     * A new key under {@code alias} ([A-Za-z0-9._-], 1-64), replacing any key of that alias.
     * Returns its public key. At most 32 keys per app: ERROR_INVALID_CONFIG beyond.
     */
    byte[] generate(String alias, int alg) = 2;

    /** JSON list of the app's keys: [{"alias", "alg", "created": epoch ms}]. */
    String list() = 3;

    /** The public key, or null if there is no such key. */
    @nullable byte[] publicKey(String alias) = 4;

    void delete(String alias) = 5;

    /** The 32-byte shared secret of {@code ciphertext}. ERROR_INVALID_CONFIG if there is no such key. */
    byte[] decapsulate(String alias, in byte[] ciphertext) = 6;

    /** Encapsulates to any public key of {@code alg} (no key of the app's needed). */
    OhrKemResult encapsulate(int alg, in byte[] publicKey) = 7;

    /**
     * HPKE (RFC 9180) base mode, single shot, with the key's KEM: {@code kdf} 1 (HKDF-SHA256),
     * {@code aead} 1 (AES-128-GCM), 2 (AES-256-GCM) or 3 (ChaCha20-Poly1305). Plaintext at most
     * 8192 bytes. ERROR_FAILED ("does not open") for a message that isn't for this key.
     */
    byte[] hpkeOpen(String alias, int kdf, int aead, in byte[] enc, in byte[] info, in byte[] aad,
            in byte[] ciphertext) = 8;

    /** Proof the key is this app's, in this device's TEE, bound to {@code challenge} (at most 128 bytes). */
    OhrKeyAttestation attest(String alias, in byte[] challenge) = 9;
}
