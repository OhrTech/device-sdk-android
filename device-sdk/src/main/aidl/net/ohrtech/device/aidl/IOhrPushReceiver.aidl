package net.ohrtech.device.aidl;

/**
 * What the app's push service returns from onBind (the Device SDK's OhrPushService). The OhrTech
 * Agent calls it; the service must require IOhrPush.PERMISSION_RECEIVE_PUSH, so nothing else can.
 */
oneway interface IOhrPushReceiver {
    /**
     * A message for {@code token}: the envelope as the sender made it (CBOR {"v", "suite", "enc",
     * "ct", "sig"}). Verify, open (IOhrPush.open), check expiry and duplicates; report a drop.
     * {@code priority} 1 = high: the app may start a foreground service now.
     */
    void onMessage(String messageId, String token, in byte[] envelope, int priority, long expiresAtMs) = 1;

    /** The token changed or ended (null): register()'s JSON. */
    void onTokenChanged(@nullable String tokenJson) = 2;
}
