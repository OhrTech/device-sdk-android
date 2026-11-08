package net.ohrtech.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.keys.OhrKeys
import net.ohrtech.device.push.OhrPush
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The OhrTech Device SDK: what an app on an OhrTech device can ask the OhrTech Agent for.
 *
 * ```
 * val sdk = OhrDeviceSdk.connect(context)        // Kotlin, suspends
 * val sdk = OhrDeviceSdk.connectBlocking(context) // Java, off the main thread
 * sdk.device.info().deviceId
 * ```
 * Calls are binder calls into the Agent: make them off the main thread (the suspend versions do
 * that themselves). What the app may change is up to its fleet ([session]'s areas); reading is
 * mostly always allowed. Close the SDK when done; it reconnects only by connecting again.
 */
class OhrDeviceSdk private constructor(
    private val context: Context,
    private val service: IOhrDeviceSdk,
    private val connection: ServiceConnection,
    /** Who the Agent takes this app for, and what the fleet lets it do. */
    val session: Session,
) : Closeable {

    /**
     * @property app the hub's id of this app; [packageName] this app's package; [appVersion] the
     * hub's id of the installed version (null when it isn't one the hub shipped);
     * [areas] what the app may use: always "device", "updates", "keys", "push", the reads
     * ("wifi.read", ...), and each area the fleet grants ("wifi", "kiosk", ...).
     */
    data class Session(
        val app: String,
        val packageName: String,
        val appVersion: String?,
        val areas: Set<String>,
        val agentVersion: Long,
        val agentInterface: Int,
    ) {
        /** Whether the fleet lets the app use (change) [area] now; reads of most areas are always allowed. */
        fun mayUse(area: String) = area in areas
    }

    private val areaBinders = ConcurrentHashMap<String, IBinder>()

    @Volatile
    private var closed = false

    internal fun area(name: String): IBinder {
        if (closed) throw OhrException.Failed("This OhrDeviceSdk is closed; connect again")
        areaBinders[name]?.takeIf { it.isBinderAlive }?.let { return it }
        return agentCall { service.area(name) }.also { areaBinders[name] = it }
    }

    internal val appContext: Context get() = context

    val device: OhrDevice by lazy { OhrDevice(this) }
    val updates: OhrUpdates by lazy { OhrUpdates(this) }
    val wifi: OhrWifi by lazy { OhrWifi(this) }
    val network: OhrNetwork by lazy { OhrNetwork(this) }
    val kiosk: OhrKiosk by lazy { OhrKiosk(this) }
    val power: OhrPower by lazy { OhrPower(this) }
    val settings: OhrSettings by lazy { OhrSettings(this) }
    val keys: OhrKeys by lazy { OhrKeys(this) }
    val push: OhrPush by lazy { OhrPush(this) }

    /** The app's managed configuration ([OhrConfig] works without a connection too). */
    val config: OhrConfig by lazy { OhrConfig(context) }

    /** The app's client certificates and trusted CAs (Agent 1061+). */
    val certificates: OhrCertificates by lazy { OhrCertificates(this, context) }

    /** The USB devices the fleet pre-approves for the app (Agent 1061+). */
    val usb: OhrUsb by lazy { OhrUsb(this) }

    /** The app's own events and log for the hub (Agent 1061+). */
    val diagnostics: OhrDiagnostics by lazy { OhrDiagnostics(this) }

    /** Device attestation for the app's server ([OhrAttestation] works without a connection too). */
    val attestation: OhrAttestation by lazy { OhrAttestation() }

    /** Whether the Agent is still there; if not, connect again. */
    val isConnected: Boolean get() = !closed && service.asBinder().isBinderAlive

    override fun close() {
        if (closed) return
        closed = true
        runCatching { context.unbindService(connection) }
    }

    companion object {
        const val AGENT_PACKAGE = "net.ohrtech.agent"

        /** The Device SDK interface version this library speaks. */
        const val VERSION = IOhrDeviceSdk.VERSION

        /** Whether this device has an OhrTech Agent with the Device SDK. */
        @JvmStatic
        fun isAvailable(context: Context): Boolean =
            context.packageManager.resolveService(bindIntent(), PackageManager.ResolveInfoFlags.of(0)) != null

        private fun bindIntent() = Intent(IOhrDeviceSdk.ACTION_BIND).setPackage(AGENT_PACKAGE)

        /** Connects to the Agent (suspends until it answers). Throws [OhrException]. */
        suspend fun connect(context: Context, timeoutMs: Long = 10_000): OhrDeviceSdk =
            withContext(Dispatchers.IO) { connectBlocking(context, timeoutMs) }

        /**
         * Connects to the Agent, waiting up to [timeoutMs]. Not on the main thread (the binding
         * is answered there). Throws [OhrException]: NotManaged if the hub didn't install this
         * app, Unsupported if the device has no OhrTech Agent, Failed if it doesn't answer.
         */
        @JvmStatic
        @JvmOverloads
        @WorkerThread
        fun connectBlocking(context: Context, timeoutMs: Long = 10_000): OhrDeviceSdk {
            check(Looper.myLooper() != Looper.getMainLooper()) { "OhrDeviceSdk.connectBlocking can't run on the main thread" }
            val app = context.applicationContext
            val bound = AtomicReference<IBinder?>()
            val latch = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    bound.set(binder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}

                override fun onNullBinding(name: ComponentName) = latch.countDown()
            }
            val ok = try {
                app.bindService(bindIntent(), conn, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                false
            }
            if (!ok) {
                runCatching { app.unbindService(conn) }
                throw OhrException.Unsupported("This device has no OhrTech Agent with the Device SDK")
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS) || bound.get() == null) {
                runCatching { app.unbindService(conn) }
                throw OhrException.Failed("The OhrTech Agent did not answer")
            }
            val service = IOhrDeviceSdk.Stub.asInterface(bound.get())
            try {
                val s = agentCall { service.hello(VERSION) }
                return OhrDeviceSdk(app, service, conn, Session(s.app, s.packageName, s.appVersion,
                    s.areas.toSet(), s.agentVersion, s.agentInterface))
            } catch (e: Exception) {
                runCatching { app.unbindService(conn) }
                throw e
            }
        }
    }
}
