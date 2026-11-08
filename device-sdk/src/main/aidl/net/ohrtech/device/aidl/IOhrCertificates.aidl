package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrListener;

/**
 * Area "certificates" (VERSION 2), no grant needed: the app's client certificates (fleet setting apps.certificates).
 */
interface IOhrCertificates {
    /**
     * Asks the hub to certify a key the app made: chain is the key's attestation chain (base64 DER, challenge
     * "ohr-certificate:" + alias). The answer comes to callback, once: "issued" {"chain": [base64 DER, leaf first]}
     * or "refused" {"reason"}.
     */
    void request(String alias, in String[] chain, IOhrListener callback) = 1;
    /**
     * JSON {"issuingCa": the fleet gives the app an issuing CA, "trust": [PEM], "imported": [{"alias", "pkcs12" (base64),
     * "password"}]}: the app's own, from the fleet.
     */
    String config() = 2;
    /** "changed" when config() does. */
    void watch(IOhrListener listener) = 3;
    void unwatch(IOhrListener listener) = 4;
}
