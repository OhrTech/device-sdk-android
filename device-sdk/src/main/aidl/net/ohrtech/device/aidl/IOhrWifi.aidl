package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrListener;

/**
 * Area "wifi": reading is always allowed, changing needs the "wifi" grant. Networks use the shared
 * Wi-Fi model (the fleet setting wifi.networks' JSON). The fleet's networks are read-only here:
 * ERROR_FLEET_LOCKED (key wifi.networks).
 */
interface IOhrWifi {
    /** JSON {"enabled", "state": "on"|"off"|"turningOn"|"turningOff", "connected": a network (networks()) or null}. */
    String state() = 1;
    /** JSON list of the networks around: {"key", "title", "ssid", "security": [...], "level", "saved", "connected", "internet", "metered", "fleet"}. */
    String networks() = 2;
    void scan() = 3;
    /** JSON list of saved networks: {"ssid", "security"} or {"security": "passpoint", "passpoint": {"fqdn", "friendlyName"}}, plus "fleet". */
    String saved() = 4;
    /** Save a network (the model's JSON); returns its identity ("ssid:…", "passpoint:…"). ERROR_INVALID_CONFIG. */
    String save(String config) = 5;
    /** Join a network by its key (networks()) or identity (saved()). */
    void connect(String keyOrIdentity) = 6;
    /** Save, then join. Returns the identity. */
    String connectConfig(String config) = 7;
    void disconnect() = 8;
    void forget(String identity) = 9;
    /** ERROR_FLEET_LOCKED when the fleet locks wifi.enabled. */
    void setEnabled(boolean on) = 10;
    /** A Wi-Fi QR code the app scanned ("WIFI:…"): saves and joins it; returns the identity. */
    String connectQr(String text) = 11;
    /** "state" and "networks", with their JSON. */
    void watch(IOhrListener listener) = 12;
    void unwatch(IOhrListener listener) = 13;
}
