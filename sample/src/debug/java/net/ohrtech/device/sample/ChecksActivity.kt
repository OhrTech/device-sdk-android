package net.ohrtech.device.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ohrtech.device.OhrAccess
import net.ohrtech.device.OhrAttestation
import net.ohrtech.device.OhrCertificates
import net.ohrtech.device.OhrConfig
import net.ohrtech.device.OhrDevice
import net.ohrtech.device.OhrDeviceSdk
import net.ohrtech.device.OhrDiagnostics
import net.ohrtech.device.OhrKiosk
import net.ohrtech.device.OhrNetwork
import net.ohrtech.device.OhrPower
import net.ohrtech.device.OhrSettings
import net.ohrtech.device.OhrUsb
import net.ohrtech.device.OhrWifi
import net.ohrtech.device.OhrException
import net.ohrtech.device.keys.OhrHpke
import net.ohrtech.device.keys.OhrKem
import net.ohrtech.device.keys.OhrKeyGenParameterSpec
import net.ohrtech.device.keys.OhrKeys
import net.ohrtech.device.keys.OhrPrivateKey
import net.ohrtech.device.keys.OhrTechProvider
import net.ohrtech.device.push.OhrPush
import net.ohrtech.device.push.SenderKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64

/**
 * Device checks of the library against the real Agent and TEE (debug builds only):
 * `am start -n <pkg>/net.ohrtech.device.sample.ChecksActivity --es check keys|push [--es senderKey <b64>] [--es suite x25519|xwing-mldsa65] [--es skip name,name]`.
 * Logs "CHECK PASS|FAIL name" (tag OhrSdkCheck) and writes files/checks-<check>.json.
 */
class ChecksActivity : Activity() {
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val results = JSONArray()
    private val out = JSONObject()
    private var failed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setPadding(32, 64, 32, 32) }
        setContentView(text)
        val check = intent.getStringExtra("check") ?: "keys"
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (check == "main-thread") mainThread() else run {
                        OhrDeviceSdk.connectBlocking()
                        val access = OhrAccess.info()
                        out.put("session", JSONObject().put("app", access.app).put("package", access.packageName)
                            .put("agent", access.agentVersion).put("deviceId", OhrDevice.info().deviceId))
                        when (check) {
                            "keys" -> keys(access)
                            "push" -> push()
                            "unregister" -> step("unregister") { OhrPush.unregister(); check(OhrPush.token() == null) }
                            "config" -> config()
                            "attestation" -> attestation()
                            // Kiosk mode and its watchdog (the Agent starts the app again after these).
                            "kiosk-start" -> step("kiosk start") { OhrKiosk.start(this@ChecksActivity); OhrKiosk.state().toString() }
                            "kiosk-state" -> step("kiosk state") { OhrKiosk.state().toString() }
                            "kiosk-stop" -> step("kiosk stop") { OhrKiosk.stop(); OhrKiosk.state().toString() }
                            // --es config <base64 JSON network>: join it, follow the state for --ei watch seconds (40), forget it.
                            "wifi-join" -> {
                                val config = JSONObject(String(Base64.getDecoder().decode(intent.getStringExtra("config") ?: "")))
                                val ssid = config.optString("ssid")
                                var identity: String? = null
                                step("connect ${config.optString("security")}/${config.optJSONObject("eap")?.optString("method")}") {
                                    identity = OhrWifi.connect(config)
                                    identity!!
                                }
                                val seen = mutableListOf<String>()
                                step("join $ssid") {
                                    val until = System.currentTimeMillis() + intent.getIntExtra("watch", 40) * 1000L
                                    // The whole window: the SSID shows as the target before association, so seeing it
                                    // proves nothing; whether EAP succeeded is the access point's to say.
                                    var joined = false
                                    while (System.currentTimeMillis() < until) {
                                        val st = OhrWifi.state()
                                        val now = st.state + ":" + (st.connected?.optString("ssid") ?: "-")
                                        if (seen.lastOrNull() != now) seen += now
                                        joined = joined || st.connected?.optString("ssid") == ssid
                                        Thread.sleep(500)
                                    }
                                    out.put("states", JSONArray(seen))
                                    check(joined) { "never on $ssid: ${seen.joinToString(" > ")}" }
                                    seen.joinToString(" > ")
                                }
                                identity?.let { id -> step("forget") { OhrWifi.forget(id); id } }
                            }
                            "settings-values" -> {
                                step("timeout 0 refused") {
                                    (runCatching { OhrSettings.set("display.timeout", 0) }.exceptionOrNull() as? OhrException.InvalidConfig)?.reason
                                        ?: error("accepted 0")
                                }
                                step("timeout 45 accepted") {
                                    val before = OhrSettings.get("display.timeout").value
                                    OhrSettings.set("display.timeout", 45)
                                    val now = OhrSettings.get("display.timeout").value
                                    before?.let { OhrSettings.set("display.timeout", it) }
                                    check(now.toString() == "45") { "read back $now" }
                                    "45 (back to $before)"
                                }
                                step("not a boolean refused") {
                                    (runCatching { OhrSettings.set("sound.vibration", "yes") }.exceptionOrNull() as? OhrException.InvalidConfig)?.reason
                                        ?: error("accepted \"yes\"")
                                }
                                step("current timeout accepted") {
                                    val now = OhrSettings.get("display.timeout").value
                                    OhrSettings.set("display.timeout", now)
                                    "display.timeout $now"
                                }
                                step("only the allowed keys") { OhrSettings.keys().size.toString() + " keys" }
                            }
                            "settings-hidden" -> for (key in listOf("apps.config", "apps.certificates", "apps.crash_reports", "usb.devices", "sdk.access")) {
                                step("$key hidden") {
                                    check(key !in OhrSettings.keys()) { "listed" }
                                    val e = runCatching { OhrSettings.get(key) }.exceptionOrNull() ?: error("readable")
                                    e.javaClass.simpleName
                                }
                            }
                            "certs" -> {
                                step("issuing CA given") { check(OhrCertificates.canRequest()) { "the fleet gives no issuing CA" }; "yes" }
                                step("request a certificate") {
                                    val c = OhrCertificates.request("checks")
                                    out.put("chain", JSONArray(c.chain.map { Base64.getEncoder().encodeToString(it.encoded) }))
                                    "${c.paths.size} paths (${c.paths.joinToString { "${it.size}: ...${it.last().subjectX500Principal.name.substringAfter("CN=").substringBefore(',')}" }}), ${c.certificate.subjectX500Principal}"
                                }
                                step("list") { OhrCertificates.list().joinToString { "${it.alias}${if (it.imported) " (imported)" else ""}" } }
                                step("tls context") { OhrCertificates.sslContext("checks").protocol }
                                intent.getStringExtra("tlsUrl")?.let { url ->
                                    step("mutual TLS") {
                                        val c = java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
                                        c.sslSocketFactory = OhrCertificates.sslContext(intent.getStringExtra("tlsAlias") ?: "checks").socketFactory
                                        c.connectTimeout = 10_000
                                        c.readTimeout = 10_000
                                        val body = c.inputStream.bufferedReader().readText()
                                        "HTTP ${c.responseCode}, ${body.lines().firstOrNull { it.contains("Subject") || it.contains("CN=") }?.trim()?.take(120) ?: body.take(80)}"
                                    }
                                }
                            }
                            "usb" -> step("approved USB devices") {
                                val list = OhrUsb.devices()
                                out.put("usb", JSONArray(list.map { JSONObject().put("name", it.name).put("deviceName", it.deviceName).put("vendorId", it.vendorId).put("productId", it.productId).put("hasPermission", it.hasPermission) }))
                                "${list.size} attached"
                            }
                            "diag" -> {
                                step("events") {
                                    OhrDiagnostics.event("checks_started", mapOf("run" to (intent.getStringExtra("run") ?: "1")))
                                    OhrDiagnostics.event("printer_offline", mapOf("port" to 9100, "retries" to 3))
                                    "2 events"
                                }
                                step("log lines") {
                                    for (i in 1..20) OhrDiagnostics.i("Checks", "line $i of the checks' own log")
                                    OhrDiagnostics.e("Checks", "an error line for the bundle")
                                    android.util.Log.w("ChecksLogcat", "a logcat line for the bundle")
                                    "21 lines"
                                }
                                step("bad event refused") {
                                    (runCatching { OhrDiagnostics.event("Bad Name") }.exceptionOrNull() as? OhrException.InvalidConfig)?.field
                                        ?: error("accepted a bad name")
                                }
                            }
                            "kiosk-exit" -> step("request exit") { OhrKiosk.requestExit(); "the keypad is up" }
                            "kiosk-hang" -> step("heartbeat, then hang") {
                                // Beats every 750 ms, then the main thread stops for 30 s: the Agent stops the app after ~3 s.
                                OhrKiosk.heartbeat(3000)
                                Thread.sleep(2000)
                                runOnUiThread { Thread.sleep(30_000) }
                                Thread.sleep(30_000)
                                error("still alive 30 s later")
                            }
                            "crash" -> {
                                Log.i(TAG, "CHECK crashing on purpose")
                                runOnUiThread { throw IllegalStateException("checks: a crash on purpose") }
                                Thread.sleep(5000)
                            }
                            "hang" -> {
                                // No heartbeat: Android's ANR (on the next input) is what the watchdog sees.
                                Log.i(TAG, "CHECK hanging the main thread on purpose")
                                runOnUiThread { Thread.sleep(120_000) }
                                Thread.sleep(120_000)
                            }
                            "reconnect" -> reconnect()
                            "heartbeat-restart" -> heartbeatRestart()
                            "config-watch" -> step("config change arrives") {
                                // One change of the managed configuration, within 90 s (the hub sends it).
                                val changed = kotlinx.coroutines.runBlocking {
                                    kotlinx.coroutines.withTimeoutOrNull(90_000) { OhrConfig.changes.first() }
                                } ?: error("no change within 90 s")
                                out.put("values", bundleJson(changed))
                                bundleJson(changed).toString()
                            }
                        }
                    }
                } catch (e: Exception) {
                    fail("connect", e)
                }
                out.put("results", results)
                File(getExternalFilesDir(null), "checks-$check.json").writeText(out.toString(1))
            }
            val summary = "CHECK DONE $check: ${results.length() - failed} passed, $failed failed"
            Log.i(TAG, summary)
            text.text = summary
        }
    }

    /**
     * The first call of a fresh process made on the main thread: it binds the Agent and answers,
     * with no deadlock (the binding is answered on the library's own thread). Start the app after
     * `am force-stop` so nothing is connected yet.
     */
    private fun mainThread() {
        step("first call on the main thread") {
            check(OhrDeviceSdk.connection.value == OhrDeviceSdk.Connection.NOT_CONNECTED) { "already ${OhrDeviceSdk.connection.value}" }
            val answer = java.util.concurrent.atomic.AtomicReference<Any>()
            val done = java.util.concurrent.CountDownLatch(1)
            val started = System.currentTimeMillis()
            runOnUiThread {
                answer.set(runCatching { OhrDevice.info().deviceId }.getOrElse { it })
                done.countDown()
            }
            check(done.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "the main thread never came back" }
            val a = answer.get()
            if (a is Throwable) throw a
            "$a in ${System.currentTimeMillis() - started} ms, ${OhrDeviceSdk.connection.value}"
        }
    }

    /**
     * Subscriptions outlive the Agent: watches battery, network and kiosk mode, then waits (--ei wait seconds, 120)
     * for the Agent to stop and come back (the harness kills it after "CHECK READY reconnect"). Passes when the
     * connection went RECONNECTING then CONNECTED, each subscription heard from the Agent again, and a call works.
     */
    private fun reconnect() {
        val states = java.util.Collections.synchronizedList(mutableListOf<String>())
        val heard = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val backAt = java.util.concurrent.atomic.AtomicLong()
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        scope.launch {
            OhrDeviceSdk.connection.collect { c ->
                states += c.name
                if (c == OhrDeviceSdk.Connection.CONNECTED && states.contains("RECONNECTING")) backAt.set(System.currentTimeMillis())
            }
        }
        fun after(name: String) {
            if (backAt.get() > 0) heard.merge(name, 1, Int::plus)
        }
        scope.launch { OhrPower.batteryChanges.collect { after("battery") } }
        scope.launch { OhrNetwork.changes.collect { after("network") } }
        val kiosk = OhrKiosk.listenMode({ it.run() }) { after("kiosk") }
        Thread.sleep(1000)
        Log.i(TAG, "CHECK READY reconnect")
        val until = System.currentTimeMillis() + intent.getIntExtra("wait", 120) * 1000L
        while (System.currentTimeMillis() < until && !(backAt.get() > 0 && heard.keys.containsAll(listOf("battery", "network", "kiosk")))) Thread.sleep(250)
        step("agent stopped and came back") {
            check("RECONNECTING" in states && backAt.get() > 0) { "states ${states.joinToString(" > ")}" }
            states.joinToString(" > ")
        }
        step("subscriptions heard again") {
            for (n in listOf("battery", "network", "kiosk")) check((heard[n] ?: 0) > 0) { "nothing from $n after the Agent came back: $heard" }
            heard.toString()
        }
        step("calls after") { OhrDevice.info().deviceId }
        kiosk.close()
        scope.cancel()
    }

    /**
     * The heartbeat never stalls the main thread, even while the Agent restarts: kiosk mode with a 3 s heartbeat,
     * the main thread timed every 100 ms while the harness crashes the Agent (after "CHECK READY heartbeat-restart").
     */
    private fun heartbeatRestart() {
        step("kiosk start") { OhrKiosk.start(this); "pinned" }
        runOnUiThread { OhrKiosk.heartbeat(3000) }
        val gaps = java.util.concurrent.atomic.AtomicLong()
        val last = java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.uptimeMillis())
        val tick = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                gaps.accumulateAndGet(now - last.getAndSet(now)) { a, b -> maxOf(a, b) }
                window.decorView.postDelayed(this, 100)
            }
        }
        runOnUiThread { window.decorView.post(tick) }
        Thread.sleep(2000)
        gaps.set(0)
        Log.i(TAG, "CHECK READY heartbeat-restart")
        Thread.sleep(intent.getIntExtra("wait", 25) * 1000L)
        runOnUiThread { window.decorView.removeCallbacks(tick) }
        step("main thread never stalled") {
            val g = gaps.get()
            check(g < 1000) { "the main thread stalled ${g} ms" }
            "longest gap ${g} ms, ${OhrDeviceSdk.connection.value}"
        }
        step("still pinned, by the same process") { OhrKiosk.state().mode }
        runOnUiThread { OhrKiosk.stopHeartbeat() }
        step("kiosk stop") { OhrKiosk.stop(); OhrKiosk.state().mode }
    }

    private fun pass(name: String, detail: String = "") {
        Log.i(TAG, "CHECK PASS $name $detail")
        results.put(JSONObject().put("name", name).put("ok", true).put("detail", detail))
    }

    private fun fail(name: String, e: Throwable) {
        failed++
        Log.e(TAG, "CHECK FAIL $name: $e", e)
        results.put(JSONObject().put("name", name).put("ok", false).put("detail", e.toString()))
    }

    /** --es skip "name,name": checks not to run (e.g. one known to crash a test build). */
    private val skip by lazy { intent.getStringExtra("skip")?.split(',')?.map { it.trim() }?.toSet().orEmpty() }

    private inline fun step(name: String, block: () -> Any?) {
        if (name in skip) {
            Log.i(TAG, "CHECK SKIP $name")
            return
        }
        try {
            val r = block()
            pass(name, if (r is String) r else "")
        } catch (e: Throwable) {
            fail(name, e)
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun keys(access: OhrAccess.Info) {
        val keys = OhrKeys
        step("available") { check(keys.isAvailable()) }
        val pubs = HashMap<OhrKeys.Algorithm, ByteArray>()
        for (alg in OhrKeys.Algorithm.entries) step("generate ${alg.standardName}") {
            val pub = keys.generate("t.${alg.name.lowercase()}", alg)
            check(pub.size == OhrKem.publicKeySize(alg)) { "public key ${pub.size} bytes" }
            check(keys.publicKey("t.${alg.name.lowercase()}")!!.contentEquals(pub))
            pubs[alg] = pub
            "${pub.size} bytes"
        }
        step("list") {
            val l = keys.list().associateBy { it.alias }
            for (alg in OhrKeys.Algorithm.entries) check(l["t.${alg.name.lowercase()}"]?.algorithm == alg) { "no t.${alg.name.lowercase()}" }
            "${l.size} keys"
        }
        for (alg in listOf(OhrKeys.Algorithm.ML_KEM_768, OhrKeys.Algorithm.ML_KEM_1024, OhrKeys.Algorithm.X_WING)) step("kem ${alg.standardName}") {
            val e = keys.encapsulate(alg, pubs.getValue(alg))
            check(e.ciphertext.size == OhrKem.encapsulationSize(alg)) { "ciphertext ${e.ciphertext.size}" }
            check(keys.decapsulate("t.${alg.name.lowercase()}", e.ciphertext).contentEquals(e.sharedSecret)) { "secrets differ" }
            // A changed ciphertext gives another secret (implicit rejection), not an error.
            val bad = e.ciphertext.clone().also { it[5] = (it[5].toInt() xor 1).toByte() }
            check(!keys.decapsulate("t.${alg.name.lowercase()}", bad).contentEquals(e.sharedSecret)) { "a changed ciphertext gave the same secret" }
        }
        step("x25519 has no raw kem") {
            try {
                keys.decapsulate("t.x25519", ByteArray(32)); error("decapsulated")
            } catch (e: OhrException.Unsupported) {
                e.reason
            }
        }
        for (alg in OhrKeys.Algorithm.entries) step("hpke ${alg.standardName}") {
            val info = "check info".toByteArray()
            val aad = "check aad".toByteArray()
            val pt = "hello from the checks, ${alg.standardName}".toByteArray()
            val s = TestSeal.seal(keys, alg, pubs.getValue(alg), info, aad, pt)
            val alias = "t.${alg.name.lowercase()}"
            check(keys.hpkeOpen(alias, OhrHpke.KDF_HKDF_SHA256, OhrHpke.AEAD_AES_128_GCM, s.enc, info, aad, s.ct).contentEquals(pt))
            try {
                keys.hpkeOpen(alias, OhrHpke.KDF_HKDF_SHA256, OhrHpke.AEAD_AES_128_GCM, s.enc, "other".toByteArray(), aad, s.ct)
                error("opened with the wrong info")
            } catch (e: OhrException.Failed) {
            }
            "${s.ct.size} byte ciphertext"
        }
        step("jca") {
            OhrTechProvider.install()
            val kpg = KeyPairGenerator.getInstance("X-Wing", OhrTechProvider.NAME)
            kpg.initialize(OhrKeyGenParameterSpec("t.jca"))
            val pair = kpg.generateKeyPair()
            check(pair.public.encoded.size == 1216 && pair.private is OhrPrivateKey)
            val kem = OhrKem.getInstance("X-Wing")
            val (secret, ct) = kem.newEncapsulator(pair.public).encapsulate()
            check(kem.newDecapsulator(pair.private).decapsulate(ct).encoded.contentEquals(secret.encoded))
            val ks = KeyStore.getInstance(OhrTechProvider.NAME).apply { load(null) }
            check(ks.containsAlias("t.jca") && (ks.getKey("t.jca", null) as OhrPrivateKey).keyAlgorithm == OhrKeys.Algorithm.X_WING)
            val s = TestSeal.seal(keys, OhrKeys.Algorithm.X_WING, pair.public.encoded, ByteArray(0), ByteArray(0), byteArrayOf(1, 2, 3))
            check(OhrHpke.recipient(pair.private).open(s.enc, ByteArray(0), ByteArray(0), s.ct).contentEquals(byteArrayOf(1, 2, 3)))
            ks.deleteEntry("t.jca")
            check(!ks.containsAlias("t.jca"))
        }
        val attestations = JSONArray()
        for (alg in OhrKeys.Algorithm.entries) step("attest ${alg.standardName}") {
            val challenge = "checks-${alg.standardName}".toByteArray()
            val a = keys.attest("t.${alg.name.lowercase()}", challenge)
            check(a.algorithm == alg && a.publicKey.contentEquals(pubs.getValue(alg)))
            // The TEE names the app by the Agent's UUID for it (signed as is), not the public app_… id.
            check(Regex("^app:[0-9a-f-]{36}:").containsMatchIn(a.owner) && a.owner.endsWith(":${access.packageName}")) { "owner ${a.owner}" }
            attestations.put(JSONObject().put("alias", "t.${alg.name.lowercase()}").put("algorithm", alg.standardName)
                .put("publicKey", b64(a.publicKey)).put("owner", a.owner).put("challenge", b64(challenge))
                .put("coseSign1", b64(a.coseSign1)).put("diceChain", b64(a.diceChain)))
            "cose ${a.coseSign1.size} B, dice ${a.diceChain.size} B"
        }
        out.put("attestations", attestations)
        step("reserved alias") {
            try {
                keys.generate("ohr.push", OhrKeys.Algorithm.X25519); error("made ohr.push")
            } catch (e: OhrException.InvalidConfig) {
                e.field
            }
        }
        step("ohr.push hidden") { check(keys.list().none { it.alias.startsWith("ohr.") }) }
        step("delete") {
            keys.delete("t.ml_kem_1024")
            check(keys.publicKey("t.ml_kem_1024") == null)
            try {
                keys.decapsulate("t.ml_kem_1024", ByteArray(1568)); error("decapsulated with a deleted key")
            } catch (e: OhrException.InvalidConfig) {
            }
        }
    }

    private fun push() {
        val key = intent.getStringExtra("senderKey") ?: return fail("push", IllegalArgumentException("--es senderKey <b64>"))
        val pq = intent.getStringExtra("senderMlDsa65")
        val suite = OhrPush.Suite.named(intent.getStringExtra("suite") ?: "x25519")!!
        step("register ${suite.wireName}") {
            val t = OhrPush.register(listOf(SenderKey.fromBase64(key, pq)), suite)
            check(t.suite == suite && t.token.startsWith("psh_"))
            out.put("token", JSONObject().put("token", t.token).put("suite", t.suite.wireName).put("publicKey", t.publicKeyBase64))
            t.token
        }
        step("token") { check(OhrPush.token()?.token == out.getJSONObject("token").getString("token")) }
        step("ohr.push hidden") { check(OhrKeys.list().none { it.alias.startsWith("ohr.") }) }
    }

    private fun attestation() {
        val challenge = (intent.getStringExtra("challenge") ?: "checks-attestation").toByteArray()
        step("attest ec") {
            val a = OhrAttestation.attest(challenge, OhrAttestation.Suite.EC)
            out.put("ec", a.toJson())
            "${a.ec.size} certificates, leaf ${a.ec[0].publicKey.algorithm}"
        }
        step("token ec") { OhrAttestation.token("https://example.test").also { out.put("tokenEc", it) }.length.toString() }
        step("attest ec+ml-dsa-65") {
            val a = OhrAttestation.attest(challenge, OhrAttestation.Suite.EC_MLDSA65)
            out.put("hybrid", a.toJson())
            "ec ${a.ec.size}, ml-dsa ${a.mlDsa65!!.size} certificates, leaf ${a.mlDsa65!![0].publicKey.algorithm}"
        }
        step("token ec+ml-dsa-65") { OhrAttestation.token("https://example.test").also { out.put("tokenHybrid", it) }.split('.').size.toString() + " parts" }
    }

    private fun config() {
        val c = OhrConfig
        step("config values") {
            val v = bundleJson(c.values())
            out.put("values", v)
            v.toString()
        }
        step("config getters") {
            val g = JSONObject()
                .put("greeting", c.string("greeting")).put("store_number", c.int("store_number"))
                .put("kiosk_on_start", c.bool("kiosk_on_start")).put("mode", c.string("mode"))
                .put("features", JSONArray(c.strings("features"))).put("api_key", c.string("api_key"))
                .put("printer.port", c.bundle("printer")?.getInt("port", -1) ?: -1)
                .put("lanes", JSONArray(c.bundles("lanes").map { it.getString("name") }))
            out.put("getters", g)
            g.toString()
        }
    }

    @Suppress("DEPRECATION")
    private fun bundleJson(b: Bundle): JSONObject = JSONObject().also { o ->
        for (k in b.keySet().sorted()) {
            o.put(k, when (val v = b.get(k)) {
                is Bundle -> bundleJson(v)
                is Array<*> -> JSONArray(v.map { if (it is Bundle) bundleJson(it) else it })
                null -> JSONObject.NULL
                else -> v
            })
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "OhrSdkCheck"
    }
}
