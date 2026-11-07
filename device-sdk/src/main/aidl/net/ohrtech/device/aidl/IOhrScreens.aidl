package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrScreenCallback;

/**
 * Area "screens": screens the Agent draws inside the app's own layout (SurfaceControlViewHost).
 * The app shows the returned SurfacePackage in a SurfaceView (setChildSurfacePackage); touch,
 * keyboard and accessibility work as in its own views. A screen opens no activity and no other
 * window, so it can't lead out of a kiosk app.
 *
 * Screens: SCREEN_WIFI (needs the "wifi" area). Same versioning rules as IOhrDeviceSdk.
 */
interface IOhrScreens {
    const String SCREEN_WIFI = "wifi";

    /** Bundle keys of what open() returns: the SurfacePackage, and the IOhrScreen binder. */
    const String KEY_SURFACE_PACKAGE = "surfacePackage";
    const String KEY_SCREEN = "screen";

    // Theme keys (all optional): booleans, colours as ARGB ints, sizes in dp.
    const String THEME_DARK = "dark";
    const String THEME_ACCENT = "accent";
    const String THEME_BACKGROUND = "background";
    const String THEME_TEXT = "text";
    const String THEME_CORNER_RADIUS_DP = "cornerRadiusDp";

    // Wi-Fi screen options (booleans, all true unless set false).
    const String WIFI_SWITCH = "switch";
    const String WIFI_ADD_NETWORK = "addNetwork";
    const String WIFI_ENTERPRISE = "enterprise";
    const String WIFI_FORGET = "forget";
    const String WIFI_SAVED = "saved";

    /**
     * Open screen {@code name} for the SurfaceView whose host token, display and size (pixels)
     * these are. Returns {KEY_SURFACE_PACKAGE: SurfacePackage, KEY_SCREEN: an IOhrScreen binder
     * to resize, restyle or close it}. The screen closes by itself when the app dies or
     * callback's binder does. Throws ERROR_NOT_ALLOWED, ERROR_UNSUPPORTED (unknown screen).
     */
    Bundle open(String name, IBinder hostToken, int displayId, int width, int height,
            in Bundle theme, in Bundle options, IOhrScreenCallback callback) = 1;
}
