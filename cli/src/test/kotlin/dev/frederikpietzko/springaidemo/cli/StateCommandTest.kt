package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.cli.fakes.FakeAnswer
import dev.frederikpietzko.springaidemo.cli.fakes.FakeControlEndpoint
import dev.frederikpietzko.springaidemo.cli.fakes.RecordingConsole
import dev.frederikpietzko.springaidemo.controlprotocol.EnabledControls
import dev.frederikpietzko.springaidemo.controlprotocol.MessageSnapshot
import dev.frederikpietzko.springaidemo.controlprotocol.StateSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StateCommandTest {
    private var snapshot = aSnapshot()
    private val endpoint = FakeControlEndpoint { line ->
        if (line == "state") FakeAnswer(200, ChatJson.encodeToString(snapshot)) else FakeAnswer(400, "unexpected")
    }
    private val console = RecordingConsole()

    @AfterEach
    fun close() = endpoint.close()

    private fun run(vararg args: String): Int =
        kaiju(args.toList(), mapOf("KAIJU_CONTROL_URL" to endpoint.url), console)

    @Test
    fun `the summary shows persona, streaming and the messages`() {
        val exit = run("state")

        assertEquals(0, exit)
        val lines = console.out.lines()
        assertTrue(
            lines.containsAll(["persona: CLAIMANT", "streaming: no", "user: Hi", "assistant: Hello"]),
            console.out,
        )
    }

    @Test
    fun `the summary shows a pending confirmation and the enabled decision controls`() {
        snapshot = aSnapshot().copy(
            pendingConfirmation = ChatEvent.ConfirmationRequest("c1", "issuePayout", """{"claimNumber":4711}"""),
            enabled = noneEnabled().copy(approve = true, deny = true),
        )

        val _ = run("state")

        val lines = console.out.lines()
        assertTrue("""confirmation pending: issuePayout {"claimNumber":4711}""" in lines, console.out)
        val enabled = lines.single { it.startsWith("enabled:") }
        assertTrue("approve" in enabled && "deny" in enabled, enabled)
    }

    @Test
    fun `the summary shows input, highlighted control, errors and cards`() {
        snapshot = aSnapshot().copy(
            input = "dra",
            pressed = "send",
            messages = [
                MessageSnapshot("ASSISTANT", "partial", "The claims desk is unavailable.", null, null),
                MessageSnapshot("ASSISTANT", "", null, aClaimCard(), null),
            ],
        )

        val _ = run("state")

        val out = console.out
        assertTrue("input: dra" in out.lines(), out)
        assertTrue("pressed: send" in out.lines(), out)
        assertTrue("The claims desk is unavailable." in out, out)
        assertTrue("KP-1001" in out, out)
    }

    @Test
    fun `json prints the endpoint's answer unchanged`() {
        val exit = run("state", "--json")

        assertEquals(0, exit)
        assertEquals(ChatJson.encodeToString(snapshot), console.out.trim())
        val _ = assertIs<JsonObject>(Json.parseToJsonElement(console.out))
    }

    private fun aSnapshot() = StateSnapshot(
        persona = Persona.CLAIMANT,
        conversationId = "conv-1",
        sending = false,
        input = "",
        pressed = null,
        messages = [
            MessageSnapshot("USER", "Hi", null, null, null),
            MessageSnapshot("ASSISTANT", "Hello", null, null, null),
        ],
        pendingConfirmation = null,
        conversations = [],
        todos = [],
        enabled = noneEnabled().copy(send = true),
    )

    private fun noneEnabled() = EnabledControls(
        send = false,
        prepareClaim = false,
        confirmClaim = false,
        approve = false,
        deny = false,
        newConversation = false,
        selectPersona = false,
        openConversation = false,
    )

    private fun aClaimCard() = ChatEvent.ClaimCard(
        policyNumber = "KP-1001",
        district = "HARBOUR",
        incidentAt = "2026-05-01T10:00:00Z",
        kaijuClass = 3,
        description = "Tail swipe",
        damageItems = [],
    )
}
