package net.ohrtech.device.push

import net.ohrtech.device.OhrException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The envelope and the accept rules, on vectors the OhrTech .NET Management SDK sealed (tools/push-vectors). */
class PushTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("push-vectors.json")!!.readText())
    private fun hex(name: String, o: JSONObject = v) = PushEnvelope.hex(o.getString(name))
    private val token = v.getString("token")
    private val x = v.getJSONObject("x25519")
    private val xw = v.getJSONObject("xwingMlDsa65")
    private val sender = SenderKey(hex("senderEd25519"), hex("senderMlDsa65"))
    private val exp = v.getLong("exp") * 1000

    private val xToken = PushToken(token, OhrPush.Suite.X25519, hex("recipientPublic", x))
    private val openX: (ByteArray, ByteArray) -> ByteArray = { enc, ct ->
        val info = PushEnvelope.LABEL.toByteArray() + byteArrayOf(0) + token.toByteArray()
        try {
            TestHpke.open(hex("recipientPrivate", x), hex("recipientPublic", x), enc, info, ByteArray(0), ct)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw OhrException.Failed("does not open")
        }
    }

    private class Seen {
        val ids = HashMap<String, Long>()
        fun mark(id: String, until: Long, now: Long): Boolean {
            if ((ids[id] ?: Long.MIN_VALUE) >= now) return false
            ids[id] = until
            return true
        }
    }

    private fun check(
        envelope: ByteArray = hex("envelope", x),
        tok: String = token,
        registered: PushToken? = xToken,
        keys: List<SenderKey> = listOf(sender),
        open: (ByteArray, ByteArray) -> ByteArray = openX,
        now: Long = exp - 60_000,
        seen: Seen = Seen(),
    ) = PushReceipt.check(envelope, tok, registered, keys, open, now, seen::mark)

    private fun dropped(o: PushReceipt.Outcome) = (o as? PushReceipt.Outcome.Dropped)?.reason

    @Test
    fun x25519EnvelopeParsesAndVerifies() {
        val e = PushEnvelope.parse(hex("envelope", x))
        assertEquals(OhrPush.Suite.X25519, e.suite)
        assertEquals(32, e.enc.size)
        assertNull(e.mlDsa65)
        assertTrue(e.signedBy(token, sender))
        assertTrue(e.signedBy(token, SenderKey(sender.ed25519)))
        assertFalse(e.signedBy(token + "x", sender))
        assertFalse(e.signedBy(token, SenderKey(ByteArray(32) { 7 })))
    }

    @Test
    fun xwingEnvelopeParses() {
        val e = PushEnvelope.parse(hex("envelope", xw))
        assertEquals(OhrPush.Suite.XWING_MLDSA65, e.suite)
        assertEquals(1120, e.enc.size)
        assertEquals(3309, e.mlDsa65!!.size)
        // Ed25519 alone is not enough for this suite.
        assertFalse(e.signedBy(token, SenderKey(sender.ed25519)))
    }

    @Test
    fun xwingEnvelopeVerifiesWhereTheJdkHasMlDsa() {
        assumeTrue("this JDK has no ML-DSA (24+ has)", runCatching { java.security.KeyFactory.getInstance("ML-DSA-65") }.isSuccess)
        assertTrue(PushEnvelope.parse(hex("envelope", xw)).signedBy(token, sender))
    }

    @Test
    fun signedBytesAreTheSendersArray() {
        val e = PushEnvelope.parse(hex("envelope", x))
        val s = Cbor.decode(PushEnvelope.signedBytes(token, e.suite, e.enc, e.ct)) as List<*>
        assertEquals(listOf("ohrtech-push-v1", token, "x25519"), s.take(3))
        assertArrayEquals(e.enc, s[3] as ByteArray)
        assertArrayEquals(e.ct, s[4] as ByteArray)
    }

    @Test
    fun acceptsTheVector() {
        val o = check() as PushReceipt.Outcome.Accepted
        assertEquals(v.getString("id"), o.plaintext.id)
        assertEquals(v.getLong("exp"), o.plaintext.expSeconds)
        assertArrayEquals(hex("data"), o.plaintext.data)
    }

    @Test
    fun anyOfTheSenderKeysWillDo() {
        assertNotNull(check(keys = listOf(SenderKey(ByteArray(32) { 9 }), sender)) as? PushReceipt.Outcome.Accepted)
    }

    @Test
    fun dropsWhatItMust() {
        assertEquals("signature", dropped(check(keys = listOf(SenderKey(ByteArray(32) { 9 })))))
        assertEquals("signature", dropped(check(keys = emptyList())))
        assertEquals("token", dropped(check(registered = null)))
        assertEquals("token", dropped(check(tok = "psh_other")))
        assertEquals("suite", dropped(check(registered = PushToken(token, OhrPush.Suite.XWING_MLDSA65, ByteArray(1216)))))
        assertEquals("malformed", dropped(check(envelope = byteArrayOf(1, 2, 3))))
        assertEquals("does_not_open", dropped(check(open = { _, _ -> throw OhrException.Failed("does not open") })))
        assertEquals("malformed", dropped(check(open = { _, _ -> byteArrayOf(0xa0.toByte()) })))
    }

    @Test
    fun aTamperedCiphertextFailsTheSignature() {
        val m = (Cbor.decode(hex("envelope", x)) as Map<*, *>)
        val ct = (m["ct"] as ByteArray).clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val sig = m["sig"] as Map<*, *>
        val tampered = Cbor.Writer().map(5).text("v").uint(1).text("suite").text("x25519").text("enc").bytes(m["enc"] as ByteArray)
            .text("ct").bytes(ct).text("sig").map(1).text("ed25519").bytes(sig["ed25519"] as ByteArray).encode()
        assertEquals("signature", dropped(check(envelope = tampered)))
    }

    @Test
    fun expiryHasTwoMinutesGrace() {
        assertNotNull(check(now = exp + PushReceipt.GRACE_MS) as? PushReceipt.Outcome.Accepted)
        assertEquals("expired", dropped(check(now = exp + PushReceipt.GRACE_MS + 1)))
    }

    @Test
    fun aRepeatIsADuplicateUntilItExpires() {
        val seen = Seen()
        assertNotNull(check(seen = seen) as? PushReceipt.Outcome.Accepted)
        assertEquals("duplicate", dropped(check(seen = seen)))
    }

    @Test
    fun envelopeShapeIsChecked() {
        fun env(build: Cbor.Writer.() -> Unit) = Cbor.Writer().apply(build).encode()
        val e = PushEnvelope.parse(hex("envelope", x))
        fun bad(b: ByteArray) = runCatching { PushEnvelope.parse(b) }.exceptionOrNull() is CborException
        // Version 2.
        assertTrue(bad(env { map(5).text("v").uint(2).text("suite").text("x25519").text("enc").bytes(e.enc).text("ct").bytes(e.ct).text("sig").map(1).text("ed25519").bytes(e.ed25519) }))
        // An ML-DSA signature in an x25519 message.
        assertTrue(bad(env { map(5).text("v").uint(1).text("suite").text("x25519").text("enc").bytes(e.enc).text("ct").bytes(e.ct).text("sig").map(2).text("ed25519").bytes(e.ed25519).text("mldsa65").bytes(ByteArray(3309)) }))
        // An unknown field.
        assertTrue(bad(env { map(6).text("v").uint(1).text("suite").text("x25519").text("enc").bytes(e.enc).text("ct").bytes(e.ct).text("sig").map(1).text("ed25519").bytes(e.ed25519).text("x").uint(0) }))
        // A short enc.
        assertTrue(bad(env { map(5).text("v").uint(1).text("suite").text("x25519").text("enc").bytes(ByteArray(31)).text("ct").bytes(e.ct).text("sig").map(1).text("ed25519").bytes(e.ed25519) }))
        // Trailing bytes.
        assertTrue(bad(hex("envelope", x) + byteArrayOf(0)))
    }

    @Test
    fun senderKeysComeFromBase64() {
        val b = java.util.Base64.getEncoder()
        assertEquals(sender, SenderKey.fromBase64(b.encodeToString(sender.ed25519), b.encodeToString(sender.mlDsa65)))
        assertFalse(sender == SenderKey(sender.ed25519))
        assertTrue(runCatching { SenderKey(ByteArray(31)) }.isFailure)
    }
}
