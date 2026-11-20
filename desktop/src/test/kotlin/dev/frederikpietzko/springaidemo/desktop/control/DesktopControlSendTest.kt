package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture.Companion.PAYOUT
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class DesktopControlSendTest {
    private val fixture = ControlFixture()

    @AfterEach
    fun close() = fixture.close()

    @Test
    suspend fun `send sends the text with the selected persona and returns before the answer ends`() {
        val result = fixture.control.execute("""send "Hi"""")

        assertIs<ControlReply.Ok>(result)
        assertEquals([ChatRequest(Persona.CLAIMANT, "Hi", "c-1")], fixture.api.requests)
        assertTrue(fixture.viewModel.state.value.sending)
    }

    @Test
    suspend fun `the text is typed character by character and sent only once it is complete`() = coroutineScope {
        val requestedAt = CopyOnWriteArrayList<TimeMark>()
        val reply = { _: ChatRequest ->
            requestedAt += TimeSource.Monotonic.markNow()
            flowOf(ChatEvent.Done)
        }
        ControlFixture(typingSpeed = TYPING, reply = reply).use { slow ->
            val inputs = CopyOnWriteArrayList<Pair<String, TimeMark>>()
            val watcher = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                slow.viewModel.state.map { it.input }.distinctUntilChanged().collect {
                    inputs += it to TimeSource.Monotonic.markNow()
                }
            }

            assertIs<ControlReply.Ok>(slow.control.execute("""send "Hi""""))

            slow.viewModel.state.await { !it.sending }
            watcher.cancel()
            assertEquals(["", "H", "Hi", ""], inputs.map { it.first })
            val [typedH, typedHi] = inputs.drop(1).take(2).map { it.second }
            assertTrue(typedH.elapsedNow() - typedHi.elapsedNow() >= TYPING * 0.8, "typed too fast")
            assertTrue(requestedAt.single().elapsedNow() <= typedHi.elapsedNow(), "requested before the text was typed")
        }
    }

    @Test
    suspend fun `send --instant puts the whole text in at once`() = coroutineScope {
        ControlFixture(typingSpeed = TYPING, reply = { flowOf(ChatEvent.Done) }).use { slow ->
            val inputs = CopyOnWriteArrayList<String>()
            val watcher = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                slow.viewModel.state.map { it.input }.distinctUntilChanged().collect { inputs += it }
            }

            assertIs<ControlReply.Ok>(slow.control.execute("""send --instant "Hello there""""))

            slow.viewModel.state.await { !it.sending }
            watcher.cancel()
            assertEquals(["", "Hello there", ""], inputs.toList())
            assertEquals(["Hello there"], slow.api.requests.map { it.message })
        }
    }

    @Test
    suspend fun `text already in the input is replaced, not appended to`() {
        fixture.viewModel.setInput("draft")

        assertIs<ControlReply.Ok>(fixture.control.execute("""send "Hi""""))

        assertEquals(["Hi"], fixture.api.requests.map { it.message })
        assertEquals("", fixture.viewModel.state.value.input)
    }

    @Test
    suspend fun `nothing is typed when Send is not usable`() {
        assertIs<ControlReply.Ok>(fixture.control.execute("""send "First""""))
        fixture.viewModel.setInput("draft")

        val result = fixture.control.execute("""send "Again"""")

        val refused = assertIs<ControlReply.Refused>(result)
        assertTrue("streaming" in refused.body, refused.body)
        assertEquals("draft", fixture.viewModel.state.value.input)
        assertEquals(["First"], fixture.api.requests.map { it.message })
    }

    @Test
    suspend fun `the input is highlighted while typing, then Send until the click`() = coroutineScope {
        ControlFixture(typingSpeed = TYPING, highlight = TYPING).use { slow ->
            val pressed = CopyOnWriteArrayList<PressTarget?>()
            val watcher = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                slow.feedback.pressed.collect { pressed += it }
            }

            assertIs<ControlReply.Ok>(slow.control.execute("""send "Hi""""))

            watcher.cancel()
            val expected: List<PressTarget?> = [null, PressTarget.Input, null, PressTarget.Send, null]
            assertEquals(expected, pressed.toList())
        }
    }

    @Test
    suspend fun `typing-speed 0ms types without a pause`() {
        ControlFixture(typingSpeed = 100.milliseconds).use { slow ->
            assertIs<ControlReply.Ok>(slow.control.execute("typing-speed 0ms"))
            val started = TimeSource.Monotonic.markNow()

            assertIs<ControlReply.Ok>(slow.control.execute("""send "Twenty characters long""""))

            assertTrue(started.elapsedNow() < 1_000.milliseconds, "took ${started.elapsedNow()}")
        }
    }

    @Test
    suspend fun `a bad typing speed is invalid, names the value and keeps the speed`() {
        ControlFixture(typingSpeed = TYPING).use { slow ->
            val result = slow.control.execute("typing-speed fast")

            val invalid = assertIs<ControlReply.Invalid>(result)
            assertTrue("fast" in invalid.body, invalid.body)
            val started = TimeSource.Monotonic.markNow()
            assertIs<ControlReply.Ok>(slow.control.execute("""send "Hi""""))
            assertTrue(started.elapsedNow() >= TYPING * 2 * 0.8, "typed in ${started.elapsedNow()}")
        }
    }

    @Test
    suspend fun `highlight sets how long a control is highlighted before its action runs`() = coroutineScope {
        val decidedAt = CopyOnWriteArrayList<TimeMark>()
        val decisionReply = { _: Any ->
            decidedAt += TimeSource.Monotonic.markNow()
            flowOf(ChatEvent.Done)
        }
        ControlFixture(decisionReply = decisionReply).use { slow ->
            slow.events.send(PAYOUT)
            slow.viewModel.send("Pay 4711")
            assertIs<ControlReply.Ok>(slow.control.execute("highlight 500ms"))

            val approve = async { slow.control.execute("approve") }
            slow.feedback.pressed.await { it == PressTarget.Approve }
            val pressedAt = TimeSource.Monotonic.markNow()

            assertIs<ControlReply.Ok>(approve.await())
            assertTrue(pressedAt.elapsedNow() - decidedAt.single().elapsedNow() >= 400.milliseconds, "too short")
        }
    }

    private companion object {
        val TYPING = 100.milliseconds
    }
}
