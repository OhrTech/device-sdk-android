package net.ohrtech.device

import android.os.DeadObjectException
import android.os.RemoteException
import net.ohrtech.device.aidl.IOhrDeviceSdk

/**
 * Why the OhrTech Agent refused or couldn't do what the app asked. Every Device SDK call throws
 * one of these subclasses, never a raw binder exception.
 */
sealed class OhrException(message: String) : Exception(message) {
    /** This app is not one the organisation installed on this device through OhrTech. */
    class NotManaged : OhrException("This app is not managed by OhrTech on this device")

    /** The fleet doesn't let this app use [area]. */
    class NotAllowed(val area: String) : OhrException("The fleet does not allow this app to use $area")

    /** The fleet locks the setting or network [key]. */
    class FleetLocked(val key: String) : OhrException("The fleet locks $key")

    /**
     * This device, OS or Agent can't do it. [needsAgent]: the Agent version that can, when the
     * Agent is only too old.
     */
    class Unsupported(val reason: String, val needsAgent: Long? = null) : OhrException(reason)

    /** A value the app passed is invalid: [field], and why. */
    class InvalidConfig(val field: String, val reason: String) : OhrException("$field: $reason")

    /** The Agent tried and it failed, or the Agent can't be reached. */
    class Failed(val reason: String) : OhrException(reason)

    internal companion object {
        private const val PREFIX = "ohr:"

        /** The Agent's "ohr:<code>:<detail>", or null if [e] isn't one. */
        fun from(e: Throwable): OhrException? {
            val m = (e as? IllegalStateException)?.message ?: return null
            if (!m.startsWith(PREFIX)) return null
            val colon = m.indexOf(':', PREFIX.length)
            if (colon < 0) return null
            val detail = m.substring(colon + 1)
            return when (m.substring(PREFIX.length, colon).toIntOrNull()) {
                IOhrDeviceSdk.ERROR_NOT_MANAGED -> NotManaged()
                IOhrDeviceSdk.ERROR_NOT_ALLOWED -> NotAllowed(detail)
                IOhrDeviceSdk.ERROR_FLEET_LOCKED -> FleetLocked(detail)
                IOhrDeviceSdk.ERROR_UNSUPPORTED -> Unsupported(detail)
                IOhrDeviceSdk.ERROR_INVALID_CONFIG -> {
                    val nl = detail.indexOf('\n')
                    if (nl < 0) InvalidConfig(detail, "invalid") else InvalidConfig(detail.substring(0, nl), detail.substring(nl + 1))
                }
                else -> Failed(detail)
            }
        }
    }
}

/** Runs a binder call, turning the Agent's errors into [OhrException]. */
internal inline fun <T> agentCall(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalStateException) {
        throw OhrException.from(e) ?: e
    } catch (e: DeadObjectException) {
        throw OhrException.Failed("The OhrTech Agent stopped; connect again")
    } catch (e: RemoteException) {
        throw OhrException.Failed("The OhrTech Agent can't be reached: $e")
    }
