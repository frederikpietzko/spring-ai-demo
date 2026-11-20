package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture.Companion.PAYOUT
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class DesktopControlWaitTest {
    private val fixture = ControlFixture()
    private val control = fixture.control

    @AfterEach
    fun close() = fixture.close()

    @Test
    suspend fun `wait-idle returns idle once the streaming answer is done`() = coroutineScope {
        assertIs<ControlReply.Ok>(control.execute("send Hi"))

        val wait = async { control.execute("wait-idle") }
        fixture.events.send(ChatEvent.TextDelta("Hello"))
        fixture.events.send(ChatEvent.Done)

        assertEquals(ControlReply.Ok("idle"), wait.await())
    }

    @Test
    suspend fun `wait-idle answers at once when nothing streams`() {
        assertEquals(ControlReply.Ok("idle"), control.execute("wait-idle"))
    }

    @Test
    suspend fun `wait-idle stops at a pending confirmation and names the tool`() = coroutineScope {
        assertIs<ControlReply.Ok>(control.execute("send Pay"))

        val wait = async { control.execute("wait-idle") }
        fixture.events.send(PAYOUT)

        assertEquals(ControlReply.Ok("idle: confirmation pending (issuePayout)"), wait.await())
    }

    @Test
    suspend fun `wait-idle fails when the reply ends in an error`() = coroutineScope {
        assertIs<ControlReply.Ok>(control.execute("send Hi"))

        val wait = async { control.execute("wait-idle") }
        fixture.events.send(ChatEvent.Error("The claims desk is unavailable."))

        assertEquals(ControlReply.Failed("error: The claims desk is unavailable."), wait.await())
    }

    @Test
    suspend fun `wait-idle times out while the answer keeps streaming`() {
        assertIs<ControlReply.Ok>(control.execute("send Hi"))

        assertEquals(ControlReply.TimedOut, control.execute("wait-idle 100ms"))
    }

    @Test
    suspend fun `sleep returns after the given time`() {
        val started = TimeSource.Monotonic.markNow()

        val result = control.execute("sleep 200ms")

        assertEquals(ControlReply.Ok("ok"), result)
        assertTrue(started.elapsedNow() >= 200.milliseconds, "returned after ${started.elapsedNow()}")
    }

    @Test
    suspend fun `state answers at once while wait-idle waits`() = coroutineScope {
        assertIs<ControlReply.Ok>(control.execute("send Hi"))
        val wait = async { control.execute("wait-idle") }

        val state = control.execute("state")

        val ok = assertIs<ControlReply.Ok>(state)
        assertTrue(""""sending":true""" in ok.body, ok.body)
        assertTrue(wait.isActive)
        fixture.events.send(ChatEvent.Done)
        assertEquals(ControlReply.Ok("idle"), wait.await())
    }
}
