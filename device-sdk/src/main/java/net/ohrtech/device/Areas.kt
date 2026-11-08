package net.ohrtech.device

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.ohrtech.device.aidl.IOhrDevice
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrKiosk
import net.ohrtech.device.aidl.IOhrNetwork
import net.ohrtech.device.aidl.IOhrPower
import net.ohrtech.device.aidl.IOhrSettings
import net.ohrtech.device.aidl.IOhrUpdates
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executor

internal fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** The device: who it is, as the OhrTech hub and the Management API name it. Always allowed. */
class OhrDevice internal constructor(private val sdk: OhrDeviceSdk) {
    data class Named(val id: String, val name: String)

    /**
     * @property deviceId "dev_…" (null until the hub sent the device its first policy);
     * [online]: the Agent is connected to the hub now.
     */
    data class Info(
        val deviceId: String?, val serial: String?, val fleet: Named?, val org: Named?,
        val osRelease: String, val osBuild: String, val securityPatch: String, val osIncremental: String,
        val agentVersion: Long, val online: Boolean,
    )

    private val api get() = IOhrDevice.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_DEVICE))

    @WorkerThread
    fun info(): Info {
        val o = JSONObject(agentCall { api.info() })
        fun named(n: String) = o.optJSONObject(n)?.let { Named(it.optString("id"), it.optString("name")) }
        val os = o.getJSONObject("os")
        return Info(o.str("deviceId"), o.str("serial"), named("fleet"), named("org"), os.optString("release"),
            os.optString("build"), os.optString("securityPatch"), os.optString("incremental"), o.optLong("agentVersion"),
            o.optBoolean("online"))
    }

    suspend fun infoAsync(): Info = withContext(Dispatchers.IO) { info() }
}

/** The app's own updates: a version the hub offers as available, downloaded ahead. Always allowed. */
class OhrUpdates internal constructor(private val sdk: OhrDeviceSdk) {
    data class Update(val versionCode: Long, val versionName: String?, val notes: String?, val downloaded: Boolean)

    private val api get() = IOhrUpdates.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_UPDATES))

    private fun parse(json: String?): Update? = json?.takeIf { it != "null" }?.let {
        val o = JSONObject(it)
        Update(o.optLong("versionCode"), o.str("versionName"), o.str("notes"), o.optBoolean("downloaded"))
    }

    /** The offered update, or null. */
    @WorkerThread
    fun available(): Update? = parse(agentCall { api.available() })

    /**
     * Installs the offered update: now, or [whenIdle] the next time the screen is off. The app is
     * replaced, so it stops; Android starts it again if it was on screen.
     */
    @WorkerThread
    fun install(whenIdle: Boolean = false) =
        agentCall { api.install(if (whenIdle) IOhrUpdates.WHEN_IDLE else IOhrUpdates.WHEN_NOW) }

    private val watcher = Watcher({ api.watch(it) }, { api.unwatch(it) }) { c -> if (c.what == "available") parse(c.json) ?: NONE else null }

    /** The offer as it changes ([NONE] when withdrawn). */
    val changes: Flow<Update> get() = watcher.flow
    fun listen(executor: Executor, listener: OhrListener<Update>): Closeable = watcher.listen(executor, listener)

    companion object {
        /** What [changes] gives when nothing is offered any more. */
        @JvmField
        val NONE = Update(0, null, null, false)
    }
}

/** Mobile data, airplane mode, roaming: read always, change with the fleet's "network" grant. */
class OhrNetwork internal constructor(private val sdk: OhrDeviceSdk) {
    /** @property transport "wifi", "cellular", "ethernet", "vpn" or "other"; null [active]: no network. */
    data class Active(val transport: String, val validated: Boolean, val captive: Boolean, val metered: Boolean, val vpn: Boolean)
    data class State(val active: Active?, val mobileData: Boolean, val airplaneMode: Boolean, val dataRoaming: Boolean,
                     val ethernetConnected: Boolean)

    private val api get() = IOhrNetwork.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_NETWORK))

    private fun parse(json: String): State {
        val o = JSONObject(json)
        val a = o.optJSONObject("active")?.let {
            Active(it.optString("transport"), it.optBoolean("validated"), it.optBoolean("captive"), it.optBoolean("metered"), it.optBoolean("vpn"))
        }
        return State(a, o.optBoolean("mobileData"), o.optBoolean("airplaneMode"), o.optBoolean("dataRoaming"),
            o.optJSONObject("ethernet")?.optBoolean("connected") ?: false)
    }

    @WorkerThread
    fun state(): State = parse(agentCall { api.state() })
    @WorkerThread
    fun setMobileData(on: Boolean) = agentCall { api.setMobileData(on) }
    @WorkerThread
    fun setAirplaneMode(on: Boolean) = agentCall { api.setAirplaneMode(on) }
    /** FleetLocked when the fleet locks network.data_roaming. */
    @WorkerThread
    fun setDataRoaming(on: Boolean) = agentCall { api.setDataRoaming(on) }

    private val watcher = Watcher({ api.watch(it) }, { api.unwatch(it) }) { c -> if (c.what == "state") parse(c.json) else null }
    val changes: Flow<State> get() = watcher.flow
    fun listen(executor: Executor, listener: OhrListener<State>): Closeable = watcher.listen(executor, listener)
}

/**
 * Kiosk mode: pins the app with Android's lock task, as the system, so the app can't be left
 * (and can't unpin itself either). Needs the fleet's "kiosk" grant. [stop] works only if the
 * fleet lets kiosk apps leave; otherwise the hub or the exit PIN ends it.
 */
class OhrKiosk internal constructor(private val sdk: OhrDeviceSdk) {
    /** What stays available in kiosk mode (combine with `or`). */
    object Feature {
        const val SYSTEM_INFO = IOhrKiosk.FEATURE_SYSTEM_INFO
        const val NOTIFICATIONS = IOhrKiosk.FEATURE_NOTIFICATIONS
        const val HOME = IOhrKiosk.FEATURE_HOME
        const val OVERVIEW = IOhrKiosk.FEATURE_OVERVIEW
        const val GLOBAL_ACTIONS = IOhrKiosk.FEATURE_GLOBAL_ACTIONS
        const val KEYGUARD = IOhrKiosk.FEATURE_KEYGUARD
        const val BLOCK_ACTIVITY_START_IN_TASK = IOhrKiosk.FEATURE_BLOCK_ACTIVITY_START_IN_TASK
    }

    /** @property mode "none", "locked" or "pinned"; [pinned]: this app is the one pinned. */
    data class State(val pinned: Boolean, val mode: String, val features: Int, val appMayExit: Boolean)

    private val api get() = IOhrKiosk.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_KIOSK))

    /** Pins [activity]'s task, keeping [features] (default: the power menu and status bar info). */
    @WorkerThread
    @JvmOverloads
    fun start(activity: Activity, features: Int = Feature.GLOBAL_ACTIONS or Feature.SYSTEM_INFO) =
        agentCall { api.start(activity.taskId, features) }

    /** Ends kiosk mode, if the fleet lets kiosk apps leave (NotAllowed otherwise). */
    @WorkerThread
    fun stop() = agentCall { api.stop() }

    @WorkerThread
    fun state(): State = JSONObject(agentCall { api.state() }).let {
        State(it.optBoolean("pinned"), it.optString("mode"), it.optInt("features"), it.optBoolean("appMayExit"))
    }

    private val watcher = Watcher({ api.watch(it) }, { api.unwatch(it) }) { c -> if (c.what == "mode") JSONObject(c.json).optString("mode") else null }

    /** The device-wide lock task mode as it changes ("none", "locked", "pinned"); [state] says whose. */
    val modeChanges: Flow<String> get() = watcher.flow
    fun listenMode(executor: Executor, listener: OhrListener<String>): Closeable = watcher.listen(executor, listener)

    private fun needsVersion2() {
        if (sdk.session.agentInterface < 2) throw OhrException.Unsupported("This OhrTech Agent is too old for it", needsAgent = 1061)
    }

    /**
     * Opens the Agent's exit PIN keypad over the app, for staff to leave kiosk mode for a while (from
     * the app's own staff menu, say). The app must be the one pinned. NotAllowed when the fleet doesn't
     * let the app offer it; Unsupported when no exit PIN is set.
     */
    @WorkerThread
    fun requestExit() {
        needsVersion2()
        agentCall { api.requestExit() }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var beating: Runnable? = null

    /**
     * Tells the Agent's kiosk watchdog that the app's main thread is alive: the library beats from the
     * main thread, a few times per [timeoutMs]. If the beats stop for longer (the main thread hangs),
     * the Agent stops the app and starts it again. Only while the app is pinned; call it again after
     * [start]. [stopHeartbeat] ends it.
     */
    @JvmOverloads
    fun heartbeat(timeoutMs: Long = 10_000) {
        needsVersion2()
        require(timeoutMs in 2_000..600_000) { "timeoutMs is 2 s to 10 minutes" }
        stopHeartbeat()
        val api = api
        val every = timeoutMs / 4
        val beat = object : Runnable {
            override fun run() {
                if (beating !== this) return
                runCatching { api.heartbeat(timeoutMs.toInt()) }
                main.postDelayed(this, every)
            }
        }
        beating = beat
        main.post(beat)
    }

    /** Stops the heartbeat (and the Agent's watch on it). */
    fun stopHeartbeat() {
        val b = beating ?: return
        beating = null
        main.removeCallbacks(b)
        runCatching { api.heartbeat(0) }
    }
}

/** Battery (always), reboots, screen timeout and brightness (with the fleet's "power" grant). */
class OhrPower internal constructor(private val sdk: OhrDeviceSdk) {
    /** @property plugged "ac", "usb", "wireless", "dock" or null; [health] "good", "overheat", ... */
    data class Battery(val level: Int?, val charging: Boolean, val plugged: String?, val health: String,
                       val temperatureC: Double, val screenOn: Boolean)

    /** A reboot schedule: [time] "HH:mm", [days] 1-7 (Monday = 1). */
    data class Schedule(val time: String, val days: Set<Int>, val reason: String)

    private val api get() = IOhrPower.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_POWER))

    private fun parse(json: String) = JSONObject(json).let {
        Battery(if (it.isNull("level")) null else it.optInt("level"), it.optBoolean("charging"), it.str("plugged"),
            it.optString("health"), it.optDouble("temperatureC"), it.optBoolean("screenOn"))
    }

    @WorkerThread
    fun battery(): Battery = parse(agentCall { api.battery() })
    /** Reboots in a few seconds; the hub records [reason]. */
    @WorkerThread
    fun reboot(reason: String) = agentCall { api.reboot(reason) }

    /** Reboots on a schedule (the device's one; the last app to set it wins); null stops it. */
    @WorkerThread
    fun scheduleReboot(schedule: Schedule?) = agentCall {
        api.scheduleReboot(schedule?.let {
            JSONObject().put("time", it.time).put("days", JSONArray(it.days.sorted())).put("reason", it.reason).toString()
        })
    }

    @WorkerThread
    fun rebootSchedule(): Schedule? = agentCall { api.rebootSchedule() }?.let {
        val o = JSONObject(it)
        val d = o.optJSONArray("days") ?: JSONArray()
        Schedule(o.optString("time"), (0 until d.length()).map { i -> d.getInt(i) }.toSet(), o.optString("reason"))
    }

    /** One of 15, 30, 60, 120, 300, 600, 1800 seconds. FleetLocked if the fleet locks it. */
    @WorkerThread
    fun setScreenTimeout(seconds: Int) = agentCall { api.setScreenTimeout(seconds) }
    /** 0-100 (turns adaptive brightness off). FleetLocked if the fleet locks it. */
    @WorkerThread
    fun setBrightness(percent: Int) = agentCall { api.setBrightness(percent) }

    private val watcher = Watcher({ api.watch(it) }, { api.unwatch(it) }) { c -> if (c.what == "battery") parse(c.json) else null }
    val batteryChanges: Flow<Battery> get() = watcher.flow
    fun listenBattery(executor: Executor, listener: OhrListener<Battery>): Closeable = watcher.listen(executor, listener)
}

/**
 * The device's settings, by the OhrTech settings catalogue's keys (docs.ohrtech.net): read always,
 * change with the fleet's "settings" grant. A key the fleet locks refuses (FleetLocked); an app's
 * change to an unlocked key holds until the fleet changes that setting. The fleet's instruments of
 * control (lock screen, debugging, factory reset, SDK access, ...) are never changeable here.
 */
class OhrSettings internal constructor(private val sdk: OhrDeviceSdk) {
    /** @property value the setting's value (Boolean, Int, String, JSONArray, JSONObject or null); [mode] "unmanaged", "set" or "locked". */
    data class Setting(val key: String, val value: Any?, val mode: String) {
        val locked: Boolean get() = mode == "locked"
    }

    private val api get() = IOhrSettings.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_SETTINGS))

    /** The keys apps may read and change on this device. */
    @WorkerThread
    fun keys(): List<String> = JSONArray(agentCall { api.keys() }).let { a -> (0 until a.length()).map { a.getString(it) } }

    @WorkerThread
    fun get(key: String): Setting = JSONObject(agentCall { api.get(key) }).let {
        Setting(key, it.opt("value").takeUnless { v -> v == JSONObject.NULL }, it.optString("mode"))
    }

    /** [value]: Boolean, a number, a String, a JSONArray or JSONObject, or null. */
    @WorkerThread
    fun set(key: String, value: Any?) = agentCall { api.set(key, json(value)) }

    internal fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        else -> JSONObject.wrap(value).toString()
    }

    private val watcher = Watcher({ api.watch(it) }, { api.unwatch(it) }) { c ->
        if (c.what != "key") null else JSONObject(c.json).let { Setting(it.optString("key"), it.opt("value").takeUnless { v -> v == JSONObject.NULL }, it.optString("mode")) }
    }
    val changes: Flow<Setting> get() = watcher.flow
    fun listen(executor: Executor, listener: OhrListener<Setting>): Closeable = watcher.listen(executor, listener)
}
