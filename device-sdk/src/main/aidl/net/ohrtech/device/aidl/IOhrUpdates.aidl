package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrListener;

/**
 * Area "updates", always allowed: the app's own updates. A version the hub offers as "available"
 * is downloaded ahead (on Wi-Fi); the app decides when to install it.
 */
interface IOhrUpdates {
    const int WHEN_NOW = 0;
    /** The next time the screen is off. */
    const int WHEN_IDLE = 1;

    /** JSON {"versionCode", "versionName", "notes", "downloaded": true} of the version offered, or null. */
    @nullable String available() = 1;

    /**
     * Install the offered version (WHEN_*). The app is replaced, so it stops; Android starts it again.
     * Throws ERROR_UNSUPPORTED when nothing is offered.
     */
    void install(int when) = 2;

    /** "available" with available()'s JSON (or "null") when the offer changes. */
    void watch(IOhrListener listener) = 3;
    void unwatch(IOhrListener listener) = 4;
}
