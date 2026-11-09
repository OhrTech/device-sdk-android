package net.ohrtech.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The process's one connection to the OhrTech Agent. Bound on first use and kept for the life of
 * the process; when the Agent stops (a crash, its own update) it binds again and registers every
 * live subscription again. Binding callbacks run on the library's own thread, so a first call
 * may wait for the Agent from any thread, the main one included.
 */
internal object Agent {
    private const val TAG = "OhrDeviceSdk"
    private const val TIMEOUT_MS = 10_000L

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext ?: context
    }

    val context: Context
        get() = appContext ?: throw OhrException.Failed(
            "OhrDeviceSdk.init(context) first: this app removed the library's App Startup initializer")

    private val lock = Object()
    private var bound = false
    private var service: IOhrDeviceSdk? = null
    private var noAgent = false
    private var everConnected = false
    private val areas = ConcurrentHashMap<String, IBinder>()

    @Volatile
    private var access: OhrAccess.Info? = null

    val state = MutableStateFlow(OhrDeviceSdk.Connection.NOT_CONNECTED)

    /** Binding callbacks, the reconnect work and its retries, in order. */
    private val worker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "OhrDeviceSdk").apply { isDaemon = true } }

    private val subscriptions: MutableSet<Subscription> = ConcurrentHashMap.newKeySet()

    private fun bindIntent() = Intent(IOhrDeviceSdk.ACTION_BIND).setPackage(OhrDeviceSdk.AGENT_PACKAGE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val reconnect: Boolean
            synchronized(lock) {
                service = IOhrDeviceSdk.Stub.asInterface(binder)
                areas.clear()
                noAgent = false
                reconnect = everConnected
                everConnected = true
                lock.notifyAll()
            }
            // Usable now; afterConnect says UNAVAILABLE instead if the hub didn't install this app.
            state.value = OhrDeviceSdk.Connection.CONNECTED
            afterConnect(reconnect)
        }

        // The Agent's process went; Android starts it again and calls onServiceConnected.
        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) {
                service = null
                areas.clear()
            }
            state.value = OhrDeviceSdk.Connection.RECONNECTING
            Log.i(TAG, "The OhrTech Agent stopped; waiting for it")
        }

        // The Agent was updated or removed: this binding is over, so bind again.
        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) {
                service = null
                areas.clear()
                runCatching { context.unbindService(this) }
                bound = false
            }
            state.value = OhrDeviceSdk.Connection.RECONNECTING
            Log.i(TAG, "The OhrTech Agent was replaced; binding again")
            rebind(1)
        }

        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                noAgent = true
                lock.notifyAll()
            }
            state.value = OhrDeviceSdk.Connection.UNAVAILABLE
        }
    }

    /** Binds (under [lock]); false when there is no Agent to bind. */
    private fun bindLocked(): Boolean {
        if (bound) return true
        bound = try {
            context.bindService(bindIntent(), Context.BIND_AUTO_CREATE, worker, connection)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) runCatching { context.unbindService(connection) }
        return bound
    }

    /** Binds again after the Agent was replaced: at once, then backing off to every 30 s. */
    private fun rebind(attempt: Int) {
        worker.schedule({
            val ok = synchronized(lock) { bindLocked() }
            if (!ok) rebind(attempt + 1)
        }, minOf(30_000L, 500L shl minOf(attempt, 6)), TimeUnit.MILLISECONDS)
    }

    private fun afterConnect(reconnect: Boolean) {
        val s = synchronized(lock) { service } ?: return
        try {
            access = OhrAccess.Info.of(agentCall { s.hello(OhrDeviceSdk.VERSION) })
        } catch (e: OhrException.NotManaged) {
            access = null
            state.value = OhrDeviceSdk.Connection.UNAVAILABLE
            return
        } catch (e: OhrException) {
            Log.w(TAG, "The OhrTech Agent's hello failed: ${e.message}")
            return
        }
        if (reconnect) {
            Log.i(TAG, "The OhrTech Agent is back; ${subscriptions.size} subscription(s) again")
            for (sub in subscriptions) {
                runCatching { sub.register() }.onFailure { Log.w(TAG, "Couldn't watch ${sub.area} again: $it") }
                runCatching { sub.resync?.invoke() }
            }
        }
    }

    /** The Agent's service, binding or waiting for it as needed (up to [timeoutMs]). */
    fun service(timeoutMs: Long = TIMEOUT_MS): IOhrDeviceSdk {
        synchronized(lock) {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            if (!bindLocked()) {
                state.value = OhrDeviceSdk.Connection.UNAVAILABLE
                throw OhrException.Unsupported("This device has no OhrTech Agent with the Device SDK")
            }
            if (state.value == OhrDeviceSdk.Connection.NOT_CONNECTED) state.value = OhrDeviceSdk.Connection.CONNECTING
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                if (noAgent) throw OhrException.Unsupported("This device's OhrTech Agent has no Device SDK")
                service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) throw OhrException.Failed("The OhrTech Agent did not answer")
                lock.wait(left)
            }
        }
    }

    /** An area's binder (cached until the Agent stops). */
    fun area(name: String): IBinder {
        areas[name]?.takeIf { it.isBinderAlive }?.let { return it }
        val s = service()
        return agentCall { s.area(name) }.also { areas[name] = it }
    }

    /** Who the Agent takes this app for and what it may do: asked now. */
    fun accessNow(): OhrAccess.Info =
        OhrAccess.Info.of(agentCall { service().hello(OhrDeviceSdk.VERSION) }).also { access = it }

    /** The Agent's Device SDK interface version (known since the last connect). */
    fun agentInterface(): Int = (access ?: accessNow()).agentInterface

    /** Throws [OhrException.Unsupported] when the Agent is older than interface 2 (Agent 1061). */
    fun needsVersion2() {
        if (agentInterface() < 2) throw OhrException.Unsupported("This OhrTech Agent is too old for it", needsAgent = 1061)
    }

    fun subscribe(sub: Subscription) {
        subscriptions += sub
        try {
            sub.register()
        } catch (e: Exception) {
            subscriptions -= sub
            throw e
        }
    }

    /** Ends [sub]; a stopped Agent has forgotten it already, so this never waits for one. */
    fun unsubscribe(sub: Subscription) {
        if (!subscriptions.remove(sub)) return
        val alive = synchronized(lock) { service?.takeIf { it.asBinder().isBinderAlive } } ?: return
        runCatching {
            val b = areas[sub.area]?.takeIf { it.isBinderAlive } ?: agentCall { alive.area(sub.area) }
            sub.unregister(b)
        }
    }
}

/**
 * A live watch of one area: [watch] and [unwatch] are that area's binder calls with [listener];
 * [resync] tells the subscriber the current state after the Agent came back (null: nothing to tell).
 */
internal class Subscription(
    val area: String,
    private val listener: IOhrListener,
    private val watch: (IBinder, IOhrListener) -> Unit,
    private val unwatch: (IBinder, IOhrListener) -> Unit,
    val resync: (() -> Unit)?,
) {
    fun register() = agentCall { watch(Agent.area(area), listener) }
    fun unregister(areaBinder: IBinder) = agentCall { unwatch(areaBinder, listener) }
}
