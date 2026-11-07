package net.ohrtech.device.aidl;

/** What IOhrDeviceSdk.hello returns. Fields are only ever appended. */
parcelable SdkSession {
    /** The Agent's IOhrDeviceSdk.VERSION. */
    int agentInterface;
    /** The Agent's own version code (1047, ...). */
    long agentVersion;
    /** The hub's id of the app this caller is. */
    String app;
    /** The caller's package. */
    String packageName;
    /** The hub's id of the app version installed, or null when the APK is not one the hub shipped. */
    @nullable String appVersion;
    /**
     * What the app may do: the always-allowed areas, "wifi.read", "network.read", "power.read" and
     * "settings.read", and each area the fleet grants it by name (AREA_ constants).
     */
    String[] areas;
}
