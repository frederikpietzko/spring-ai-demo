package dev.frederikpietzko.springaidemo.coremcp.testsupport

import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lifecycle of the forked auth-server, outside any Spring context. */
class AuthServerProcessTest {
    @Test
    fun `the forked auth-server stops when the application context closes`() {
        val authServer = AuthServerProcess()
        assertTrue(authServer.isRunning)

        authServer.onApplicationEvent(ContextClosedEvent(GenericApplicationContext()))

        assertFalse(authServer.isRunning)
    }
}
