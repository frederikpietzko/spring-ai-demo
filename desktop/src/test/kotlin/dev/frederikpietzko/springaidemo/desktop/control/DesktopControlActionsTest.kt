package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ClaimCardItem
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture.Companion.GNOME
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture.Companion.PAYOUT
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture.Companion.SLOW
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DesktopControlActionsTest {
    private val fixture = ControlFixture()
    private val control = fixture.control
    private val viewModel = fixture.viewModel
    private val api = fixture.api

    @AfterEach
    fun close() = fixture.close()

    @Test
    suspend fun `persona selects the persona and the next message is sent with it`() {
        val result = control.execute("persona adjuster")

        assertIs<ControlReply.Ok>(result)
        assertEquals(Persona.ADJUSTER, viewModel.state.value.persona)
        fixture.events.send(ChatEvent.Done)
        assertIs<ControlReply.Ok>(control.execute("send Hi"))
        assertEquals([Persona.ADJUSTER], api.requests.map { it.persona })
    }

    @Test
    suspend fun `open shows the stored conversation's transcript and continues it`() {
        viewModel.refreshConversations()

        val result = control.execute("""open "Gnome"""")

        assertIs<ControlReply.Ok>(result)
        val state = viewModel.state.value
        assertEquals(GNOME.id, state.conversationId)
        assertEquals(["my garden gnome is gone", "Noted."], state.messages.map { it.text })
    }

    @Test
    suspend fun `new-conversation clears the conversation`() {
        fixture.events.send(ChatEvent.Done)
        viewModel.send("Hi")

        val result = control.execute("new-conversation")

        assertIs<ControlReply.Ok>(result)
        assertEquals([], viewModel.state.value.messages)
    }

    @Test
    suspend fun `prepare-claim sends the prepare action and returns before the answer ends`() {
        val result = control.execute("prepare-claim")

        assertIs<ControlReply.Ok>(result)
        assertTrue(viewModel.state.value.sending)
        assertEquals([ChatAction.PREPARE_CLAIM], api.requests.map { it.action })
    }

    @Test
    suspend fun `confirm-claim sends the confirm action when the newest message holds a card`() {
        fixture.events.send(CARD)
        fixture.events.send(ChatEvent.Done)
        viewModel.prepareClaim()

        val result = control.execute("confirm-claim")

        assertIs<ControlReply.Ok>(result)
        assertEquals([ChatAction.PREPARE_CLAIM, ChatAction.CONFIRM_CLAIM], api.requests.map { it.action })
    }

    @Test
    suspend fun `approve and deny send the decision on the pending confirmation`() {
        fixture.events.send(PAYOUT)
        viewModel.send("Pay 4711")

        val result = control.execute("approve")

        assertIs<ControlReply.Ok>(result)
        viewModel.state.await { !it.sending && it.messages.last().text.contains("Paid") }
        assertEquals([ConfirmationDecision(PAYOUT.confirmationId, approved = true)], api.decisions)
    }

    @Test
    suspend fun `deny sends the denying decision`() {
        fixture.events.send(PAYOUT)
        viewModel.send("Pay 4711")

        assertIs<ControlReply.Ok>(control.execute("deny"))

        assertEquals([ConfirmationDecision(PAYOUT.confirmationId, approved = false)], api.decisions)
    }

    @Test
    suspend fun `the control is highlighted for the highlight time before the click`() = coroutineScope {
        ControlFixture(highlight = SLOW).use { slow ->
            slow.events.send(ChatEvent.Done)
            slow.viewModel.send("Hi")

            val command = async { slow.control.execute("new-conversation") }

            slow.feedback.pressed.await { it == PressTarget.NewConversation }
            assertEquals(2, slow.viewModel.state.value.messages.size, "cleared before the highlight ended")
            assertIs<ControlReply.Ok>(command.await())
            assertEquals(null, slow.feedback.pressed.value)
            assertEquals([], slow.viewModel.state.value.messages)
        }
    }

    @Test
    suspend fun `the persona switch is highlighted before it switches`() = coroutineScope {
        ControlFixture(highlight = SLOW).use { slow ->
            val command = async { slow.control.execute("persona adjuster") }

            slow.feedback.pressed.await { it == PressTarget.Persona }
            assertEquals(Persona.CLAIMANT, slow.viewModel.state.value.persona)
            assertIs<ControlReply.Ok>(command.await())
            assertEquals(Persona.ADJUSTER, slow.viewModel.state.value.persona)
        }
    }

    @Test
    suspend fun `the pressed conversation row is highlighted`() = coroutineScope {
        ControlFixture(highlight = SLOW).use { slow ->
            slow.viewModel.refreshConversations()

            val command = async { slow.control.execute("open Gnome") }

            slow.feedback.pressed.await { it == PressTarget.Conversation(GNOME.id) }
            assertIs<ControlReply.Ok>(command.await())
        }
    }

    @Test
    suspend fun `an action is refused while an answer streams and nothing reaches the backend`() {
        assertIs<ControlReply.Ok>(control.execute("prepare-claim"))

        val result = control.execute("prepare-claim")

        val refused = assertIs<ControlReply.Refused>(result)
        assertTrue("streaming" in refused.body, refused.body)
        assertEquals(1, api.requests.size)
    }

    @Test
    suspend fun `approve without a confirmation is refused and no decision is sent`() {
        val result = control.execute("approve")

        val refused = assertIs<ControlReply.Refused>(result)
        assertTrue("nothing to approve" in refused.body, refused.body)
        assertEquals([], api.decisions)
    }

    @Test
    suspend fun `confirm without a claim card is refused and no request is sent`() {
        val result = control.execute("confirm-claim")

        assertIs<ControlReply.Refused>(result)
        assertEquals([], api.requests)
    }

    @Test
    suspend fun `actions other than approve and deny are refused while a confirmation is pending`() {
        fixture.events.send(PAYOUT)
        viewModel.send("Pay 4711")

        val results = ["new-conversation", "persona adjuster", "prepare-claim", "send Hi"].map { control.execute(it) }

        assertTrue(results.all { it is ControlReply.Refused && "issuePayout" in it.body }, results.toString())
        assertEquals(1, api.requests.size)
    }

    @Test
    suspend fun `an unknown conversation is refused by its title and the conversation shown is unchanged`() {
        viewModel.refreshConversations()
        val before = viewModel.state.value.conversationId

        val result = control.execute("""open "Nonexistent"""")

        val refused = assertIs<ControlReply.Refused>(result)
        assertTrue("Nonexistent" in refused.body, refused.body)
        assertEquals(before, viewModel.state.value.conversationId)
    }

    @Test
    suspend fun `nothing is highlighted when an action is refused`() = coroutineScope {
        ControlFixture(highlight = SLOW).use { slow ->
            val pressed = mutableListOf<PressTarget?>()
            val watcher = async(start = CoroutineStart.UNDISPATCHED) {
                slow.feedback.pressed.collect { pressed += it }
            }

            assertIs<ControlReply.Refused>(slow.control.execute("approve"))

            watcher.cancel()
            val expected: List<PressTarget?> = [null]
            assertEquals(expected, pressed.toList())
        }
    }

    @Test
    suspend fun `a second action waits until the first one has pressed its control`() = coroutineScope {
        val reply = { _: Any -> flowOf(ChatEvent.TextDelta("Hello"), ChatEvent.Done) }
        ControlFixture(typingSpeed = TYPING, reply = reply).use { slow ->
            val send = async { slow.control.execute("""send "Hello"""") }
            slow.viewModel.state.await { it.input.isNotEmpty() }

            val newConversation = slow.control.execute("new-conversation")

            assertIs<ControlReply.Ok>(send.await())
            assertIs<ControlReply.Ok>(newConversation)
            assertEquals(["Hello"], slow.api.requests.map { it.message })
            assertEquals([], slow.viewModel.state.value.messages)
        }
    }

    @Test
    suspend fun `an invalid line is answered as invalid and runs nothing`() {
        val result = control.execute("dance")

        assertIs<ControlReply.Invalid>(result)
        assertEquals([], api.requests)
    }

    private companion object {
        val TYPING = 50.milliseconds

        val CARD = ChatEvent.ClaimCard(
            policyNumber = "KP-1001",
            district = "HARBOUR",
            incidentAt = "2026-11-19T15:00:00Z",
            kaijuClass = 3,
            description = "Kaiju stepped on the car",
            damageItems = [ClaimCardItem("VEHICLE", "Car roof", 800000)],
        )
    }
}
