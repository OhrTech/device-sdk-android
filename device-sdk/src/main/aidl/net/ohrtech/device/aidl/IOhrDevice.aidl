package net.ohrtech.device.aidl;

/** Area "device", always allowed: who this device is. */
interface IOhrDevice {
    /**
     * JSON: {"deviceId": "dev_…", "serial", "fleet": {"id": "flt_…", "name"}, "org": {"id": "org_…", "name"},
     * "os": {"release", "build", "securityPatch", "incremental"}, "agentVersion", "online": hub connected now}.
     * Ids are null until the hub has sent its first policy.
     */
    String info() = 1;
}
