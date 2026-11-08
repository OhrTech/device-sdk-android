package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrListener;

/** Area "power": battery state always; the rest needs the "power" grant. */
interface IOhrPower {
    /** JSON {"level": 0-100, "charging", "plugged": "ac"|"usb"|"wireless"|"dock"|null, "health", "temperatureC", "screenOn"}. */
    String battery() = 1;
    /** Reboot now; reported to the hub with the reason. */
    void reboot(String reason) = 2;
    /** JSON {"time": "HH:mm", "days": [1-7, Monday = 1], "reason"} to reboot on a schedule, or null to stop. */
    void scheduleReboot(@nullable String schedule) = 3;
    /** The schedule, or null. */
    @nullable String rebootSchedule() = 4;
    /** Seconds, 1 to 2147483 (Android keeps milliseconds in an int). ERROR_FLEET_LOCKED. */
    void setScreenTimeout(int seconds) = 5;
    /** 0-100. ERROR_FLEET_LOCKED. */
    void setBrightness(int percent) = 6;
    /** "battery" with battery()'s JSON. */
    void watch(IOhrListener listener) = 7;
    void unwatch(IOhrListener listener) = 8;
}
