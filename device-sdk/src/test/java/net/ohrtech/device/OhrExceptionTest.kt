package net.ohrtech.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OhrExceptionTest {
    private fun from(m: String?) = OhrException.from(IllegalStateException(m))

    @Test
    fun readsTheAgentsCodes() {
        assertTrue(from("ohr:1:") is OhrException.NotManaged)
        assertEquals("wifi", (from("ohr:2:wifi") as OhrException.NotAllowed).area)
        assertEquals("power.screen_timeout", (from("ohr:3:power.screen_timeout") as OhrException.FleetLocked).key)
        assertEquals("needs the OhrTech KEM app", (from("ohr:4:needs the OhrTech KEM app") as OhrException.Unsupported).reason)
        (from("ohr:5:password\nis 8 to 63 characters") as OhrException.InvalidConfig).let {
            assertEquals("password", it.field)
            assertEquals("is 8 to 63 characters", it.reason)
        }
        assertEquals("the hub: a: b", (from("ohr:6:the hub: a: b") as OhrException.Failed).reason)
        assertTrue(from("ohr:99:later") is OhrException.Failed)
    }

    @Test
    fun leavesOtherErrorsAlone() {
        assertNull(from("something else"))
        assertNull(from(null))
        assertNull(from("ohr:x"))
        assertNull(OhrException.from(IllegalArgumentException("ohr:1:")))
    }

    @Test
    fun refusedArgumentsAreInvalidConfig() {
        val e = try {
            checkArg(false, "alias") { "1 to 64 of A-Z a-z 0-9 . _ -" }
            null
        } catch (x: OhrException.InvalidConfig) {
            x
        }
        assertEquals("alias", e!!.field)
        assertEquals("1 to 64 of A-Z a-z 0-9 . _ -", e.reason)
        checkArg(true, "alias") { error("not evaluated") }
    }
}
