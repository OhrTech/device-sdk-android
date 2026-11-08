package net.ohrtech.device

import android.util.Log
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrDiagnostics
import org.json.JSONObject

/**
 * The app's own diagnostics for the hub (Agent 1061+). Crashes need nothing: the Agent reports every crash,
 * native crash and ANR of the hub's apps itself.
 *
 * - [event]: an event of the app's own ("order_failed", "printer_offline"), shown with the device's events in the
 *   portal and the Management API. At most 60 a minute.
 * - [log]: lines the Agent keeps (the newest 500) for log bundles, which support asks the hub for, together with
 *   the app's logcat and recent events.
 *
 * Both are one way calls: they never block, and may be called from any thread, the main one included.
 */
class OhrDiagnostics internal constructor(private val sdk: OhrDeviceSdk) {
    private val api get() = IOhrDiagnostics.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_DIAGNOSTICS))

    private fun needsVersion2() {
        if (sdk.session.agentInterface < 2) throw OhrException.Unsupported("This OhrTech Agent is too old for it", needsAgent = 1061)
    }

    /** [name]: lower camel or snake case, up to 64 characters; [data]: up to 2 KB as JSON. */
    @JvmOverloads
    fun event(name: String, data: Map<String, Any?> = emptyMap()) = event(name, JSONObject(data))

    fun event(name: String, data: JSONObject) {
        needsVersion2()
        require(Regex("^[a-z][A-Za-z0-9_.]{0,63}$").matches(name)) { "event names are [a-z][A-Za-z0-9_.]{0,63}" }
        val json = data.toString()
        require(json.length <= 2048) { "event data is at most 2 KB" }
        agentCall { api.event(name, json) }
    }

    /** A log line for log bundles; [priority] is android.util.Log's (Log.INFO, ...). */
    fun log(priority: Int, tag: String, message: String) {
        needsVersion2()
        agentCall { api.log(priority, tag, message) }
    }

    fun i(tag: String, message: String) = log(Log.INFO, tag, message)
    fun w(tag: String, message: String) = log(Log.WARN, tag, message)
    fun e(tag: String, message: String) = log(Log.ERROR, tag, message)
}
