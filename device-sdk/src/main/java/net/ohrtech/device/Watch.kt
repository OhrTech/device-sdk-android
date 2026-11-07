package net.ohrtech.device

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import net.ohrtech.device.aidl.IOhrListener
import java.io.Closeable
import java.util.concurrent.Executor

/** A change an area reports: [what] changed, [json] its new state (that area's getter's JSON). */
internal data class Change(val what: String, val json: String)

/** For Java: told of changes on [Executor]; close the returned [Closeable] to stop. */
fun interface OhrListener<T> {
    fun onChange(value: T)
}

/**
 * Subscribes to an area's changes. [watch] and [unwatch] are the area's own binder calls;
 * [map] turns a change into what the app sees (null skips it).
 */
internal class Watcher<T>(
    private val watch: (IOhrListener) -> Unit,
    private val unwatch: (IOhrListener) -> Unit,
    private val map: (Change) -> T?,
) {
    val flow: Flow<T> = callbackFlow {
        val l = object : IOhrListener.Stub() {
            override fun onChange(what: String, json: String) {
                map(Change(what, json))?.let { trySend(it) }
            }
        }
        agentCall { watch(l) }
        awaitClose { runCatching { unwatch(l) } }
    }

    fun listen(executor: Executor, listener: OhrListener<T>): Closeable {
        val l = object : IOhrListener.Stub() {
            override fun onChange(what: String, json: String) {
                val v = map(Change(what, json)) ?: return
                executor.execute { listener.onChange(v) }
            }
        }
        agentCall { watch(l) }
        return Closeable { runCatching { unwatch(l) } }
    }
}
