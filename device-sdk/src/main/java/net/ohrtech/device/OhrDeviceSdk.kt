package net.ohrtech.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.WorkerThread
import androidx.startup.Initializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import net.ohrtech.device.aidl.IOhrDeviceSdk
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * The OhrTech Device SDK: what an app on an OhrTech device can ask the OhrTech Agent for. Each area
 * is an object to call directly: [OhrDevice], [OhrUpdates], [OhrWifi], [OhrNetwork], [OhrKiosk],
 * [OhrPower], [OhrSettings], [OhrConfig], [OhrAttestation], [OhrCertificates], [OhrUsb],
 * [OhrDiagnostics], [net.ohrtech.device.keys.OhrKeys], [net.ohrtech.device.push.OhrPush];
 * [OhrAccess] says what the fleet lets the app do.
 *
 * ```
 * val info = OhrDevice.infoAsync()
 * if (OhrAccess.mayUse("kiosk")) OhrKiosk.start(activity)
 * OhrWifi.stateChanges.collect { … }
 * ```
 * There is nothing to connect or close. The library binds the Agent on the first call and keeps
 * the binding for the life of the process; when the Agent stops (a crash, its own update), it binds
 * again and every subscription goes on. Calls are binder calls into the Agent: make them off the
 * main thread (the suspend versions do that themselves).
 */
object OhrDeviceSdk {
    const val AGENT_PACKAGE = "net.ohrtech.agent"

    /** The Device SDK interface version this library speaks. */
    const val VERSION = IOhrDeviceSdk.VERSION

    /** The library's connection to the Agent. */
    enum class Connection {
        /** Nothing has asked the Agent anything yet. */
        NOT_CONNECTED,

        /** Binding the Agent for the first time. */
        CONNECTING,
        CONNECTED,

        /** The Agent stopped (a crash, its own update); calls wait for it to come back. */
        RECONNECTING,

        /** No OhrTech Agent with the Device SDK, or the hub didn't install this app. */
        UNAVAILABLE,
    }

    /**
     * The application context. App Startup calls it at app start; call it yourself only if your
     * app removes the library's initializer (`net.ohrtech.device.OhrDeviceSdkInitializer`).
     */
    @JvmStatic
    fun init(context: Context) = Agent.init(context)

    /** Whether this device has an OhrTech Agent with the Device SDK. */
    @JvmStatic
    fun isAvailable(): Boolean =
        Agent.context.packageManager.resolveService(
            Intent(IOhrDeviceSdk.ACTION_BIND).setPackage(AGENT_PACKAGE), PackageManager.ResolveInfoFlags.of(0)) != null

    /** The connection as it changes. */
    @JvmStatic
    val connection: StateFlow<Connection> = Agent.state.asStateFlow()

    /** For Java: told of each change of [connection] on [executor]; close the returned [Closeable] to stop. */
    @JvmStatic
    fun listenConnection(executor: Executor, listener: OhrListener<Connection>): Closeable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { connection.drop(1).collect { c -> executor.execute { listener.onChange(c) } } }
        return Closeable { scope.cancel() }
    }

    /**
     * Connects now, waiting up to [timeoutMs], instead of on the first call: to know at app start
     * whether the Device SDK is there. Throws [OhrException]: NotManaged if the hub didn't install
     * this app, Unsupported if the device has no OhrTech Agent, Failed if it doesn't answer.
     */
    @JvmStatic
    @JvmOverloads
    @WorkerThread
    fun connectBlocking(timeoutMs: Long = 10_000) {
        Agent.service(timeoutMs)
        Agent.accessNow()
    }

    /** [connectBlocking], suspending. */
    suspend fun connect(timeoutMs: Long = 10_000) = withContext(Dispatchers.IO) { connectBlocking(timeoutMs) }
}

/** Gives the library the application context at app start (AndroidX App Startup). */
class OhrDeviceSdkInitializer : Initializer<Unit> {
    override fun create(context: Context) = OhrDeviceSdk.init(context)
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
