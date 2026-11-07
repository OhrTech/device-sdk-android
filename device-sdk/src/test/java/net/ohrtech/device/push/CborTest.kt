package net.ohrtech.device.push

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CborTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fails(b: ByteArray) = runCatching { Cbor.decode(b) }.exceptionOrNull() is CborException

    @Test
    fun headsAreShortest() {
        assertEquals("17", hex(Cbor.Writer().uint(23).encode()))
        assertEquals("1818", hex(Cbor.Writer().uint(24).encode()))
        assertEquals("18ff", hex(Cbor.Writer().uint(255).encode()))
        assertEquals("190100", hex(Cbor.Writer().uint(256).encode()))
        assertEquals("1a00010000", hex(Cbor.Writer().uint(65536).encode()))
        assertEquals("1b0000000100000000", hex(Cbor.Writer().uint(1L shl 32).encode()))
        assertEquals("6161", hex(Cbor.Writer().text("a").encode()))
        assertEquals("5818" + "00".repeat(24), hex(Cbor.Writer().bytes(ByteArray(24)).encode()))
    }

    @Test
    fun roundTrips() {
        val b = Cbor.Writer().map(3).text("id").text("x").text("exp").uint(1_800_000_000).text("data").bytes(byteArrayOf(1, 2)).encode()
        val m = Cbor.decode(b) as Map<*, *>
        assertEquals(listOf("id", "exp", "data"), m.keys.toList())
        assertEquals(1_800_000_000L, m["exp"])
        assertArrayEquals(byteArrayOf(1, 2), m["data"] as ByteArray)
    }

    @Test
    fun readsTheRest() {
        assertEquals(-1L, Cbor.decode(byteArrayOf(0x20)))
        assertEquals(listOf(true, false, null), Cbor.decode(byteArrayOf(0x83.toByte(), 0xf5.toByte(), 0xf4.toByte(), 0xf6.toByte())))
        // An indefinite-length map.
        assertEquals(mapOf("a" to 1L), Cbor.decode(byteArrayOf(0xbf.toByte(), 0x61, 0x61, 0x01, 0xff.toByte())))
    }

    @Test
    fun refusesWhatIsNotWellFormed() {
        assertTrue(fails(byteArrayOf()))
        assertTrue(fails(byteArrayOf(0x01, 0x01)))                       // trailing
        assertTrue(fails(byteArrayOf(0x62, 0x61)))                       // truncated text
        assertTrue(fails(byteArrayOf(0x5a, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))) // huge length
        assertTrue(fails(byteArrayOf(0xa2.toByte(), 0x61, 0x61, 0x01, 0x61, 0x61, 0x02))) // duplicate key
        assertTrue(fails(byteArrayOf(0xa1.toByte(), 0x01, 0x01)))        // integer key
        assertTrue(fails(byteArrayOf(0x5f, 0xff.toByte())))              // indefinite bytes
        assertTrue(fails(byteArrayOf(0xfb.toByte(), 0, 0, 0, 0, 0, 0, 0, 0))) // a float
        assertTrue(fails(ByteArray(20) { 0x81.toByte() } + byteArrayOf(0))) // nested too deep
        assertTrue(fails(byteArrayOf(0x1b, 0xff.toByte(), 0, 0, 0, 0, 0, 0, 0))) // over 2^63
    }
}
