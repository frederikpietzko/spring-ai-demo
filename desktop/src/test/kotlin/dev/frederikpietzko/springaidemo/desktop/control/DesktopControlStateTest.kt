package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ClaimCardItem
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture.Companion.PAYOUT
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture.Companion.SLOW
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopControlStateTest {
    private val fixture = ControlFixture()
    private val control = fixture.control
    private val viewModel = fixture.viewModel

    @AfterEach
    fun close() = fixture.close()

    @Test
    suspend fun `state lists the messages after an answer and Send is enabled`() {
        fixture.events.send(ChatEvent.TextDelta("Hello"))
        fixture.events.send(ChatEvent.Done)
        viewModel.send("Hi")

        val state = state()

        val messages = state.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(
            [("USER" to "Hi"), ("ASSISTANT" to "Hello")],
            messages.map { it.string("author") to it.string("text") },
        )
        assertEquals("false", state.string("sending"))
        assertEquals("true", state.enabled("send"))
        assertEquals("CLAIMANT", state.string("persona"))
        assertEquals(viewModel.state.value.conversationId, state.string("conversationId"))
    }

    @Test
    suspend fun `state while streaming shows sending and disables send, prepare and new conversation`() {
        assertIs<ControlReply.Ok>(control.execute("send Hi"))

        val state = state()

        assertEquals("true", state.string("sending"))
        assertEquals(
            ["false", "false", "false"],
            [state.enabled("send"), state.enabled("prepareClaim"), state.enabled("newConversation")],
        )
    }

    @Test
    suspend fun `state shows the newest claim card and confirm is enabled on it`() {
        fixture.events.send(CARD)
        fixture.events.send(ChatEvent.Done)
        viewModel.prepareClaim()

        val state = state()

        val card = state.getValue("messages").jsonArray.last().jsonObject.getValue("claimCard").jsonObject
        assertEquals("KP-1001", card.string("policyNumber"))
        assertEquals(["true", "false"], [state.enabled("confirmClaim"), state.enabled("approve")])
    }

    @Test
    suspend fun `state shows the pending confirmation and approve and deny are enabled`() {
        fixture.events.send(PAYOUT)
        viewModel.send("Pay 4711")

        val state = state()

        assertEquals("issuePayout", state.getValue("pendingConfirmation").jsonObject.string("toolName"))
        assertEquals(
            ["true", "true", "false"],
            [state.enabled("approve"), state.enabled("deny"), state.enabled("send")],
        )
    }

    @Test
    suspend fun `state lists every enablement rule`() {
        val rules: Set<String> = [
            "send",
            "prepareClaim",
            "confirmClaim",
            "approve",
            "deny",
            "newConversation",
            "selectPersona",
            "openConversation",
        ]

        val enabled = state().getValue("enabled").jsonObject

        assertEquals(rules, enabled.keys)
    }

    @Test
    suspend fun `state shows the conversations, the todos, the input and nothing pressed`() {
        viewModel.refreshConversations()
        viewModel.setInput("dra")

        val state = state()

        assertEquals("dra", state.string("input"))
        assertEquals(JsonNull, state["pressed"])
        assertEquals(["Shed", "Gnome"], state.getValue("conversations").jsonArray.map { it.jsonObject.string("title") })
        assertEquals(0, state.getValue("todos").jsonArray.size)
    }

    @Test
    suspend fun `state names the control being pressed`() = coroutineScope {
        ControlFixture(highlight = SLOW).use { slow ->
            val press = async { slow.control.execute("new-conversation") }
            slow.feedback.pressed.await { it != null }

            val state = slow.state()

            assertEquals("newConversation", state.string("pressed"))
            assertIs<ControlReply.Ok>(press.await())
        }
    }

    @Test
    suspend fun `state answers while send is still typing and shows the text typed so far`() = coroutineScope {
        ControlFixture(typingSpeed = SLOW).use { slow ->
            val send = async { slow.control.execute("""send "Hello"""") }
            slow.viewModel.state.await { it.input.isNotEmpty() }

            val input = slow.state().string("input")

            assertTrue(input.isNotEmpty() && "Hello".startsWith(input) && input != "Hello", input)
            send.cancel()
        }
    }

    @Test
    suspend fun `reply answers with the text of the newest assistant message`() {
        fixture.events.send(ChatEvent.TextDelta("Paid"))
        fixture.events.send(ChatEvent.Done)
        viewModel.send("Pay")

        assertEquals(ControlReply.Ok("Paid"), control.execute("reply"))
    }

    @Test
    suspend fun `reply is refused when there is no assistant message yet`() {
        assertIs<ControlReply.Refused>(control.execute("reply"))
    }

    private suspend fun state(): Map<String, JsonElement> = fixture.state()

    private suspend fun ControlFixture.state(): Map<String, JsonElement> {
        val reply = assertIs<ControlReply.Ok>(control.execute("state"))
        assertTrue(reply.json, "state is not marked as JSON")
        return Json.parseToJsonElement(reply.body).jsonObject
    }

    private fun Map<String, JsonElement>.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun Map<String, JsonElement>.enabled(key: String): String =
        getValue("enabled").jsonObject.getValue(key).jsonPrimitive.content

    private companion object {
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
