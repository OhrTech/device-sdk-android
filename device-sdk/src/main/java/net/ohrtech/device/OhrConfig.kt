package net.ohrtech.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.os.Bundle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * The app's managed configuration: the values its fleet (or one device's overrides) sets in the
 * OhrTech portal or API for the settings the app declares in its own `res/xml/app_restrictions.xml`
 * (manifest meta-data `android.content.APP_RESTRICTIONS`, Android's standard).
 *
 * ```
 * val config = OhrConfig(context)            // or sdk.config
 * val url = config.string("server_url")      // the managed value, else the app's declared default
 * config.changes.collect { reload() }        // the fleet changed it
 * ```
 * It is Android's application restrictions, set by the OhrTech Agent, so `RestrictionsManager`
 * works too. Needs no connection to the Agent, and no fleet grant: it is the app's own configuration.
 */
class OhrConfig(context: Context) {
    private val app = context.applicationContext
    private val restrictions = app.getSystemService(RestrictionsManager::class.java)

    /** Every managed value (keys the fleet doesn't set are absent: see [defaults]). */
    fun values(): Bundle = restrictions.applicationRestrictions ?: Bundle()

    /** The app's declared settings, with their defaults, from its app_restrictions.xml. */
    fun defaults(): List<RestrictionEntry> = restrictions.getManifestRestrictions(app.packageName) ?: emptyList()

    /** Whether the fleet sets [key]. */
    fun isSet(key: String): Boolean = values().containsKey(key)

    private fun declared(key: String) = defaults().firstOrNull { it.key == key }

    /** A string, choice or hidden setting: the managed value, else the declared default, else [fallback]. */
    @JvmOverloads
    fun string(key: String, fallback: String? = null): String? =
        values().getString(key) ?: declared(key)?.selectedString ?: fallback

    /** From Java: `getInt(key)` (`int` is a Java keyword). */
    @JvmOverloads
    @JvmName("getInt")
    fun int(key: String, fallback: Int = 0): Int {
        val v = values()
        return if (v.containsKey(key)) v.getInt(key) else declared(key)?.takeIf { it.type == RestrictionEntry.TYPE_INTEGER }?.intValue ?: fallback
    }

    @JvmOverloads
    fun bool(key: String, fallback: Boolean = false): Boolean {
        val v = values()
        return if (v.containsKey(key)) v.getBoolean(key) else declared(key)?.takeIf { it.type == RestrictionEntry.TYPE_BOOLEAN }?.selectedState ?: fallback
    }

    /** A multi-select setting. */
    fun strings(key: String): List<String> =
        values().getStringArray(key)?.toList() ?: declared(key)?.allSelectedStrings?.toList() ?: emptyList()

    /** A bundle setting (a group of settings), or null if the fleet doesn't set it. */
    fun bundle(key: String): Bundle? = values().getBundle(key)

    /** A bundle-array setting (a list of groups). */
    @Suppress("DEPRECATION")
    fun bundles(key: String): List<Bundle> =
        values().getParcelableArray(key)?.filterIsInstance<Bundle>() ?: emptyList()

    /** Every change of the managed configuration, as the new values. */
    val changes: Flow<Bundle>
        get() = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    trySend(values())
                }
            }
            app.registerReceiver(receiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            awaitClose { app.unregisterReceiver(receiver) }
        }

    /** For Java: told of changes on [executor]; close the returned [Closeable] to stop. */
    fun listen(executor: Executor, listener: OhrListener<Bundle>): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val v = values()
                executor.execute { listener.onChange(v) }
            }
        }
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        return Closeable { runCatching { app.unregisterReceiver(receiver) } }
    }
}
