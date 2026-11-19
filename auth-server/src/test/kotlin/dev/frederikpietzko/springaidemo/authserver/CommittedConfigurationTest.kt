package dev.frederikpietzko.springaidemo.authserver

import kotlin.test.Test
import kotlin.test.assertEquals

class CommittedConfigurationTest {
    @Test
    fun `the committed configuration serves port 9000`() {
        val port = committedProperty("server.port")

        assertEquals("9000", port)
    }
}
