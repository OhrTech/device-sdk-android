package net.ohrtech.device

import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The Management API's ids (`app_…`, `dev_…`): a type prefix and the UUID's 128 bits in 26 characters of
 * 0-9 a-z without i, l, o and u, most significant first. The Agent names apps by UUID; the app sees `app_…`.
 */
internal object PublicIds {
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    fun format(prefix: String, id: UUID): String {
        var v = BigInteger(1, ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array())
        val out = CharArray(26)
        val thirtyTwo = BigInteger.valueOf(32)
        for (i in 25 downTo 0) {
            out[i] = ALPHABET[v.mod(thirtyTwo).toInt()]
            v = v.shiftRight(5)
        }
        return prefix + "_" + String(out)
    }

    /** The app's `app_…` id from the Agent's UUID; anything else unchanged. */
    fun app(agentId: String): String = runCatching { format("app", UUID.fromString(agentId)) }.getOrDefault(agentId)
}
