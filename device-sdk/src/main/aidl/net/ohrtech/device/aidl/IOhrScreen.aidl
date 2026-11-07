package net.ohrtech.device.aidl;

/** One open embedded screen (IOhrScreens.open). */
interface IOhrScreen {
    /** The SurfaceView changed size (pixels). */
    void resize(int width, int height) = 1;

    /** New theme keys (IOhrScreens.THEME_*), e.g. the app switched to dark. */
    void setTheme(in Bundle theme) = 2;

    /** Close it: the SurfacePackage goes blank. */
    void close() = 3;
}
