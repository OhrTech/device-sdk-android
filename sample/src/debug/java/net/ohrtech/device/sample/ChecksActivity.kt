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
import net.ohrtech.device.OhrDeviceSdk
import net.ohrtech.device.OhrException
import net.ohrtech.device.keys.OhrHpke
import net.ohrtech.device.keys.OhrKem
import net.ohrtech.device.keys.OhrKeyGenParameterSpec
import net.ohrtech.device.keys.OhrKeys
import net.ohrtech.device.keys.OhrPrivateKey
import net.ohrtech.device.keys.OhrTechProvider
import net.ohrtech.device.push.OhrPush
import net.ohrtech.device.push.SenderKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64

/**
 * Device checks of the library against the real Agent and TEE (debug builds only):
 * `am start -n <pkg>/net.ohrtech.device.sample.ChecksActivity --es check keys|push [--es senderKey <b64>] [--es suite x25519|xwing-mldsa65]`.
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
                    OhrDeviceSdk.connectBlocking(this@ChecksActivity).use { sdk ->
                        out.put("session", JSONObject().put("app", sdk.session.app).put("package", sdk.session.packageName)
                            .put("agent", sdk.session.agentVersion).put("deviceId", sdk.device.info().deviceId))
                        when (check) {
                            "keys" -> keys(sdk)
                            "push" -> push(sdk)
                            "unregister" -> step("unregister") { sdk.push.unregister(); check(sdk.push.token() == null) }
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

    private fun pass(name: String, detail: String = "") {
        Log.i(TAG, "CHECK PASS $name $detail")
        results.put(JSONObject().put("name", name).put("ok", true).put("detail", detail))
    }

    private fun fail(name: String, e: Throwable) {
        failed++
        Log.e(TAG, "CHECK FAIL $name: $e", e)
        results.put(JSONObject().put("name", name).put("ok", false).put("detail", e.toString()))
    }

    private inline fun step(name: String, block: () -> Any?) {
        try {
            val r = block()
            pass(name, if (r is String) r else "")
        } catch (e: Throwable) {
            fail(name, e)
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun keys(sdk: OhrDeviceSdk) {
        val keys = sdk.keys
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
            OhrTechProvider.install(sdk)
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
            check(a.owner == "app:${sdk.session.app}:${sdk.session.packageName}") { "owner ${a.owner}" }
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

    private fun push(sdk: OhrDeviceSdk) {
        val key = intent.getStringExtra("senderKey") ?: return fail("push", IllegalArgumentException("--es senderKey <b64>"))
        val pq = intent.getStringExtra("senderMlDsa65")
        val suite = OhrPush.Suite.named(intent.getStringExtra("suite") ?: "x25519")!!
        step("register ${suite.wireName}") {
            val t = sdk.push.register(listOf(SenderKey.fromBase64(key, pq)), suite)
            check(t.suite == suite && t.token.startsWith("psh_"))
            out.put("token", JSONObject().put("token", t.token).put("suite", t.suite.wireName).put("publicKey", t.publicKeyBase64))
            t.token
        }
        step("token") { check(sdk.push.token()?.token == out.getJSONObject("token").getString("token")) }
        step("ohr.push hidden") { check(sdk.keys.list().none { it.alias.startsWith("ohr.") }) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "OhrSdkCheck"
    }
}
