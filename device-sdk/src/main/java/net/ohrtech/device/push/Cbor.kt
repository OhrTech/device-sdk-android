package net.ohrtech.device.push

import java.io.ByteArrayOutputStream

/** Not well-formed CBOR, or not the shape push expects. */
internal class CborException(message: String) : Exception(message)

/**
 * The bit of CBOR (RFC 8949) push needs. Reads: unsigned and negative integers, byte and text
 * strings, arrays and maps (maps also of indefinite length), false, true, null. Writes: shortest
 * heads, definite lengths (§4.2.1), as the signed bytes require.
 */
internal object Cbor {
    private const val MAX_DEPTH = 8

    /** The single item [bytes] holds: Long, ByteArray, String, List, LinkedHashMap, Boolean or null. */
    fun decode(bytes: ByteArray): Any? {
        val r = Reader(bytes)
        val v = r.item(0)
        if (r.pos != bytes.size) throw CborException("trailing bytes")
        return v
    }

    private class Reader(val b: ByteArray) {
        var pos = 0

        private fun byte(): Int {
            if (pos >= b.size) throw CborException("truncated")
            return b[pos++].toInt() and 0xff
        }

        /** The argument of a head whose low 5 bits are [info]; -1 for indefinite length. */
        private fun argument(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> byte().toLong()
            info == 25 -> (byte().toLong() shl 8) or byte().toLong()
            info == 26 -> (0 until 4).fold(0L) { a, _ -> (a shl 8) or byte().toLong() }
            info == 27 -> (0 until 8).fold(0L) { a, _ -> (a shl 8) or byte().toLong() }.also {
                if (it < 0) throw CborException("integer too large")
            }
            info == 31 -> -1
            else -> throw CborException("reserved head")
        }

        private fun length(info: Int): Int {
            val n = argument(info)
            if (n < 0) throw CborException("indefinite length")
            if (n > b.size - pos) throw CborException("truncated")
            return n.toInt()
        }

        fun item(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw CborException("nested too deep")
            val head = byte()
            val info = head and 0x1f
            return when (head ushr 5) {
                0 -> argument(info).also { if (it < 0) throw CborException("indefinite integer") }
                1 -> argument(info).also { if (it < 0) throw CborException("indefinite integer") }.let { -1 - it }
                2 -> length(info).let { n -> b.copyOfRange(pos, pos + n).also { pos += n } }
                3 -> length(info).let { n -> String(b, pos, n, Charsets.UTF_8).also { pos += n } }
                4 -> {
                    val n = argument(info)
                    if (n < 0 || n > b.size - pos) throw CborException("bad array length")
                    List(n.toInt()) { item(depth + 1) }
                }
                5 -> {
                    val n = argument(info)
                    val m = LinkedHashMap<Any?, Any?>()
                    if (n < 0) {
                        while (true) {
                            if (pos >= b.size) throw CborException("truncated")
                            if (b[pos].toInt() and 0xff == 0xff) { pos++; break }
                            put(m, item(depth + 1), item(depth + 1))
                        }
                    } else {
                        if (n > (b.size - pos) / 2) throw CborException("bad map length")
                        repeat(n.toInt()) { put(m, item(depth + 1), item(depth + 1)) }
                    }
                    m
                }
                7 -> when (info) {
                    20 -> false
                    21 -> true
                    22 -> null
                    else -> throw CborException("unsupported simple value")
                }
                else -> throw CborException("unsupported major type ${head ushr 5}")
            }
        }

        private fun put(m: MutableMap<Any?, Any?>, k: Any?, v: Any?) {
            if (k !is String) throw CborException("map key is not text")
            if (m.containsKey(k)) throw CborException("duplicate key $k")
            m[k] = v
        }
    }

    class Writer {
        private val out = ByteArrayOutputStream()

        private fun head(major: Int, n: Long): Writer {
            val m = major shl 5
            when {
                n < 24 -> out.write(m or n.toInt())
                n < 0x100 -> { out.write(m or 24); out.write(n.toInt()) }
                n < 0x10000 -> { out.write(m or 25); for (s in 8 downTo 0 step 8) out.write((n ushr s).toInt() and 0xff) }
                n < 0x100000000 -> { out.write(m or 26); for (s in 24 downTo 0 step 8) out.write((n ushr s).toInt() and 0xff) }
                else -> { out.write(m or 27); for (s in 56 downTo 0 step 8) out.write((n ushr s).toInt() and 0xff) }
            }
            return this
        }

        fun uint(v: Long) = head(0, v.also { require(it >= 0) })
        fun bytes(v: ByteArray) = head(2, v.size.toLong()).also { out.write(v) }
        fun text(v: String) = v.toByteArray(Charsets.UTF_8).let { head(3, it.size.toLong()); out.write(it); this }
        fun array(n: Int) = head(4, n.toLong())
        fun map(n: Int) = head(5, n.toLong())
        fun encode(): ByteArray = out.toByteArray()
    }
}
