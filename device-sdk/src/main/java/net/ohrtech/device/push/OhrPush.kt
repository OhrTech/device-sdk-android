package net.ohrtech.device.push

import android.content.Context
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ohrtech.device.OhrDeviceSdk
import net.ohrtech.device.agentCall
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrPush
import org.json.JSONObject
import java.util.Base64

/**
 * OhrTech push: messages from the app's own server, end to end. The server seals each message to
 * the device's push key (in the TEE) and signs it with its sender key; the OhrTech hub only carries
 * it. Every app the organisation installed may use it.
 *
 * 1. [register] once (and when [OhrPushService.onTokenChanged] says the token ended), with your
 *    server's sender public keys. Send the [PushToken] (token and public key) to your server.
 * 2. Your server seals with an OhrTech server SDK and sends through the Management API
 *    (`POST /v1/push`).
 * 3. Your [OhrPushService] gets the message, verified, opened, in date and not seen before.
 */
class OhrPush internal constructor(private val sdk: OhrDeviceSdk) {

    /** [X25519]: small (about 190 bytes over the data), the default. [XWING_MLDSA65]: post-quantum (about 4.6 KB over). */
    enum class Suite(internal val id: Int, val wireName: String, internal val encSize: Int) {
        X25519(IOhrPush.SUITE_X25519, "x25519", 32),
        XWING_MLDSA65(IOhrPush.SUITE_XWING_MLDSA65, "xwing-mldsa65", 1120);

        companion object {
            @JvmStatic
            fun named(wireName: String) = entries.firstOrNull { it.wireName == wireName }
        }
    }

    private val api get() = IOhrPush.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_PUSH))
    private val store get() = PushStore(sdk.appContext)

    /**
     * Makes the app's push key for [suite] in the TEE, has the hub verify it and issue a token, and
     * keeps [senderKeys] (your server's public keys; messages signed by none of them are dropped).
     * Registering again gives a new token and ends the old one. Waits for the hub (up to 30 s).
     */
    @WorkerThread
    @JvmOverloads
    fun register(senderKeys: List<SenderKey>, suite: Suite = Suite.X25519): PushToken {
        require(senderKeys.isNotEmpty()) { "at least one sender key" }
        require(suite != Suite.XWING_MLDSA65 || senderKeys.any { it.mlDsa65 != null }) {
            "the xwing-mldsa65 suite needs a sender key with ML-DSA-65"
        }
        setSenderKeys(senderKeys)
        return PushToken.parse(agentCall { api.register(suite.id) })
    }

    suspend fun registerAsync(senderKeys: List<SenderKey>, suite: Suite = Suite.X25519): PushToken =
        withContext(Dispatchers.IO) { register(senderKeys, suite) }

    /** The current token, or null if the app isn't registered (or its token ended). */
    @WorkerThread
    fun token(): PushToken? = agentCall { api.token() }?.let { PushToken.parse(it) }

    /** Ends the token and deletes the push key. */
    @WorkerThread
    fun unregister() = agentCall { api.unregister() }

    /** Replaces the sender keys messages are checked against (e.g. when your server rotates them). */
    fun setSenderKeys(keys: List<SenderKey>) = store.setSenderKeys(keys)

    fun senderKeys(): List<SenderKey> = store.senderKeys()

    @WorkerThread
    internal fun open(enc: ByteArray, ct: ByteArray): ByteArray = agentCall { api.open(enc, ct) }

    @WorkerThread
    internal fun reportDropped(messageId: String, reason: String) = agentCall { api.reportDropped(messageId, reason) }
}

/**
 * A sender's public keys, as your server's OhrTech SDK gives them: [ed25519] (32 bytes) and, for
 * the xwing-mldsa65 suite, [mlDsa65] (1,952 bytes).
 */
class SenderKey @JvmOverloads constructor(ed25519: ByteArray, mlDsa65: ByteArray? = null) {
    val ed25519: ByteArray = ed25519.clone()
    val mlDsa65: ByteArray? = mlDsa65?.clone()

    init {
        require(ed25519.size == 32) { "an Ed25519 public key is 32 bytes" }
        require(mlDsa65 == null || mlDsa65.size == 1952) { "an ML-DSA-65 public key is 1,952 bytes" }
    }

    override fun equals(other: Any?) =
        other is SenderKey && other.ed25519.contentEquals(ed25519) && other.mlDsa65.contentEquals(mlDsa65)

    override fun hashCode() = ed25519.contentHashCode()

    companion object {
        /** From base64 (standard alphabet, as the Management API and server SDKs print them). */
        @JvmStatic
        @JvmOverloads
        fun fromBase64(ed25519: String, mlDsa65: String? = null) =
            SenderKey(Base64.getDecoder().decode(ed25519), mlDsa65?.let { Base64.getDecoder().decode(it) })
    }
}

/** What your server needs to push to this app on this device: [token] ("psh_…") and [publicKey]. */
class PushToken(val token: String, val suite: OhrPush.Suite, publicKey: ByteArray) {
    private val key = publicKey.clone()
    val publicKey: ByteArray get() = key.clone()
    val publicKeyBase64: String get() = Base64.getEncoder().encodeToString(key)

    override fun toString() = "PushToken($token, ${suite.wireName})"

    internal companion object {
        fun parse(json: String): PushToken {
            val o = JSONObject(json)
            val suite = OhrPush.Suite.named(o.getString("suite"))
                ?: throw IllegalStateException("unknown push suite ${o.getString("suite")}")
            return PushToken(o.getString("token"), suite, Base64.getDecoder().decode(o.getString("publicKey")))
        }
    }
}

/**
 * A message your server sent, verified and opened. [id]: your server's id for it; [data] its
 * bytes (at most 4,096); [highPriority]: sent as high priority (the app may start a foreground
 * service now); [pushId]: the hub's id ("pmsg_…").
 */
class OhrPushMessage internal constructor(
    val id: String,
    val data: ByteArray,
    val expiresAtMs: Long,
    val highPriority: Boolean,
    val pushId: String,
)

/** The app's sender keys and the message ids it has seen, in its own private storage. */
internal class PushStore(context: Context) {
    private val prefs = context.getSharedPreferences("net.ohrtech.device.push", Context.MODE_PRIVATE)

    fun setSenderKeys(keys: List<SenderKey>) {
        val b = Base64.getEncoder()
        prefs.edit().putStringSet(SENDERS, keys.map { k ->
            b.encodeToString(k.ed25519) + (k.mlDsa65?.let { ":" + b.encodeToString(it) } ?: "")
        }.toSet()).apply()
    }

    fun senderKeys(): List<SenderKey> = prefs.getStringSet(SENDERS, emptySet()).orEmpty().mapNotNull { s ->
        runCatching { s.split(':').let { SenderKey.fromBase64(it[0], it.getOrNull(1)) } }.getOrNull()
    }

    /** Records [id] until [keepUntilMs]; false if it was seen already. */
    @Synchronized
    fun markSeen(id: String, keepUntilMs: Long, nowMs: Long): Boolean {
        val seen = runCatching { JSONObject(prefs.getString(SEEN, "{}")!!) }.getOrDefault(JSONObject())
        if (seen.has(id) && seen.optLong(id) >= nowMs) return false
        val ids = seen.keys().asSequence().toList()
        ids.forEach { if (seen.optLong(it) < nowMs) seen.remove(it) }
        // A bound, for senders with far expiries: the soonest to expire go first.
        if (seen.length() >= MAX_SEEN) {
            seen.keys().asSequence().toList().sortedBy { seen.optLong(it) }.take(seen.length() - MAX_SEEN + 1).forEach { seen.remove(it) }
        }
        seen.put(id, keepUntilMs)
        prefs.edit().putString(SEEN, seen.toString()).commit()
        return true
    }

    private companion object {
        const val SENDERS = "senderKeys"
        const val SEEN = "seen"
        const val MAX_SEEN = 2000
    }
}
