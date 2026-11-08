package net.ohrtech.device

import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrUsb
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * The USB devices the fleet pre-approves for this app (Agent 1061+; fleet setting usb.devices, set per app on the
 * fleet's Applications tab). When one is attached the Agent gives the app access and makes it the device's default
 * handler, so there is no "Allow access?": open it with Android's UsbManager as usual,
 * `usbManager.deviceList[device.deviceName]`.
 */
class OhrUsb internal constructor(private val sdk: OhrDeviceSdk) {
    /**
     * A device ([accessory] false: [deviceName], [vendorId], [productId], [product]) or accessory ([accessory] true:
     * [model], [version]); [name] is the fleet's label for it; [hasPermission] whether the app may open it now.
     */
    data class Device(
        val accessory: Boolean, val deviceName: String?, val vendorId: Int, val productId: Int, val manufacturer: String?,
        val product: String?, val model: String?, val version: String?, val serial: String?, val name: String?, val hasPermission: Boolean,
    )

    /** An approved device was attached ([attached] true) or detached. */
    data class Change(val attached: Boolean, val device: Device)

    private val api get() = IOhrUsb.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_USB))

    private fun needsVersion2() {
        if (sdk.session.agentInterface < 2) throw OhrException.Unsupported("This OhrTech Agent is too old for it", needsAgent = 1061)
    }

    /** The approved devices attached now. */
    @WorkerThread
    fun devices(): List<Device> {
        needsVersion2()
        val a = JSONArray(agentCall { api.devices() })
        return (0 until a.length()).map { device(a.getJSONObject(it)) }
    }

    private val watcher = Watcher({ api.watch(it) }, { api.unwatch(it) }) { c ->
        if (c.what == "attached" || c.what == "detached") Change(c.what == "attached", device(JSONObject(c.json))) else null
    }

    /** Approved devices as they are attached and detached. */
    val changes: Flow<Change> get() = watcher.flow.also { needsVersion2() }
    fun listen(executor: Executor, listener: OhrListener<Change>): Closeable {
        needsVersion2()
        return watcher.listen(executor, listener)
    }

    private fun device(o: JSONObject) = Device(
        o.optBoolean("accessory"), o.optString("deviceName").ifEmpty { null }, o.optInt("vendorId", -1), o.optInt("productId", -1),
        o.optString("manufacturer").ifEmpty { null }, o.optString("product").ifEmpty { null }, o.optString("model").ifEmpty { null },
        o.optString("version").ifEmpty { null }, o.optString("serial").ifEmpty { null }, o.optString("name").ifEmpty { null },
        o.optBoolean("hasPermission"),
    )
}
