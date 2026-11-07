package net.ohrtech.device.aidl;

import net.ohrtech.device.aidl.IOhrListener;

/** Area "network": reading is always allowed, changing needs the "network" grant. */
interface IOhrNetwork {
    /**
     * JSON {"active": {"transport": "wifi"|"cellular"|"ethernet"|"vpn"|null, "validated", "captive",
     * "metered", "vpn"}, "mobileData", "airplaneMode", "dataRoaming", "ethernet": {"connected"}}.
     */
    String state() = 1;
    void setMobileData(boolean on) = 2;
    void setAirplaneMode(boolean on) = 3;
    /** ERROR_FLEET_LOCKED when the fleet locks network.data_roaming. */
    void setDataRoaming(boolean on) = 4;
    /** "state" with state()'s JSON. */
    void watch(IOhrListener listener) = 5;
    void unwatch(IOhrListener listener) = 6;
}
