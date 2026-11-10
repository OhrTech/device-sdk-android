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
 * Both never wait for the Agent, and may be called from any thread, the main one included: the arguments are
 * checked at once, and the call goes to the Agent from the library's own thread, in order. While the Agent
 * restarts, up to 1,000 wait for it; beyond that the newest are dropped. (Only the process's very first call to
 * the SDK may wait, while the library binds the Agent.)
 */
object OhrDiagnostics {
    private val api get() = IOhrDiagnostics.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_DIAGNOSTICS))

    private val waiting = java.util.concurrent.atomic.AtomicInteger()
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "OhrDeviceSdk-diagnostics").apply { isDaemon = true } }

    private fun send(call: () -> Unit) {
        if (waiting.incrementAndGet() > MAX_WAITING) {
            waiting.decrementAndGet()
            return
        }
        sender.execute {
            try {
                runCatching(call)
            } finally {
                waiting.decrementAndGet()
            }
        }
    }

    private const val MAX_WAITING = 1000

    /** [name]: lower camel or snake case, up to 64 characters; [data]: up to 2 KB as JSON. */
    @JvmStatic
    @JvmOverloads
    fun event(name: String, data: Map<String, Any?> = emptyMap()) = event(name, JSONObject(data))

    @JvmStatic
    fun event(name: String, data: JSONObject) {
        Agent.needsVersion2()
        checkArg(NAME.matches(name), "name") { "event names are [a-z][A-Za-z0-9_.]{0,63}" }
        val json = data.toString()
        checkArg(json.length <= 2048, "data") { "event data is at most 2 KB as JSON" }
        send { agentCall { api.event(name, json) } }
    }

    private val NAME = Regex("^[a-z][A-Za-z0-9_.]{0,63}$")

    /** A log line for log bundles; [priority] is android.util.Log's (Log.INFO, ...). */
    @JvmStatic
    fun log(priority: Int, tag: String, message: String) {
        Agent.needsVersion2()
        send { agentCall { api.log(priority, tag, message) } }
    }

    @JvmStatic
    fun i(tag: String, message: String) = log(Log.INFO, tag, message)

    @JvmStatic
    fun w(tag: String, message: String) = log(Log.WARN, tag, message)

    @JvmStatic
    fun e(tag: String, message: String) = log(Log.ERROR, tag, message)
}
