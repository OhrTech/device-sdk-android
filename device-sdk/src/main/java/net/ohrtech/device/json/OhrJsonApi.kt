package net.ohrtech.device.json

import android.app.Activity
import android.os.Bundle
import net.ohrtech.device.OhrAccess
import net.ohrtech.device.OhrAttestation
import net.ohrtech.device.OhrCertificates
import net.ohrtech.device.OhrConfig
import net.ohrtech.device.OhrDevice
import net.ohrtech.device.OhrDeviceSdk
import net.ohrtech.device.OhrDiagnostics
import net.ohrtech.device.OhrException
import net.ohrtech.device.OhrKiosk
import net.ohrtech.device.OhrListener
import net.ohrtech.device.OhrNetwork
import net.ohrtech.device.OhrPower
import net.ohrtech.device.OhrSettings
import net.ohrtech.device.OhrUpdates
import net.ohrtech.device.OhrUsb
import net.ohrtech.device.OhrWifi
import net.ohrtech.device.keys.OhrKeys
import net.ohrtech.device.push.OhrPush
import net.ohrtech.device.push.PushToken
import net.ohrtech.device.push.SenderKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.Executor

/**
 * The Device SDK as JSON: each method by name, taking its arguments as a JSON object and returning JSON text, and
 * each kind of change as JSON text. For SDKs over a text bridge (React Native's module, a web app's page): they call
 * here and get exactly what the Kotlin library does, with the same grants and the same Agent checks.
 *
 * Each method belongs to an area (the names `sdk.access` grants and the Agent reports: "wifi", "kiosk", ...), or to
 * none for the connection and [OhrAccess] (always allowed). A host can refuse a call by its area before it is made.
 *
 * Calls block: run them on a worker thread. Errors are [OhrException]s; [error] gives the JSON a bridge sends.
 *
 * @param activity the host's current activity, for kiosk mode.
 * @param extra the host's own methods, added to the table (or replacing one of the same name).
 */
class OhrJsonApi @JvmOverloads constructor(
    private val activity: () -> Activity?,
    extra: Map<String, Method> = emptyMap(),
) {
    /** A method: its [area] (null: none) and what it does with its arguments. */
    class Method(val area: String?, val run: (JSONObject) -> Any?)

    private val table: Map<String, Method> = standard() + extra

    /** The method names. */
    val methods: Set<String> get() = table.keys

    /** The area [method] belongs to; null for one of none, or an unknown method. */
    fun areaOf(method: String): String? = table[method]?.area

    /** Runs [method] with [argsJson] (a JSON object, or blank); the result as JSON text. */
    fun call(method: String, argsJson: String): String {
        val f = table[method] ?: throw OhrException.Unsupported("this version of the SDK has no method $method")
        val args = try {
            if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        } catch (e: org.json.JSONException) {
            throw OhrException.InvalidConfig("args", "not a JSON object")
        }
        val result = try {
            f.run(args)
        } catch (e: org.json.JSONException) {
            throw OhrException.InvalidConfig("args", e.message ?: "missing or wrong argument")
        }
        return jsonText(result)
    }

    /** The kinds of change [watch] takes. */
    val kinds: Set<String> get() = watches.keys

    /** The area a kind of change belongs to (null: none). */
    fun areaOfKind(kind: String): String? = watchAreas[kind]

    /**
     * Calls [send] with each change of [kind] as JSON text, on [executor], until the result is closed. The connection
     * starts with how it stands; the others with the next change.
     */
    fun watch(kind: String, executor: Executor, send: (String) -> Unit): Closeable {
        val subscribe = watches[kind] ?: throw OhrException.Unsupported("this version of the SDK has no change $kind")
        return subscribe(executor, send)
    }

    private val watchAreas = mapOf(
        "connection" to null, "updates" to "updates", "wifiState" to "wifi", "wifiNetworks" to "wifi",
        "networkState" to "network", "kioskMode" to "kiosk", "battery" to "power", "settings" to "settings",
        "config" to "config", "certificates" to "certificates", "usb" to "usb",
    )

    private val watches: Map<String, (Executor, (String) -> Unit) -> Closeable> = mapOf(
        "connection" to { e, send ->
            val now = OhrDeviceSdk.connection.value.name
            e.execute { send(JSONObject.quote(now)) }
            OhrDeviceSdk.listenConnection(e) { send(JSONObject.quote(it.name)) }
        },
        "updates" to { e, send -> OhrUpdates.listen(e) { send(it.json().toString()) } },
        "wifiState" to { e, send -> OhrWifi.listenState(e) { send(it.json().toString()) } },
        "wifiNetworks" to { e, send -> OhrWifi.listenNetworks(e) { n -> send(JSONArray(n.map { it.json }).toString()) } },
        "networkState" to { e, send -> OhrNetwork.listen(e) { send(it.json().toString()) } },
        "kioskMode" to { e, send -> OhrKiosk.listenMode(e) { send(JSONObject.quote(it)) } },
        "battery" to { e, send -> OhrPower.listenBattery(e) { send(it.json().toString()) } },
        "settings" to { e, send -> OhrSettings.listen(e) { send(it.json().toString()) } },
        "config" to { e, send -> OhrConfig.listen(e) { send(it.json().toString()) } },
        "certificates" to { e, send -> OhrCertificates.listen(e, OhrListener { send("true") }) },
        "usb" to { e, send -> OhrUsb.listen(e) { c -> send(JSONObject().put("attached", c.attached).put("device", c.device.json()).toString()) } },
    )

    private fun standard(): Map<String, Method> {
        fun m(area: String?, run: (JSONObject) -> Any?) = Method(area, run)
        return mapOf(
            // The connection and access
            "connect" to m(null) { a -> OhrDeviceSdk.connectBlocking(a.optLong("timeoutMs", 10_000)); null },
            "isAvailable" to m(null) { _ -> OhrDeviceSdk.isAvailable() },
            "connectionState" to m(null) { _ -> OhrDeviceSdk.connection.value.name },
            "accessInfo" to m(null) { _ -> OhrAccess.info().json() },

            // Device and updates
            "deviceInfo" to m("device") { _ -> OhrDevice.info().json() },
            "updatesAvailable" to m("updates") { _ -> OhrUpdates.available()?.json() },
            "updatesInstall" to m("updates") { a -> OhrUpdates.install(a.optBoolean("whenIdle")); null },

            // Wi-Fi (configs and networks as the Agent's JSON)
            "wifiState" to m("wifi") { _ -> OhrWifi.state().json() },
            "wifiNetworks" to m("wifi") { _ -> JSONArray(OhrWifi.networks().map { it.json }) },
            "wifiScan" to m("wifi") { _ -> OhrWifi.scan(); null },
            "wifiSaved" to m("wifi") { _ -> JSONArray(OhrWifi.saved()) },
            "wifiSave" to m("wifi") { a -> OhrWifi.save(a.getJSONObject("config")) },
            "wifiConnect" to m("wifi") { a -> OhrWifi.connect(a.getString("keyOrIdentity")); null },
            "wifiConnectConfig" to m("wifi") { a -> OhrWifi.connect(a.getJSONObject("config")) },
            "wifiDisconnect" to m("wifi") { _ -> OhrWifi.disconnect(); null },
            "wifiForget" to m("wifi") { a -> OhrWifi.forget(a.getString("identity")); null },
            "wifiSetEnabled" to m("wifi") { a -> OhrWifi.setEnabled(a.getBoolean("on")); null },
            "wifiConnectQr" to m("wifi") { a -> OhrWifi.connectQr(a.getString("text")) },

            // Network
            "networkState" to m("network") { _ -> OhrNetwork.state().json() },
            "networkSetMobileData" to m("network") { a -> OhrNetwork.setMobileData(a.getBoolean("on")); null },
            "networkSetAirplaneMode" to m("network") { a -> OhrNetwork.setAirplaneMode(a.getBoolean("on")); null },
            "networkSetDataRoaming" to m("network") { a -> OhrNetwork.setDataRoaming(a.getBoolean("on")); null },

            // Kiosk
            "kioskStart" to m("kiosk") { a ->
                val act = activity() ?: throw OhrException.Failed("kiosk mode needs the app's activity, and there is none now")
                OhrKiosk.start(act, a.optInt("features", OhrKiosk.Feature.GLOBAL_ACTIONS or OhrKiosk.Feature.SYSTEM_INFO))
                null
            },
            "kioskStop" to m("kiosk") { _ -> OhrKiosk.stop(); null },
            "kioskState" to m("kiosk") { _ ->
                OhrKiosk.state().let { JSONObject().put("pinned", it.pinned).put("mode", it.mode).put("features", it.features).put("appMayExit", it.appMayExit) }
            },
            "kioskRequestExit" to m("kiosk") { _ -> OhrKiosk.requestExit(); null },
            "kioskStopHeartbeat" to m("kiosk") { _ -> OhrKiosk.stopHeartbeat(); null },
            "kioskBeat" to m("kiosk") { a -> OhrKiosk.beat(a.getLong("timeoutMs")); null },

            // Power
            "powerBattery" to m("power") { _ -> OhrPower.battery().json() },
            "powerReboot" to m("power") { a -> OhrPower.reboot(a.getString("reason")); null },
            "powerScheduleReboot" to m("power") { a ->
                OhrPower.scheduleReboot(a.optJSONObject("schedule")?.let { s ->
                    val d = s.getJSONArray("days")
                    OhrPower.Schedule(s.getString("time"), (0 until d.length()).map { d.getInt(it) }.toSet(), s.optString("reason"))
                })
                null
            },
            "powerRebootSchedule" to m("power") { _ ->
                OhrPower.rebootSchedule()?.let { JSONObject().put("time", it.time).put("days", JSONArray(it.days.sorted())).put("reason", it.reason) }
            },
            "powerSetScreenTimeout" to m("power") { a -> OhrPower.setScreenTimeout(a.getInt("seconds")); null },
            "powerSetBrightness" to m("power") { a -> OhrPower.setBrightness(a.getInt("percent")); null },

            // Settings
            "settingsKeys" to m("settings") { _ -> JSONArray(OhrSettings.keys()) },
            "settingsGet" to m("settings") { a -> OhrSettings.get(a.getString("key")).json() },
            "settingsSet" to m("settings") { a -> OhrSettings.set(a.getString("key"), a.opt("value").takeUnless { it == JSONObject.NULL }); null },

            // Managed configuration
            "configValues" to m("config") { _ -> OhrConfig.values().json() },
            "configString" to m("config") { a -> OhrConfig.string(a.getString("key"), a.optString("fallback").takeIf { a.has("fallback") && !a.isNull("fallback") }) },
            "configInt" to m("config") { a -> OhrConfig.int(a.getString("key"), a.optInt("fallback")) },
            "configBool" to m("config") { a -> OhrConfig.bool(a.getString("key"), a.optBoolean("fallback")) },
            "configStrings" to m("config") { a -> JSONArray(OhrConfig.strings(a.getString("key"))) },
            "configIsSet" to m("config") { a -> OhrConfig.isSet(a.getString("key")) },

            // Attestation
            "attestationAttest" to m("attestation") { a ->
                val suite = when (a.optString("suite", "ec")) {
                    "ec" -> OhrAttestation.Suite.EC
                    "ec-mldsa65" -> OhrAttestation.Suite.EC_MLDSA65
                    else -> throw OhrException.InvalidConfig("suite", "ec or ec-mldsa65")
                }
                OhrAttestation.attest(a.bytes("challenge"), suite).toJson()
            },
            "attestationToken" to m("attestation") { a ->
                OhrAttestation.token(a.getString("audience"), a.optLong("ttlSeconds", 300), a.optString("nonce").takeIf { a.has("nonce") && !a.isNull("nonce") })
            },
            "attestationSuite" to m("attestation") { _ -> OhrAttestation.suite?.let { it.name.lowercase().replace('_', '-') } },

            // Certificates
            "certificatesCanRequest" to m("certificates") { _ -> OhrCertificates.canRequest() },
            "certificatesRequest" to m("certificates") { a -> OhrCertificates.request(a.getString("alias"), a.optLong("timeoutMs", 60_000)).json() },
            "certificatesRenewIfDue" to m("certificates") { a -> OhrCertificates.renewIfDue(a.getString("alias"))?.json() },
            "certificatesList" to m("certificates") { _ -> JSONArray(OhrCertificates.list().map { it.json() }) },

            // USB
            "usbDevices" to m("usb") { _ -> JSONArray(OhrUsb.devices().map { it.json() }) },

            // Diagnostics
            "diagnosticsEvent" to m("diagnostics") { a -> OhrDiagnostics.event(a.getString("name"), a.optJSONObject("data") ?: JSONObject()); null },
            "diagnosticsLog" to m("diagnostics") { a -> OhrDiagnostics.log(a.getInt("priority"), a.getString("tag"), a.getString("message")); null },

            // Keys
            "keysIsAvailable" to m("keys") { _ -> OhrKeys.isAvailable() },
            "keysGenerate" to m("keys") { a -> b64(OhrKeys.generate(a.getString("alias"), OhrKeys.Algorithm.named(a.getString("algorithm")))) },
            "keysList" to m("keys") { _ ->
                JSONArray(OhrKeys.list().map { JSONObject().put("alias", it.alias).put("algorithm", it.algorithm.standardName).put("createdAtMs", it.createdAtMs) })
            },
            "keysPublicKey" to m("keys") { a -> OhrKeys.publicKey(a.getString("alias"))?.let(::b64) },
            "keysDelete" to m("keys") { a -> OhrKeys.delete(a.getString("alias")); null },
            "keysDecapsulate" to m("keys") { a -> b64(OhrKeys.decapsulate(a.getString("alias"), a.bytes("ciphertext"))) },
            "keysEncapsulate" to m("keys") { a ->
                OhrKeys.encapsulate(OhrKeys.Algorithm.named(a.getString("algorithm")), a.bytes("publicKey"))
                    .let { JSONObject().put("ciphertext", b64(it.ciphertext)).put("sharedSecret", b64(it.sharedSecret)) }
            },
            "keysHpkeOpen" to m("keys") { a ->
                b64(OhrKeys.hpkeOpen(a.getString("alias"), a.optInt("kdf", 1), a.optInt("aead", 1), a.bytes("enc"), a.bytesOr("info"), a.bytesOr("aad"), a.bytes("ciphertext")))
            },
            "keysAttest" to m("keys") { a ->
                OhrKeys.attest(a.getString("alias"), a.bytes("challenge")).let {
                    JSONObject().put("algorithm", it.algorithm.standardName).put("publicKey", b64(it.publicKey)).put("owner", it.owner)
                        .put("coseSign1", b64(it.coseSign1)).put("diceChain", b64(it.diceChain))
                }
            },

            // Push (the host receives messages itself)
            "pushRegister" to m("push") { a ->
                val suite = OhrPush.Suite.named(a.optString("suite", "x25519")) ?: throw OhrException.InvalidConfig("suite", "x25519 or xwing-mldsa65")
                OhrPush.register(senderKeys(a.getJSONArray("senderKeys")), suite).json()
            },
            "pushToken" to m("push") { _ -> OhrPush.token()?.json() },
            "pushUnregister" to m("push") { _ -> OhrPush.unregister(); null },
            "pushSetSenderKeys" to m("push") { a -> OhrPush.setSenderKeys(senderKeys(a.getJSONArray("keys"))); null },
            "pushSenderKeys" to m("push") { _ ->
                JSONArray(OhrPush.senderKeys().map { k -> JSONObject().put("ed25519", b64(k.ed25519)).apply { k.mlDsa65?.let { put("mlDsa65", b64(it)) } } })
            },
        )
    }

    private fun senderKeys(a: JSONArray) = (0 until a.length()).map { i ->
        val k = a.getJSONObject(i)
        SenderKey(unb64(k.getString("ed25519")), k.optString("mlDsa65").takeIf { k.has("mlDsa65") && !k.isNull("mlDsa65") }?.let(::unb64))
    }

    companion object {
        /**
         * [t] as the JSON a bridge sends: `{"code", "message", "details"}`, the code one of [OhrException]'s kinds
         * (NotManaged, NotAllowed, FleetLocked, Unsupported, InvalidConfig, Refused, Failed) and the details its fields.
         */
        @JvmStatic
        fun error(t: Throwable): JSONObject {
            val (code, details) = when (t) {
                is OhrException.NotManaged -> "NotManaged" to JSONObject()
                is OhrException.NotAllowed -> "NotAllowed" to JSONObject().put("area", t.area)
                is OhrException.FleetLocked -> "FleetLocked" to JSONObject().put("key", t.key)
                is OhrException.Unsupported -> "Unsupported" to JSONObject().put("reason", t.reason).put("needsAgent", t.needsAgent ?: JSONObject.NULL)
                is OhrException.InvalidConfig -> "InvalidConfig" to JSONObject().put("field", t.field).put("reason", t.reason)
                is OhrException.Refused -> "Refused" to JSONObject().put("reason", t.reason)
                is OhrException.Failed -> "Failed" to JSONObject().put("reason", t.reason)
                else -> "Failed" to JSONObject().put("reason", t.message ?: t.toString())
            }
            return JSONObject().put("code", code).put("message", t.message ?: t.toString()).put("details", details)
        }

        /** A value as JSON text: null, a string, a number, a boolean, an array or an object. */
        @JvmStatic
        fun jsonText(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            else -> JSONObject.wrap(value).toString()
        }
    }
}

internal fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
internal fun unb64(s: String): ByteArray = try {
    Base64.getDecoder().decode(s)
} catch (e: IllegalArgumentException) {
    throw OhrException.InvalidConfig("base64", "not base64")
}

private fun JSONObject.bytes(name: String): ByteArray = unb64(getString(name))
private fun JSONObject.bytesOr(name: String): ByteArray = if (has(name) && !isNull(name)) unb64(getString(name)) else ByteArray(0)

internal fun OhrAccess.Info.json() = JSONObject().put("app", app).put("packageName", packageName).put("appVersion", appVersion ?: JSONObject.NULL)
    .put("areas", JSONArray(areas.sorted())).put("agentVersion", agentVersion).put("agentInterface", agentInterface)

internal fun OhrDevice.Info.json(): JSONObject {
    fun named(n: OhrDevice.Named?) = n?.let { JSONObject().put("id", it.id).put("name", it.name) } ?: JSONObject.NULL
    return JSONObject().put("deviceId", deviceId ?: JSONObject.NULL).put("serial", serial ?: JSONObject.NULL).put("fleet", named(fleet))
        .put("org", named(org)).put("osRelease", osRelease).put("osBuild", osBuild).put("securityPatch", securityPatch)
        .put("osIncremental", osIncremental).put("agentVersion", agentVersion).put("online", online)
}

internal fun OhrUpdates.Update.json() = JSONObject().put("versionCode", versionCode).put("versionName", versionName ?: JSONObject.NULL)
    .put("notes", notes ?: JSONObject.NULL).put("downloaded", downloaded)

internal fun OhrWifi.State.json() = JSONObject().put("enabled", enabled).put("state", state).put("connected", connected ?: JSONObject.NULL)

internal fun OhrNetwork.State.json() = JSONObject()
    .put("active", active?.let { JSONObject().put("transport", it.transport).put("validated", it.validated).put("captive", it.captive).put("metered", it.metered).put("vpn", it.vpn) } ?: JSONObject.NULL)
    .put("mobileData", mobileData).put("airplaneMode", airplaneMode).put("dataRoaming", dataRoaming).put("ethernetConnected", ethernetConnected)

internal fun OhrPower.Battery.json() = JSONObject().put("level", level ?: JSONObject.NULL).put("charging", charging)
    .put("plugged", plugged ?: JSONObject.NULL).put("health", health).put("temperatureC", temperatureC).put("screenOn", screenOn)

internal fun OhrSettings.Setting.json() = JSONObject().put("key", key).put("value", value ?: JSONObject.NULL).put("mode", mode)

internal fun OhrCertificates.Certificate.json() = JSONObject().put("alias", alias).put("imported", imported)
    .put("paths", JSONArray(paths.map { p -> JSONArray(p.map { b64(it.encoded) }) }))

internal fun OhrUsb.Device.json() = JSONObject().put("accessory", accessory).put("deviceName", deviceName ?: JSONObject.NULL)
    .put("vendorId", vendorId).put("productId", productId).put("manufacturer", manufacturer ?: JSONObject.NULL)
    .put("product", product ?: JSONObject.NULL).put("model", model ?: JSONObject.NULL).put("version", version ?: JSONObject.NULL)
    .put("serial", serial ?: JSONObject.NULL).put("name", name ?: JSONObject.NULL).put("hasPermission", hasPermission)

internal fun PushToken.json() = JSONObject().put("token", token).put("suite", suite.wireName).put("publicKey", publicKeyBase64)

/** Managed configuration as JSON: bundles as objects, bundle arrays as lists. */
@Suppress("DEPRECATION")
internal fun Bundle.json(): JSONObject = JSONObject().also { o ->
    for (k in keySet().sorted()) {
        o.put(k, when (val v = get(k)) {
            is Bundle -> v.json()
            is Array<*> -> JSONArray(v.map { if (it is Bundle) it.json() else it })
            null -> JSONObject.NULL
            else -> v
        })
    }
}
