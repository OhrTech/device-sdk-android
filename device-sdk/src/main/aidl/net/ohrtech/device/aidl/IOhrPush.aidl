package net.ohrtech.device.aidl;

/**
 * Area "push", allowed for every app the hub installed: OhrTech push, end to end (the hub carries
 * only sealed messages). The app's key for its suite lives in the TEE (the OhrTech KEM app); the
 * app never holds it, and opens messages through open(). Delivery: the Agent binds the app's
 * service for ACTION_PUSH, which must require PERMISSION_RECEIVE_PUSH (IOhrPushReceiver).
 */
interface IOhrPush {
    /** HPKE DHKEM(X25519) + Ed25519: small, the default. */
    const int SUITE_X25519 = 1;
    /** HPKE X-Wing (X25519 + ML-KEM-768) + Ed25519 and ML-DSA-65: post-quantum. */
    const int SUITE_XWING_MLDSA65 = 2;

    const String ACTION_PUSH = "net.ohrtech.device.action.PUSH";
    /** The permission the app's push service must require; only the OhrTech Agent holds it. */
    const String PERMISSION_RECEIVE_PUSH = "net.ohrtech.device.permission.RECEIVE_PUSH";

    /**
     * Makes the app's push key for {@code suite} in the TEE, has the hub verify its attestation and
     * issue a token: JSON {"token": "psh_…", "suite": "x25519"|"xwing-mldsa65", "publicKey": base64}.
     * Send token and public key to your server. Registering again gives a new token and ends the old.
     * Waits for the hub (up to 30 s): ERROR_FAILED if it can't be reached.
     */
    String register(int suite) = 1;

    /** register()'s JSON for the current token, or null. */
    @nullable String token() = 2;

    /** Ends the token and deletes the key. */
    void unregister() = 3;

    /**
     * Opens a message's {@code enc} and {@code ct} (after you verified its signature) with the
     * app's push key and the token's HPKE info. The plaintext: CBOR {"id", "exp", "data"}.
     * ERROR_FAILED ("does not open") if it isn't for this token.
     */
    byte[] open(in byte[] enc, in byte[] ct) = 4;

    /** Tells the hub the app dropped message {@code messageId} (reason: "signature", "expired", "duplicate", ...). */
    void reportDropped(String messageId, String reason) = 5;
}
