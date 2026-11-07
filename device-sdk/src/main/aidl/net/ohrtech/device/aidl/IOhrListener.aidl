package net.ohrtech.device.aidl;

/** Changes an area pushes to the app (its watch()); {@code json} is the new state, as that area's getter returns it. */
oneway interface IOhrListener {
    void onChange(String what, String json) = 1;
}
