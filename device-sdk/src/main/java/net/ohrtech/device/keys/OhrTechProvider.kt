package net.ohrtech.device.keys

import java.io.InputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGeneratorSpi
import java.security.KeyStoreSpi
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration

/**
 * The JCA provider "OhrTech" over [OhrKeys]: TEE keys by their standard names.
 * ```
 * OhrTechProvider.install()
 * val kpg = KeyPairGenerator.getInstance("ML-KEM-768", "OhrTech")
 * kpg.initialize(OhrKeyGenParameterSpec("orders"))
 * val pair = kpg.generateKeyPair()          // pair.private stays in the TEE: a handle
 * val ks = KeyStore.getInstance("OhrTech").apply { load(null) }
 * val key = ks.getKey("orders", null) as OhrPrivateKey
 * ```
 * Algorithms: "ML-KEM-768", "ML-KEM-1024", "X-Wing", "X25519". Then [OhrKem] and [OhrHpke].
 */
class OhrTechProvider private constructor() : @Suppress("DEPRECATION") Provider(NAME, 1.0, "OhrTech TEE keys (ML-KEM, X-Wing, X25519)") {
    init {
        put("KeyPairGenerator.ML-KEM-768", Generator.MlKem768::class.java.name)
        put("KeyPairGenerator.ML-KEM-1024", Generator.MlKem1024::class.java.name)
        put("KeyPairGenerator.X-Wing", Generator.XWing::class.java.name)
        put("KeyPairGenerator.X25519", Generator.X25519::class.java.name)
        put("KeyStore.$NAME", Store::class.java.name)
    }

    companion object {
        const val NAME = "OhrTech"

        /** Adds the provider (once per process). */
        @JvmStatic
        @Synchronized
        fun install() {
            if (Security.getProvider(NAME) == null) Security.addProvider(OhrTechProvider())
        }
    }

    /** KeyPairGenerator: needs an [OhrKeyGenParameterSpec] (the alias). One subclass per algorithm. */
    sealed class Generator(private val algorithm: OhrKeys.Algorithm) : KeyPairGeneratorSpi() {
        class MlKem768 : Generator(OhrKeys.Algorithm.ML_KEM_768)
        class MlKem1024 : Generator(OhrKeys.Algorithm.ML_KEM_1024)
        class XWing : Generator(OhrKeys.Algorithm.X_WING)
        class X25519 : Generator(OhrKeys.Algorithm.X25519)

        private var alias: String? = null

        override fun initialize(keysize: Int, random: SecureRandom?) =
            throw InvalidAlgorithmParameterException("initialize with an OhrKeyGenParameterSpec (the key's alias)")

        override fun initialize(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as? OhrKeyGenParameterSpec)?.alias
                ?: throw InvalidAlgorithmParameterException("initialize with an OhrKeyGenParameterSpec (the key's alias)")
        }

        override fun generateKeyPair(): KeyPair {
            val a = alias ?: throw IllegalStateException("initialize with an OhrKeyGenParameterSpec first")
            val pub = OhrKeys.generate(a, algorithm)
            return KeyPair(OhrPublicKey(algorithm, pub), OhrPrivateKey(a, algorithm))
        }
    }

    /** The app's TEE keys, read-only: aliases, and [OhrPrivateKey] handles (getKey). */
    class Store : KeyStoreSpi() {
        private fun keys() = OhrKeys.list()
        override fun engineGetKey(alias: String, password: CharArray?): Key? =
            keys().firstOrNull { it.alias == alias }?.let { OhrPrivateKey(it.alias, it.algorithm) }

        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = keys().firstOrNull { it.alias == alias }?.let { Date(it.createdAtMs) }
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException("TEE keys are made with KeyPairGenerator, not imported")

        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException("TEE keys are made with KeyPairGenerator, not imported")

        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) = OhrKeys.delete(alias)
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys().map { it.alias })
        override fun engineContainsAlias(alias: String) = keys().any { it.alias == alias }
        override fun engineSize() = keys().size
        override fun engineIsKeyEntry(alias: String) = engineContainsAlias(alias)
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }
}

/** The alias of the key a [OhrTechProvider] KeyPairGenerator makes. */
class OhrKeyGenParameterSpec(val alias: String) : AlgorithmParameterSpec

/** A TEE key's public half: the raw encoding of its algorithm (getFormat "RAW"). */
class OhrPublicKey(val keyAlgorithm: OhrKeys.Algorithm, private val raw: ByteArray) : PublicKey {
    override fun getAlgorithm() = keyAlgorithm.standardName
    override fun getFormat() = "RAW"
    override fun getEncoded(): ByteArray = raw.clone()
}

/** A handle to a TEE key: no key material (getEncoded is null), only its [alias]. */
class OhrPrivateKey(val alias: String, val keyAlgorithm: OhrKeys.Algorithm) : PrivateKey {
    override fun getAlgorithm() = keyAlgorithm.standardName
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}
