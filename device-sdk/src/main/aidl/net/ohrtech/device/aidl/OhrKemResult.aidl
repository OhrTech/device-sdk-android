package net.ohrtech.device.aidl;

/** An encapsulation: the ciphertext to send, and the 32-byte shared secret to keep. */
parcelable OhrKemResult {
    byte[] ciphertext;
    byte[] sharedSecret;
}
