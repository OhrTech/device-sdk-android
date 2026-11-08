package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrListener;

/**
 * Area "usb" (VERSION 2), no grant needed: the USB devices the fleet pre-approves for this app (setting usb.devices).
 * The Agent grants the app access to each when it is attached and makes the app its default handler; the app opens
 * it with Android's UsbManager as usual (getDeviceList().get(deviceName)), with no "Allow access?".
 */
interface IOhrUsb {
    /**
     * JSON list of the approved devices attached now: {"deviceName", "vendorId", "productId", "manufacturer", "product",
     * "serial", "name" (the fleet's label), "hasPermission"} or, for accessories, {"accessory": true, "manufacturer",
     * "model", "version", "serial", "name", "hasPermission"}.
     */
    String devices() = 1;
    /** "attached" and "detached", each with one device as in devices(). */
    void watch(IOhrListener listener) = 2;
    void unwatch(IOhrListener listener) = 3;
}
