package net.ohrtech.device.aidl;

/**
 * Proof that a key was made in this device's TEE for this app. Verify it with the hub
 * (POST /v1/devices/{id}/key-attestations/verify), which knows each device's registered key.
 */
parcelable OhrKeyAttestation {
    /** ALG_* of IOhrKeys. */
    int alg;
    byte[] publicKey;
    /** The app, as the TEE recorded it: "app:<app id>:<package>". */
    String owner;
    /** COSE_Sign1 over {"v": 1, "alg", "pubSha256", "owner", "challenge"}, external AAD "ohrtech-kem-attest-v1". */
    byte[] coseSign1;
    /** DICE chain (CBOR) of the signer: [the device's registered key, the KEM app's certificate]. */
    byte[] diceChain;
}
