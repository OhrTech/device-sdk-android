package net.ohrtech.device

import android.os.IBinder
import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrListener
import net.ohrtech.device.aidl.IOhrWifi
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Wi-Fi: state and the networks around, read always; changes with the fleet's "wifi" grant. The
 * fleet's own networks are read-only (FleetLocked "wifi.networks"). For a ready-made screen, put
 * an [OhrWifiView] in the layout.
 *
 * Network configs are the OhrTech Wi-Fi model, as JSON (the same as the fleet setting "Saved Wi-Fi
 * networks"); [WifiConfig] builds the common ones. Every type Android supports: open, OWE, WEP,
 * WPA/WPA2 (psk), WPA3 (sae), enterprise (eap: PEAP, TTLS, TLS, PWD, SIM, AKA), Passpoint.
 */
object OhrWifi {
    /** @property state "on", "off", "turningOn" or "turningOff"; [connected] the current network, or null. */
    data class State(val enabled: Boolean, val state: String, val connected: JSONObject?)

    /** One network in range. [key] joins it with [connect]. */
    data class Network(val key: String, val title: String, val ssid: String?, val security: List<String>, val level: Int,
                       val saved: Boolean, val connected: Boolean, val fleet: Boolean, val json: JSONObject)

    private val api get() = IOhrWifi.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_WIFI))

    private fun state(json: String) = JSONObject(json).let { State(it.optBoolean("enabled"), it.optString("state"), it.optJSONObject("connected")) }

    private fun networks(json: String) = JSONArray(json).objects().map {
        val sec = it.optJSONArray("security") ?: JSONArray()
        Network(it.optString("key"), it.optString("title"), it.str("ssid"), (0 until sec.length()).map { i -> sec.getString(i) },
            it.optInt("level", -1), it.optBoolean("saved"), it.optString("connected") == "connected", it.optBoolean("fleet"), it)
    }

    @JvmStatic
    @WorkerThread
    fun state(): State = state(agentCall { api.state() })
    @JvmStatic
    @WorkerThread
    fun networks(): List<Network> = networks(agentCall { api.networks() })
    @JvmStatic
    @WorkerThread
    fun scan() = agentCall { api.scan() }
    /** Saved networks: {"ssid", "security", "fleet"} (or "passpoint"). */
    @JvmStatic
    @WorkerThread
    fun saved(): List<JSONObject> = JSONArray(agentCall { api.saved() }).objects()

    /** Saves [config]; returns its identity ("ssid:…", "passpoint:…"). InvalidConfig names the field. */
    @JvmStatic
    @WorkerThread
    fun save(config: JSONObject): String = agentCall { api.save(config.toString()) }
    /** Joins a network by its [Network.key] or a saved identity. */
    @JvmStatic
    @WorkerThread
    fun connect(keyOrIdentity: String) = agentCall { api.connect(keyOrIdentity) }
    /** Saves, then joins. */
    @JvmStatic
    @WorkerThread
    fun connect(config: JSONObject): String = agentCall { api.connectConfig(config.toString()) }
    @JvmStatic
    @WorkerThread
    fun disconnect() = agentCall { api.disconnect() }
    @JvmStatic
    @WorkerThread
    fun forget(identity: String) = agentCall { api.forget(identity) }
    /** FleetLocked when the fleet locks wifi.enabled. */
    @JvmStatic
    @WorkerThread
    fun setEnabled(on: Boolean) = agentCall { api.setEnabled(on) }
    /** A Wi-Fi QR code the app scanned ("WIFI:…"): saves and joins it. */
    @JvmStatic
    @WorkerThread
    fun connectQr(text: String): String = agentCall { api.connectQr(text) }

    private fun watch(b: IBinder, l: IOhrListener) = IOhrWifi.Stub.asInterface(b).watch(l)
    private fun unwatch(b: IBinder, l: IOhrListener) = IOhrWifi.Stub.asInterface(b).unwatch(l)
    private val stateWatcher = Watcher(IOhrDeviceSdk.AREA_WIFI, ::watch, ::unwatch, current = { state() }) { c ->
        if (c.what == "state") state(c.json) else null
    }
    private val networkWatcher = Watcher(IOhrDeviceSdk.AREA_WIFI, ::watch, ::unwatch, current = { networks() }) { c ->
        if (c.what == "networks") networks(c.json) else null
    }

    @JvmStatic
    val stateChanges: Flow<State> get() = stateWatcher.flow

    @JvmStatic
    val networkChanges: Flow<List<Network>> get() = networkWatcher.flow

    @JvmStatic
    fun listenState(executor: Executor, listener: OhrListener<State>): Closeable = stateWatcher.listen(executor, listener)

    @JvmStatic
    fun listenNetworks(executor: Executor, listener: OhrListener<List<Network>>): Closeable = networkWatcher.listen(executor, listener)
}

/** The common network configs of the OhrTech Wi-Fi model. For enterprise and Passpoint, build the JSON (docs.ohrtech.net). */
object WifiConfig {
    @JvmStatic
    @JvmOverloads
    fun open(ssid: String, hidden: Boolean = false): JSONObject = base(ssid, "open", hidden)

    /** Enhanced Open. */
    @JvmStatic
    @JvmOverloads
    fun owe(ssid: String, hidden: Boolean = false): JSONObject = base(ssid, "owe", hidden)

    /** WPA/WPA2 personal: 8-63 characters, or 64 hex digits. */
    @JvmStatic
    @JvmOverloads
    fun psk(ssid: String, password: String, hidden: Boolean = false): JSONObject = base(ssid, "psk", hidden).put("password", password)

    /** WPA3 personal. */
    @JvmStatic
    @JvmOverloads
    fun sae(ssid: String, password: String, hidden: Boolean = false): JSONObject = base(ssid, "sae", hidden).put("password", password)

    private fun base(ssid: String, security: String, hidden: Boolean) =
        JSONObject().put("ssid", ssid).put("security", security).apply { if (hidden) put("hidden", true) }
}
