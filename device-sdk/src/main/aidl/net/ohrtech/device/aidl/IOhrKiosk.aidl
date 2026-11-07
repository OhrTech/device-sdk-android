package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrListener;

/**
 * Area "kiosk", needs the "kiosk" grant: pins the app with Android's lock task, as the system
 * (the app itself can't unpin). Features are Android's DevicePolicyManager.LOCK_TASK_FEATURE_* bits.
 */
interface IOhrKiosk {
    const int FEATURE_SYSTEM_INFO = 1;
    const int FEATURE_NOTIFICATIONS = 2;
    const int FEATURE_HOME = 4;
    const int FEATURE_OVERVIEW = 8;
    const int FEATURE_GLOBAL_ACTIONS = 16;
    const int FEATURE_KEYGUARD = 32;
    const int FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 64;

    /** Pin task {@code taskId} (one of the caller's) with these features. */
    void start(int taskId, int features) = 1;
    /** Unpin. ERROR_NOT_ALLOWED when the fleet keeps the app pinned (kiosk.app_exit off). */
    void stop() = 2;
    /** JSON {"pinned": this app is pinned, "mode": "none"|"locked"|"pinned", "features", "appMayExit"}. */
    String state() = 3;
    /** "mode" with {"mode"} when kiosk mode starts or ends (state() says whether it is this app's). */
    void watch(IOhrListener listener) = 4;
    void unwatch(IOhrListener listener) = 5;
}
