package net.ohrtech.device

import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
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
 * Subscribes to an area's changes. [watch] and [unwatch] are the area's own binder calls; [map]
 * turns a change into what the app sees (null skips it); [current], for areas with a state, is
 * the state now: a subscriber gets it after the Agent came back, for what it missed meanwhile.
 * Each collection or listener is its own subscription, ended by cancelling or closing it.
 */
internal class Watcher<T : Any>(
    private val area: String,
    private val watch: (IBinder, IOhrListener) -> Unit,
    private val unwatch: (IBinder, IOhrListener) -> Unit,
    private val current: (() -> T)? = null,
    private val map: (Change) -> T?,
) {
    private fun subscription(deliver: (T) -> Unit): Subscription {
        val listener = object : IOhrListener.Stub() {
            override fun onChange(what: String, json: String) {
                map(Change(what, json))?.let(deliver)
            }
        }
        return Subscription(area, listener, watch, unwatch, current?.let { get -> { deliver(get()) } })
    }

    // The binder calls (and a first bind) on the IO dispatcher, wherever the flow is collected.
    val flow: Flow<T> = callbackFlow {
        val sub = subscription { trySend(it) }
        Agent.subscribe(sub)
        awaitClose { Agent.unsubscribe(sub) }
    }.flowOn(Dispatchers.IO)

    fun listen(executor: Executor, listener: OhrListener<T>): Closeable {
        val sub = subscription { v -> executor.execute { listener.onChange(v) } }
        Agent.subscribe(sub)
        return Closeable { Agent.unsubscribe(sub) }
    }
}
