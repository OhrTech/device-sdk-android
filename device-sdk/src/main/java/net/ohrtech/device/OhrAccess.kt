package net.ohrtech.device

import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ohrtech.device.aidl.SdkSession

/**
 * Who the Agent takes this app for, and what the fleet lets it do. Asked of the Agent on each call,
 * so a change of the fleet's grants shows at once.
 */
object OhrAccess {
    /**
     * @property app the hub's id of this app ("app_…"); [packageName] this app's package; [appVersion]
     * the hub's id of the installed version (null when it isn't one the hub shipped); [areas] what the
     * app may use: always "device", "updates", "keys", "push", the reads ("wifi.read", ...), and each
     * area the fleet grants ("wifi", "kiosk", ...); [agentVersion] the Agent's version code;
     * [agentInterface] its Device SDK interface version.
     */
    data class Info(
        val app: String,
        val packageName: String,
        val appVersion: String?,
        val areas: Set<String>,
        val agentVersion: Long,
        val agentInterface: Int,
    ) {
        /** Whether the fleet lets the app use (change) [area]; reads of most areas are always allowed. */
        fun mayUse(area: String) = area in areas

        internal companion object {
            fun of(s: SdkSession) = Info(PublicIds.app(s.app), s.packageName, s.appVersion, s.areas.toSet(), s.agentVersion, s.agentInterface)
        }
    }

    /** Asks the Agent now. NotManaged if the hub didn't install this app. */
    @JvmStatic
    @WorkerThread
    fun info(): Info = Agent.accessNow()

    suspend fun infoAsync(): Info = withContext(Dispatchers.IO) { info() }

    /** Whether the fleet lets the app use (change) [area] now. */
    @JvmStatic
    @WorkerThread
    fun mayUse(area: String): Boolean = info().mayUse(area)

    suspend fun mayUseAsync(area: String): Boolean = withContext(Dispatchers.IO) { mayUse(area) }
}
