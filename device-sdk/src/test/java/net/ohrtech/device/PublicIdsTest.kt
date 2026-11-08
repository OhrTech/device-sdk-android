package net.ohrtech.device

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicIdsTest {
    @Test
    fun appIdsMatchTheManagementApi() {
        // The same encoding as the Management API's ids (26 characters, most significant first).
        assertEquals("app_00000000000000000000000001", PublicIds.app("00000000-0000-0000-0000-000000000001"))
        assertEquals("app_7zzzzzzzzzzzzzzzzzzzzzzzzz", PublicIds.app("ffffffff-ffff-ffff-ffff-ffffffffffff"))
        assertEquals("app_014enwthb7h7qg28t5cy4tqkff", PublicIds.app("0123abcd-4567-89ef-0123-456789abcdef"))
        assertEquals("not-a-uuid", PublicIds.app("not-a-uuid"))
    }
}
