package net.ohrtech.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import net.ohrtech.device.aidl.IOhrCertificates
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The app's client certificates and trusted CAs (Agent 1061+; fleet setting apps.certificates, set per app on the
 * fleet's Applications tab).
 *
 * - **Device-made** ([request]): a key in the app's own Android Keystore (in the TEE), attested, certified by the
 *   organisation's issuing CA. The certificate names the device (URI urn:ohrtech:device:dev_…), the app and the
 *   package, and validates to the organisation's client root and to the OhrTech Device Identity Root. The key never
 *   leaves the device. [renewIfDue] makes a new key and certificate when a third of the lifetime is left.
 * - **Imported**: PKCS#12 bundles the fleet gives the app, installed into the app's Keystore on first use.
 * - **Trusted CAs**: private CAs the fleet gives the app, besides the system's ([trustManager]).
 *
 * [sslContext] puts them together for TLS: `HttpsURLConnection.setSSLSocketFactory(sdk.certificates.sslContext().socketFactory)`.
 */
class OhrCertificates internal constructor(private val sdk: OhrDeviceSdk, context: Context) {
    /**
     * A client certificate: [alias] as the app names it; [imported] from the fleet's PKCS#12.
     *
     * A device-made certificate has two [paths], each leaf first: through the organisation's client root (once the
     * organisation has uploaded its certificate for the issuing CA) and through the OhrTech Device Identity Root.
     * Both have the same issuing CA subject and key, so TLS sends one at a time: [keyManager] picks the one whose root
     * the server asks for, else the first. [chain] is the first.
     */
    data class Certificate(val alias: String, val paths: List<List<X509Certificate>>, val imported: Boolean) {
        val chain: List<X509Certificate> get() = paths[0]
        val certificate: X509Certificate get() = paths[0][0]
    }

    private val api get() = IOhrCertificates.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_CERTIFICATES))
    private val prefs = context.applicationContext.getSharedPreferences("net.ohrtech.device.certificates", Context.MODE_PRIVATE)
    private val keyStore: KeyStore get() = KeyStore.getInstance(STORE).apply { load(null) }

    private fun needsVersion2() {
        if (sdk.session.agentInterface < 2) throw OhrException.Unsupported("This OhrTech Agent is too old for it", needsAgent = 1061)
    }

    private fun config(): JSONObject = JSONObject(agentCall { api.config() })

    /** Whether the fleet gives this app an issuing CA, so [request] can work. */
    @WorkerThread
    fun canRequest(): Boolean {
        needsVersion2()
        return config().optBoolean("issuingCa")
    }

    /**
     * A new key and certificate under [alias] (1 to 64 of A-Z a-z 0-9 . _ -), replacing the one before. The key is
     * made in the TEE and attested; the hub certifies it only if the attestation holds (this device, this app, a
     * verified OhrTech boot). Takes a few seconds; [timeoutMs] for the hub's answer.
     */
    @WorkerThread
    @JvmOverloads
    fun request(alias: String, timeoutMs: Long = 60_000): Certificate {
        needsVersion2()
        require(Regex("^[A-Za-z0-9._-]{1,64}$").matches(alias)) { "an alias is 1 to 64 of A-Z a-z 0-9 . _ -" }
        val generation = prefs.getInt("gen.$alias", 0) + 1
        val internal = "$PREFIX$alias.$generation"
        val spec = KeyGenParameterSpec.Builder(internal, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setAttestationChallenge("ohr-certificate:$alias".toByteArray())
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply { initialize(spec) }.generateKeyPair()
        val ks = keyStore
        try {
            val attestation = ks.getCertificateChain(internal).map { Base64.getEncoder().encodeToString(it.encoded) }.toTypedArray()
            val answer = AtomicReference<Pair<String, String>>()
            val done = CountDownLatch(1)
            agentCall {
                api.request(alias, attestation, object : IOhrListener.Stub() {
                    override fun onChange(what: String, json: String) {
                        answer.set(what to json)
                        done.countDown()
                    }
                })
            }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw OhrException.Failed("the hub did not answer within ${timeoutMs / 1000} s")
            val (what, json) = answer.get()
            if (what != "issued") throw OhrException.Refused(JSONObject(json).optString("reason", "refused"))
            val chain = JSONObject(json).getJSONArray("chain").let { a -> (0 until a.length()).map { cert(Base64.getDecoder().decode(a.getString(it))) } }
            // The key's chain is now the issued one (the attestation chain is no longer needed).
            ks.setKeyEntry(internal, ks.getKey(internal, null), null, chain.toTypedArray())
            val old = prefs.getString("cur.$alias", null)
            prefs.edit().putInt("gen.$alias", generation).putString("cur.$alias", internal).apply()
            if (old != null && old != internal) runCatching { ks.deleteEntry(old) }
            return Certificate(alias, paths(chain), imported = false)
        } catch (e: Exception) {
            runCatching { ks.deleteEntry(internal) }
            throw e
        }
    }

    /** A new key and certificate for [alias] when less than a third of its lifetime is left; null when it isn't due. */
    @WorkerThread
    fun renewIfDue(alias: String): Certificate? {
        val c = list().firstOrNull { it.alias == alias && !it.imported } ?: return null
        val life = c.certificate.notAfter.time - c.certificate.notBefore.time
        return if (c.certificate.notAfter.time - System.currentTimeMillis() < life / 3) request(alias) else null
    }

    /** The app's client certificates: device-made and imported (the fleet's PKCS#12 bundles are installed here first). */
    @WorkerThread
    fun list(): List<Certificate> {
        needsVersion2()
        installImported(config())
        val ks = keyStore
        val out = mutableListOf<Certificate>()
        for (key in prefs.all.keys.filter { it.startsWith("cur.") }) {
            val internal = prefs.getString(key, null) ?: continue
            val chain = ks.getCertificateChain(internal)?.map { it as X509Certificate } ?: continue
            out += Certificate(key.removePrefix("cur."), paths(chain), imported = false)
        }
        for (key in prefs.all.keys.filter { it.startsWith("imp.") }) {
            val alias = key.removePrefix("imp.")
            val chain = ks.getCertificateChain(IMPORTED + alias)?.map { it as X509Certificate } ?: continue
            out += Certificate(alias, listOf(chain), imported = true)
        }
        return out
    }

    /** Puts the fleet's PKCS#12 bundles into the Keystore (once each), and removes the ones the fleet took away. */
    private fun installImported(config: JSONObject) {
        val ks = keyStore
        val bundles = config.optJSONArray("imported") ?: JSONArray()
        val wanted = mutableSetOf<String>()
        for (i in 0 until bundles.length()) {
            val b = bundles.getJSONObject(i)
            val alias = b.getString("alias")
            wanted += alias
            val p12 = Base64.getDecoder().decode(b.getString("pkcs12"))
            val hash = MessageDigest.getInstance("SHA-256").digest(p12).joinToString("") { "%02x".format(it) }
            if (prefs.getString("imp.$alias", null) == hash && ks.containsAlias(IMPORTED + alias)) continue
            val password = b.optString("password").toCharArray()
            val pkcs = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(p12), password) }
            val src = pkcs.aliases().toList().first { pkcs.isKeyEntry(it) }
            val key = pkcs.getKey(src, password) as PrivateKey
            val chain = pkcs.getCertificateChain(src)
            val algorithm = key.algorithm
            val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or (if (algorithm == "RSA") KeyProperties.PURPOSE_DECRYPT else 0))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .apply { if (algorithm == "RSA") setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1) }
                .build()
            ks.setEntry(IMPORTED + alias, KeyStore.PrivateKeyEntry(key, chain), protection)
            prefs.edit().putString("imp.$alias", hash).apply()
        }
        for (key in prefs.all.keys.filter { it.startsWith("imp.") }) {
            val alias = key.removePrefix("imp.")
            if (alias !in wanted) {
                runCatching { ks.deleteEntry(IMPORTED + alias) }
                prefs.edit().remove(key).apply()
            }
        }
    }

    /**
     * A key manager presenting [alias]'s certificate (or, null, any of the app's) to servers that ask for one. For a
     * device-made certificate it sends the path to the root the server names in its request (most servers name the
     * CAs they trust: nginx's ssl_client_certificate, OpenSSL's CA file); with none named, the organisation's.
     */
    @WorkerThread
    @JvmOverloads
    fun keyManager(alias: String? = null): X509ExtendedKeyManager {
        val certs = list().filter { alias == null || it.alias == alias }
        val ks = keyStore
        val keys = certs.associate { c -> c.alias to (ks.getKey(internalOf(c), null) as PrivateKey) }
        // A chosen alias is "<alias>#<path>" ('#' is never in an alias).
        fun chosen(a: String?): Pair<Certificate, List<X509Certificate>>? {
            val name = a?.substringBefore('#') ?: return null
            val c = certs.firstOrNull { it.alias == name } ?: return null
            return c to (c.paths.getOrNull(a.substringAfter('#', "0").toIntOrNull() ?: 0) ?: return null)
        }
        return object : X509ExtendedKeyManager() {
            private fun pick(types: Array<out String>?, issuers: Array<out Principal>?): String? {
                for (c in certs) {
                    val algorithm = c.certificate.publicKey.algorithm
                    if (types != null && types.none { it.equals(algorithm, true) || (algorithm == "EC" && it.startsWith("EC")) }) continue
                    if (issuers.isNullOrEmpty()) return "${c.alias}#0"
                    val i = c.paths.indexOfFirst { p -> p.any { x -> issuers.any { it == x.issuerX500Principal || it == x.subjectX500Principal } } }
                    if (i >= 0) return "${c.alias}#$i"
                }
                return null
            }
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = pick(keyType, issuers)
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = pick(keyType, issuers)
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = certs.map { "${it.alias}#0" }.toTypedArray()
            override fun getCertificateChain(a: String?) = chosen(a)?.second?.toTypedArray()
            override fun getPrivateKey(a: String?) = chosen(a)?.let { keys[it.first.alias] }
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
        }
    }

    private fun internalOf(c: Certificate) = if (c.imported) IMPORTED + c.alias else prefs.getString("cur.${c.alias}", null)!!

    /** Trusts the system's CAs and the private CAs the fleet gives the app. */
    @WorkerThread
    fun trustManager(): X509TrustManager {
        needsVersion2()
        val system = defaultTrust(null)
        val pems = config().optJSONArray("trust") ?: JSONArray()
        if (pems.length() == 0) return system
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            for (i in 0 until pems.length()) setCertificateEntry("fleet$i", cert(pem(pems.getString(i))))
        }
        val fleet = defaultTrust(anchors)
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: Exception) {
                    fleet.checkServerTrusted(chain, authType)
                }
            }
            override fun getAcceptedIssuers() = system.acceptedIssuers + fleet.acceptedIssuers
        }
    }

    /** TLS with the app's client certificate ([alias], or any) and its trusted CAs. */
    @WorkerThread
    @JvmOverloads
    fun sslContext(alias: String? = null): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(keyManager(alias)), arrayOf<TrustManager>(trustManager()), null) }

    private val watcher = Watcher({ api.watch(it) }, { api.unwatch(it) }) { c -> if (c.what == "changed") Unit else null }

    /** The fleet changed the app's certificates (issuing CA, trusted CAs, imported bundles). */
    val changes: Flow<Unit> get() = watcher.flow.also { needsVersion2() }
    fun listen(executor: Executor, listener: OhrListener<Unit>): Closeable {
        needsVersion2()
        return watcher.listen(executor, listener)
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val PREFIX = "net.ohrtech.cert."
        const val IMPORTED = "net.ohrtech.cert.imported."
        /**
         * The hub's chain (leaf, then the issuing CA by the organisation's root and its chain, then by OhrTech and
         * Intermediate 1) as separate paths: one per certificate for the leaf's issuer, each followed up its issuers.
         */
        fun paths(all: List<X509Certificate>): List<List<X509Certificate>> {
            val leaf = all[0]
            val rest = all.drop(1)
            val paths = rest.filter { it.subjectX500Principal == leaf.issuerX500Principal }.map { first ->
                val path = mutableListOf(leaf, first)
                while (path.last().issuerX500Principal != path.last().subjectX500Principal) {
                    val next = rest.firstOrNull { it !in path && it.subjectX500Principal == path.last().issuerX500Principal } ?: break
                    path += next
                }
                path.toList()
            }
            return paths.ifEmpty { listOf(all) }
        }

        fun cert(der: ByteArray) = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        fun pem(text: String): ByteArray = Base64.getMimeDecoder().decode(text.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----"))
        fun defaultTrust(ks: KeyStore?): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}
