package net.ohrtech.device.aidl;

/**
 * Area "diagnostics" (VERSION 2), no grant needed: the app's own events and log, for the hub. Both one way:
 * they never block the app. The app's crashes need nothing: the Agent reports them itself.
 */
interface IOhrDiagnostics {
    /**
     * An event of the app's own, shown next to the device's events at the hub. name: [a-z][A-Za-z0-9_.]{0,63};
     * json: an object, at most 2 KB. At most 60 a minute per app; more are counted, not sent.
     */
    oneway void event(String name, String json) = 1;
    /** A log line the Agent keeps (the newest 500 per app) for log bundles. priority: android.util.Log's. */
    oneway void log(int priority, String tag, String message) = 2;
}
