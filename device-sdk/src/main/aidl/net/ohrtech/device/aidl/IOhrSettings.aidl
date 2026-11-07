package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrListener;

/**
 * Area "settings": reading is always allowed, changing needs the "settings" grant. Keys and value
 * shapes are the hub's settings catalogue. A key the fleet locks refuses (ERROR_FLEET_LOCKED); an
 * app's change to an unlocked key holds until the fleet changes that setting.
 */
interface IOhrSettings {
    /** JSON list of the keys this Agent can read and change. */
    String keys() = 1;
    /** JSON {"value", "mode": "unmanaged"|"set"|"locked"}. ERROR_UNSUPPORTED for an unknown key. */
    String get(String key) = 2;
    /** valueJson is the value as JSON (true, 60, "300", [...], {...}). */
    void set(String key, String valueJson) = 3;
    /** "key" with {key, value, mode} as a setting changes. */
    void watch(IOhrListener listener) = 4;
    void unwatch(IOhrListener listener) = 5;
}
