package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.SdkSession;

/**
 * The OhrTech Agent's Device SDK service. Apps reach it through the net.ohrtech:device-sdk
 * library, which binds the Agent (net.ohrtech.agent) with ACTION_BIND.
 *
 * Rules that keep every library version working with every Agent version:
 * - Append only. A method keeps its transaction id for ever; a new method takes the next id and
 *   raises VERSION. Nothing is removed, renumbered or changed in meaning.
 * - Parcelables are structured, and only ever gain fields at the end (readers skip what they don't
 *   know, and see defaults for what an older writer didn't send).
 * - The library reads version() and refuses a newer method on an older Agent (Unsupported, with
 *   the Agent version it needs) instead of calling it.
 *
 * Errors are IllegalStateException with the message "ohr:<code>:<detail>", code one of the
 * ERROR_ constants, detail as noted (ServiceSpecificException is a system API apps can't name).
 */
interface IOhrDeviceSdk {
    const String ACTION_BIND = "net.ohrtech.device.action.SDK";

    /** This interface's version. 1: version, hello, area. */
    const int VERSION = 1;

    /** The caller is not an app the hub installed on this device. Message: empty. */
    const int ERROR_NOT_MANAGED = 1;
    /** The fleet does not let this app use the area. Message: the area. */
    const int ERROR_NOT_ALLOWED = 2;
    /** The fleet locks the setting or network. Message: the settings key. */
    const int ERROR_FLEET_LOCKED = 3;
    /** This Agent, device or build can't do it. Message: why. */
    const int ERROR_UNSUPPORTED = 4;
    /** A config the app passed is invalid. Message: "field\nreason". */
    const int ERROR_INVALID_CONFIG = 5;
    /** The Agent tried and Android refused. Message: why. */
    const int ERROR_FAILED = 6;

    /** Always allowed: device identity, read-only. */
    const String AREA_DEVICE = "device";
    /** Always allowed: the app's own updates. */
    const String AREA_UPDATES = "updates";
    /** Always allowed: TEE-backed keys, kept per app. */
    const String AREA_KEYS = "keys";
    /** Always allowed: OhrTech push to the app itself. */
    const String AREA_PUSH = "push";
    /** Reading is always allowed; changing needs the fleet's grant. */
    const String AREA_WIFI = "wifi";
    /** Reading is always allowed; changing needs the fleet's grant. */
    const String AREA_NETWORK = "network";
    /** Needs the fleet's grant. */
    const String AREA_KIOSK = "kiosk";
    /** Reading battery state is always allowed; the rest needs the fleet's grant. */
    const String AREA_POWER = "power";
    /** Reading is always allowed; changing needs the fleet's grant. */
    const String AREA_SETTINGS = "settings";
    /** Embedded screens (IOhrScreens); each screen needs the area it changes ("wifi" for Wi-Fi). */
    const String AREA_SCREENS = "screens";

    /** This Agent's VERSION. */
    int version() = 1;

    /**
     * Who the Agent takes the caller for, and what it may do. clientVersion is the VERSION the
     * library was built against, for the Agent's logs. Throws ERROR_NOT_MANAGED.
     */
    SdkSession hello(int clientVersion) = 2;

    /**
     * The binder of one area (AREA_ constants), whose interface is that area's own (IOhrWifi for
     * "wifi", and so on), versioned the same way. Every call on it is checked against its caller,
     * so a binder passed to another app is of no use there. Throws ERROR_NOT_MANAGED, and
     * ERROR_UNSUPPORTED for an area this Agent doesn't have.
     */
    IBinder area(String name) = 3;
}
