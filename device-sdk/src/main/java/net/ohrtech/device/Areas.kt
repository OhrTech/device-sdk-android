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
object OhrDevice {
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

    private val api get() = IOhrDevice.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_DEVICE))

    @JvmStatic
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
object OhrUpdates {
    data class Update(val versionCode: Long, val versionName: String?, val notes: String?, val downloaded: Boolean)

    /** What [changes] gives when nothing is offered any more. */
    @JvmField
    val NONE = Update(0, null, null, false)

    private val api get() = IOhrUpdates.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_UPDATES))

    private fun parse(json: String?): Update? = json?.takeIf { it != "null" }?.let {
        val o = JSONObject(it)
        Update(o.optLong("versionCode"), o.str("versionName"), o.str("notes"), o.optBoolean("downloaded"))
    }

    /** The offered update, or null. */
    @JvmStatic
    @WorkerThread
    fun available(): Update? = parse(agentCall { api.available() })

    /**
     * Installs the offered update: now, or [whenIdle] the next time the screen is off. The app is
     * replaced, so it stops; Android starts it again if it was on screen.
     */
    @JvmStatic
    @WorkerThread
    @JvmOverloads
    fun install(whenIdle: Boolean = false) =
        agentCall { api.install(if (whenIdle) IOhrUpdates.WHEN_IDLE else IOhrUpdates.WHEN_NOW) }

    private val watcher = Watcher(IOhrDeviceSdk.AREA_UPDATES,
        { b, l -> IOhrUpdates.Stub.asInterface(b).watch(l) }, { b, l -> IOhrUpdates.Stub.asInterface(b).unwatch(l) },
        current = { available() ?: NONE }) { c -> if (c.what == "available") parse(c.json) ?: NONE else null }

    /** The offer as it changes ([NONE] when withdrawn). */
    @JvmStatic
    val changes: Flow<Update> get() = watcher.flow

    @JvmStatic
    fun listen(executor: Executor, listener: OhrListener<Update>): Closeable = watcher.listen(executor, listener)
}

/** Mobile data, airplane mode, roaming: read always, change with the fleet's "network" grant. */
object OhrNetwork {
    /** @property transport "wifi", "cellular", "ethernet", "vpn" or "other"; null [active]: no network. */
    data class Active(val transport: String, val validated: Boolean, val captive: Boolean, val metered: Boolean, val vpn: Boolean)
    data class State(val active: Active?, val mobileData: Boolean, val airplaneMode: Boolean, val dataRoaming: Boolean,
                     val ethernetConnected: Boolean)

    private val api get() = IOhrNetwork.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_NETWORK))

    private fun parse(json: String): State {
        val o = JSONObject(json)
        val a = o.optJSONObject("active")?.let {
            Active(it.optString("transport"), it.optBoolean("validated"), it.optBoolean("captive"), it.optBoolean("metered"), it.optBoolean("vpn"))
        }
        return State(a, o.optBoolean("mobileData"), o.optBoolean("airplaneMode"), o.optBoolean("dataRoaming"),
            o.optJSONObject("ethernet")?.optBoolean("connected") ?: false)
    }

    @JvmStatic
    @WorkerThread
    fun state(): State = parse(agentCall { api.state() })

    @JvmStatic
    @WorkerThread
    fun setMobileData(on: Boolean) = agentCall { api.setMobileData(on) }

    @JvmStatic
    @WorkerThread
    fun setAirplaneMode(on: Boolean) = agentCall { api.setAirplaneMode(on) }

    /** FleetLocked when the fleet locks network.data_roaming. */
    @JvmStatic
    @WorkerThread
    fun setDataRoaming(on: Boolean) = agentCall { api.setDataRoaming(on) }

    private val watcher = Watcher(IOhrDeviceSdk.AREA_NETWORK,
        { b, l -> IOhrNetwork.Stub.asInterface(b).watch(l) }, { b, l -> IOhrNetwork.Stub.asInterface(b).unwatch(l) },
        current = { state() }) { c -> if (c.what == "state") parse(c.json) else null }

    @JvmStatic
    val changes: Flow<State> get() = watcher.flow

    @JvmStatic
    fun listen(executor: Executor, listener: OhrListener<State>): Closeable = watcher.listen(executor, listener)
}

/**
 * Kiosk mode: pins the app with Android's lock task, as the system, so the app can't be left
 * (and can't unpin itself either). Needs the fleet's "kiosk" grant. [stop] works only if the
 * fleet lets kiosk apps leave; otherwise the hub or the exit PIN ends it.
 */
object OhrKiosk {
    /** What stays available in kiosk mode (combine with `or`). [NOTIFICATIONS] and [OVERVIEW] need [HOME] too (Android's rule). */
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

    private val api get() = IOhrKiosk.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_KIOSK))

    /**
     * Pins [activity]'s task, keeping [features] (default: the power menu and status bar info).
     * [Feature.NOTIFICATIONS] or [Feature.OVERVIEW] without [Feature.HOME] is [OhrException.InvalidConfig].
     */
    @JvmStatic
    @WorkerThread
    @JvmOverloads
    fun start(activity: Activity, features: Int = Feature.GLOBAL_ACTIONS or Feature.SYSTEM_INFO) {
        checkArg(features and (Feature.NOTIFICATIONS or Feature.OVERVIEW) == 0 || features and Feature.HOME != 0, "features") {
            "NOTIFICATIONS and OVERVIEW need HOME too"
        }
        Agent.init(activity)
        agentCall { api.start(activity.taskId, features) }
    }

    /** Ends kiosk mode, if the fleet lets kiosk apps leave (NotAllowed otherwise). */
    @JvmStatic
    @WorkerThread
    fun stop() = agentCall { api.stop() }

    @JvmStatic
    @WorkerThread
    fun state(): State = JSONObject(agentCall { api.state() }).let {
        State(it.optBoolean("pinned"), it.optString("mode"), it.optInt("features"), it.optBoolean("appMayExit"))
    }

    private val watcher = Watcher(IOhrDeviceSdk.AREA_KIOSK,
        { b, l -> IOhrKiosk.Stub.asInterface(b).watch(l) }, { b, l -> IOhrKiosk.Stub.asInterface(b).unwatch(l) },
        current = { state().mode }) { c -> if (c.what == "mode") JSONObject(c.json).optString("mode") else null }

    /** The device-wide lock task mode as it changes ("none", "locked", "pinned"); [state] says whose. */
    @JvmStatic
    val modeChanges: Flow<String> get() = watcher.flow

    @JvmStatic
    fun listenMode(executor: Executor, listener: OhrListener<String>): Closeable = watcher.listen(executor, listener)

    /**
     * Opens the Agent's exit PIN keypad over the app, for staff to leave kiosk mode for a while (from
     * the app's own staff menu, say). The app must be the one pinned. NotAllowed when the fleet doesn't
     * let the app offer it; Unsupported when no exit PIN is set.
     */
    @JvmStatic
    @WorkerThread
    fun requestExit() {
        Agent.needsVersion2()
        agentCall { api.requestExit() }
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var beating: Runnable? = null

    private fun checkTimeout(timeoutMs: Long) =
        checkArg(timeoutMs in 2_000..600_000, "timeoutMs") { "2 s to 10 minutes" }

    /** The Agent's heartbeat calls run here, never on the caller's thread (see [send]). */
    private val beats = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "OhrDeviceSdk-beat").apply { isDaemon = true } }
    private val beatWaiting = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * One beat, sent from the library's beat thread: a beat proves the thread that asked for it is alive, so that
     * thread must never wait for the Agent (which takes up to 10 s to come back when it restarts). While a beat
     * waits for the Agent, the next ones are skipped rather than queued.
     */
    private fun send(timeoutMs: Int) {
        if (!beatWaiting.compareAndSet(false, true)) return
        beats.execute {
            try {
                runCatching { api.heartbeat(timeoutMs) }
            } finally {
                beatWaiting.set(false)
            }
        }
    }

    /**
     * Tells the Agent's kiosk watchdog that the app's main thread is alive: the library beats from the
     * main thread, a few times per [timeoutMs]. If the beats stop for longer (the main thread hangs),
     * the Agent stops the app and starts it again. Only while the app is pinned; call it again after
     * [start]. [stopHeartbeat] ends it. One heartbeat per app: calling it again replaces it.
     *
     * With Agent 1062+, the fleet's response timeout for the app (portal or Management API), when set,
     * replaces [timeoutMs], and hangs aren't counted while the screen is off (the app gets a full timeout
     * again when it comes back on).
     */
    @JvmStatic
    @JvmOverloads
    fun heartbeat(timeoutMs: Long = 10_000) {
        Agent.needsVersion2()
        checkTimeout(timeoutMs)
        stopHeartbeat()
        val every = timeoutMs / 4
        val beat = object : Runnable {
            override fun run() {
                if (beating !== this) return
                // Running here proves the main thread is alive; the call itself goes to the beat thread.
                send(timeoutMs.toInt())
                main.postDelayed(this, every)
            }
        }
        beating = beat
        main.post(beat)
    }

    /**
     * One beat, for an app whose UI runs on a thread of its own (Flutter's UI isolate, React Native's
     * JavaScript thread): call it from that thread a few times per [timeoutMs] instead of [heartbeat].
     * It never waits for the Agent: the beat is sent from the library's own thread. (Only the process's
     * very first call to the SDK may wait, while the library binds the Agent.)
     */
    @JvmStatic
    @JvmOverloads
    fun beat(timeoutMs: Long = 10_000) {
        Agent.needsVersion2()
        checkTimeout(timeoutMs)
        send(timeoutMs.toInt())
    }

    /** Stops the heartbeat (and the Agent's watch on it). Never waits: the stop goes after any beat already sent. */
    @JvmStatic
    fun stopHeartbeat() {
        val b = beating
        beating = null
        if (b != null) main.removeCallbacks(b)
        beats.execute { runCatching { api.heartbeat(0) } }
    }
}

/** Battery (always), reboots, screen timeout and brightness (with the fleet's "power" grant). */
object OhrPower {
    /** @property plugged "ac", "usb", "wireless", "dock" or null; [health] "good", "overheat", ... */
    data class Battery(val level: Int?, val charging: Boolean, val plugged: String?, val health: String,
                       val temperatureC: Double, val screenOn: Boolean)

    /** A reboot schedule: [time] "HH:mm", [days] 1-7 (Monday = 1). */
    data class Schedule(val time: String, val days: Set<Int>, val reason: String)

    private val api get() = IOhrPower.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_POWER))

    private fun parse(json: String) = JSONObject(json).let {
        Battery(if (it.isNull("level")) null else it.optInt("level"), it.optBoolean("charging"), it.str("plugged"),
            it.optString("health"), it.optDouble("temperatureC"), it.optBoolean("screenOn"))
    }

    @JvmStatic
    @WorkerThread
    fun battery(): Battery = parse(agentCall { api.battery() })

    /** Reboots in a few seconds; the hub records [reason]. */
    @JvmStatic
    @WorkerThread
    fun reboot(reason: String) = agentCall { api.reboot(reason) }

    /** Reboots on a schedule (the device's one; the last app to set it wins); null stops it. */
    @JvmStatic
    @WorkerThread
    fun scheduleReboot(schedule: Schedule?) = agentCall {
        api.scheduleReboot(schedule?.let {
            JSONObject().put("time", it.time).put("days", JSONArray(it.days.sorted())).put("reason", it.reason).toString()
        })
    }

    @JvmStatic
    @WorkerThread
    fun rebootSchedule(): Schedule? = agentCall { api.rebootSchedule() }?.let {
        val o = JSONObject(it)
        val d = o.optJSONArray("days") ?: JSONArray()
        Schedule(o.optString("time"), (0 until d.length()).map { i -> d.getInt(i) }.toSet(), o.optString("reason"))
    }

    /** Any whole number of seconds, 1 to 2147483 (Android's limit). FleetLocked if the fleet locks it. */
    @JvmStatic
    @WorkerThread
    fun setScreenTimeout(seconds: Int) = agentCall { api.setScreenTimeout(seconds) }

    /** 0-100 (turns adaptive brightness off). FleetLocked if the fleet locks it. */
    @JvmStatic
    @WorkerThread
    fun setBrightness(percent: Int) = agentCall { api.setBrightness(percent) }

    private val watcher = Watcher(IOhrDeviceSdk.AREA_POWER,
        { b, l -> IOhrPower.Stub.asInterface(b).watch(l) }, { b, l -> IOhrPower.Stub.asInterface(b).unwatch(l) },
        current = { battery() }) { c -> if (c.what == "battery") parse(c.json) else null }

    @JvmStatic
    val batteryChanges: Flow<Battery> get() = watcher.flow

    @JvmStatic
    fun listenBattery(executor: Executor, listener: OhrListener<Battery>): Closeable = watcher.listen(executor, listener)
}

/**
 * The device's settings, by the OhrTech settings catalogue's keys (docs.ohrtech.net): read always,
 * change with the fleet's "settings" grant. A key the fleet locks refuses (FleetLocked); an app's
 * change to an unlocked key holds until the fleet changes that setting. The fleet's instruments of
 * control (lock screen, debugging, factory reset, SDK access, ...) are never changeable here.
 */
object OhrSettings {
    /** @property value the setting's value (Boolean, Int, String, JSONArray, JSONObject or null); [mode] "unmanaged", "set" or "locked". */
    data class Setting(val key: String, val value: Any?, val mode: String) {
        val locked: Boolean get() = mode == "locked"
    }

    private val api get() = IOhrSettings.Stub.asInterface(Agent.area(IOhrDeviceSdk.AREA_SETTINGS))

    /** The keys apps may read and change on this device. */
    @JvmStatic
    @WorkerThread
    fun keys(): List<String> = JSONArray(agentCall { api.keys() }).let { a -> (0 until a.length()).map { a.getString(it) } }

    @JvmStatic
    @WorkerThread
    fun get(key: String): Setting = JSONObject(agentCall { api.get(key) }).let {
        Setting(key, it.opt("value").takeUnless { v -> v == JSONObject.NULL }, it.optString("mode"))
    }

    /** [value]: Boolean, a number, a String, a JSONArray or JSONObject, or null. */
    @JvmStatic
    @WorkerThread
    fun set(key: String, value: Any?) = agentCall { api.set(key, json(value)) }

    internal fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        else -> JSONObject.wrap(value).toString()
    }

    private val watcher = Watcher(IOhrDeviceSdk.AREA_SETTINGS,
        { b, l -> IOhrSettings.Stub.asInterface(b).watch(l) }, { b, l -> IOhrSettings.Stub.asInterface(b).unwatch(l) }) { c ->
        if (c.what != "key") null else JSONObject(c.json).let { Setting(it.optString("key"), it.opt("value").takeUnless { v -> v == JSONObject.NULL }, it.optString("mode")) }
    }

    /** Each setting as it changes (after the Agent restarted, read the ones you need again). */
    @JvmStatic
    val changes: Flow<Setting> get() = watcher.flow

    @JvmStatic
    fun listen(executor: Executor, listener: OhrListener<Setting>): Closeable = watcher.listen(executor, listener)
}
