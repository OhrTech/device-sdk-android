package net.ohrtech.device.push

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.WorkerThread
import net.ohrtech.device.OhrDeviceSdk
import net.ohrtech.device.OhrException
import net.ohrtech.device.aidl.IOhrPush
import net.ohrtech.device.aidl.IOhrPushReceiver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Receives the app's push messages. Subclass it and declare it in the manifest, protected by the
 * permission only the OhrTech Agent holds:
 * ```
 * <service android:name=".Push" android:exported="true"
 *     android:permission="net.ohrtech.device.permission.RECEIVE_PUSH">
 *     <intent-filter><action android:name="net.ohrtech.device.action.PUSH" /></intent-filter>
 * </service>
 * ```
 * The library checks each message before [onMessage]: the suite is the token's, one of the sender
 * keys ([OhrPush.register], [senderKeys]) signed it, it opens with the app's TEE key, it hasn't
 * expired (2 minutes' grace for the device clock), and its id wasn't seen before. Otherwise the
 * message is dropped and the hub is told why ([onDropped]).
 *
 * Callbacks run one at a time on a worker thread of the service. The Agent unbinds the service
 * shortly after delivering, so start a foreground service or WorkManager for long work.
 */
abstract class OhrPushService : Service() {

    /** A verified, opened message. */
    @WorkerThread
    abstract fun onMessage(message: OhrPushMessage)

    /** The token changed or ended (null: register again). Send a new token to your server. */
    @WorkerThread
    open fun onTokenChanged(token: PushToken?) {}

    /** A message was dropped: [reason] "signature", "expired", "duplicate", "malformed", ... */
    @WorkerThread
    open fun onDropped(pushId: String, reason: String) {}

    /** The keys messages must be signed with; by default those given to [OhrPush.register] or [OhrPush.setSenderKeys]. */
    @WorkerThread
    open fun senderKeys(): List<SenderKey> = PushStore(this).senderKeys()

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "OhrPushService") }
    private var sdk: OhrDeviceSdk? = null

    private fun sdk(): OhrDeviceSdk = sdk?.takeIf { it.isConnected } ?: OhrDeviceSdk.connectBlocking(this).also {
        sdk?.close()
        sdk = it
    }

    /** Only the Agent may call (the manifest's permission says so too). */
    private fun fromAgent(): Boolean =
        packageManager.getPackagesForUid(Binder.getCallingUid())?.contains(OhrDeviceSdk.AGENT_PACKAGE) == true

    private val receiver = object : IOhrPushReceiver.Stub() {
        override fun onMessage(messageId: String, token: String, envelope: ByteArray, priority: Int, expiresAtMs: Long) {
            if (!fromAgent()) return
            worker.execute { deliver(messageId, token, envelope, priority) }
        }

        override fun onTokenChanged(tokenJson: String?) {
            if (!fromAgent()) return
            worker.execute {
                val t = tokenJson?.let { runCatching { PushToken.parse(it) }.getOrNull() }
                runCatching { onTokenChanged(t) }.onFailure { Log.e(TAG, "onTokenChanged threw", it) }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == IOhrPush.ACTION_PUSH) receiver else null

    override fun onDestroy() {
        worker.execute {
            sdk?.close()
            sdk = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun deliver(pushId: String, token: String, envelope: ByteArray, priority: Int) {
        val push = try {
            sdk().push
        } catch (e: OhrException) {
            // Delivered as far as the hub knows; without the Agent it can't be opened.
            Log.e(TAG, "Push $pushId: no OhrTech Agent to open it with", e)
            return
        }
        val outcome = try {
            PushReceipt.check(
                envelope, token, push.token(), senderKeys(),
                open = { enc, ct -> push.open(enc, ct) },
                nowMs = System.currentTimeMillis(),
                markSeen = { id, until, now -> PushStore(this).markSeen(id, until, now) },
            )
        } catch (e: OhrException) {
            PushReceipt.Outcome.Dropped("agent")
        }
        when (outcome) {
            is PushReceipt.Outcome.Accepted -> {
                val p = outcome.plaintext
                val m = OhrPushMessage(p.id, p.data, p.expSeconds * 1000, priority == 1, pushId)
                runCatching { onMessage(m) }.onFailure { Log.e(TAG, "onMessage threw for $pushId", it) }
            }
            is PushReceipt.Outcome.Dropped -> {
                Log.w(TAG, "Push $pushId dropped: ${outcome.reason}")
                runCatching { push.reportDropped(pushId, outcome.reason) }
                runCatching { onDropped(pushId, outcome.reason) }.onFailure { Log.e(TAG, "onDropped threw", it) }
            }
        }
    }

    private companion object {
        const val TAG = "OhrPushService"
    }
}

/** What the library accepts (docs: OhrTech push §2), apart from Android. */
internal object PushReceipt {
    const val GRACE_MS = 2 * 60 * 1000L

    sealed class Outcome {
        class Accepted(val plaintext: PushPlaintext) : Outcome()
        class Dropped(val reason: String) : Outcome()
    }

    fun check(
        envelope: ByteArray,
        token: String,
        registered: PushToken?,
        senderKeys: List<SenderKey>,
        open: (ByteArray, ByteArray) -> ByteArray,
        nowMs: Long,
        markSeen: (String, Long, Long) -> Boolean,
    ): Outcome {
        val env = try {
            PushEnvelope.parse(envelope)
        } catch (e: CborException) {
            return Outcome.Dropped("malformed")
        }
        if (registered == null || registered.token != token) return Outcome.Dropped("token")
        if (env.suite != registered.suite) return Outcome.Dropped("suite")
        if (senderKeys.none { env.signedBy(token, it) }) return Outcome.Dropped("signature")
        val bytes = try {
            open(env.enc, env.ct)
        } catch (e: OhrException.Failed) {
            return Outcome.Dropped("does_not_open")
        }
        val p = try {
            PushPlaintext.parse(bytes)
        } catch (e: CborException) {
            return Outcome.Dropped("malformed")
        } finally {
            bytes.fill(0)
        }
        val expMs = p.expSeconds * 1000
        if (nowMs > expMs + GRACE_MS) return Outcome.Dropped("expired")
        if (!markSeen(p.id, expMs + GRACE_MS, nowMs)) return Outcome.Dropped("duplicate")
        return Outcome.Accepted(p)
    }
}
