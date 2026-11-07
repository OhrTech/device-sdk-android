package net.ohrtech.device.aidl;

/** What an embedded screen tells the app. */
oneway interface IOhrScreenCallback {
    /**
     * EVENT_* with its data: "connected" {ssid}, "closed" {}, "error" {message}, "height" {px}
     * (the content's preferred height, for a view that wraps its content).
     */
    void onEvent(String event, in Bundle data) = 1;
}
