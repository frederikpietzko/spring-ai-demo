package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimJson
import dev.frederikpietzko.springaidemo.backend.testsupport.claimReportJson
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.session.CreateSessionRequest
import org.springframework.ai.session.SessionAttributes
import org.springframework.ai.session.SessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A prepare reads the conversation that `session-memory` stored: the intake prompt is built from its transcript, as
 * plain text messages. Observed in the prompt the model receives for the prepare (the last one). Ids are unique per
 * test.
 */
@IntegrationTest
class PrepareFromStoredConversationTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var sessionService: SessionService

    @Test
    fun `tool calls of the claimant chat do not reach the intake prompt`() {
        val id = "stored-tool-call"
        tell(id, GNOME, reply = "Noted.")
        fake.requestTool("conversation_search", """{"query":"gnome"}""")
        tell(id, "what did I tell you?", reply = "You told me about your gnome.")
        fake.replyJson(claimReportJson())

        val events = prepare(id)

        val messages = fake.prompts.last().instructions
        assertIs<SystemMessage>(messages.first())
        assertEquals(
            [
                MessageType.USER to GNOME,
                MessageType.ASSISTANT to "Noted.",
                MessageType.USER to "what did I tell you?",
                MessageType.ASSISTANT to "You told me about your gnome.",
            ],
            messages.drop(1).dropLast(1).map { it.messageType to it.text },
        )
        assertTrue(messages.none { it is AssistantMessage && it.hasToolCalls() }, messages.toString())
        assertIs<UserMessage>(messages.last())
        assertIs<ChatEvent.ClaimCard>(events.first())
        assertEquals(ChatEvent.Done, events.last())
    }

    @Test
    fun `the intake prompt holds what the claimant typed, not the retrieved clauses`() {
        val id = "stored-typed-text"
        tell(id, CLASS_5_QUESTION, reply = "Not covered.")
        val stored = sessionService.getMessages(id).filter { it.messageType == MessageType.USER }
        fake.replyJson(claimReportJson())

        val _ = prepare(id)

        assertTrue(stored.single().text.orEmpty().contains(ACT_OF_KAIJU_LABEL), stored.toString())
        val texts = fake.prompts.last().instructions.map { it.text.orEmpty() }
        assertTrue(CLASS_5_QUESTION in texts, texts.toString())
        assertTrue(texts.none { it.contains(ACT_OF_KAIJU_LABEL) }, texts.toString())
    }

    @Test
    fun `turns that compaction removed from the chat prompt still reach the intake prompt`() {
        val id = "stored-compacted"
        seedTurns(id, MAX_TURNS)
        tell(id, "turn eleven", reply = "Noted 11.")
        tell(id, "turn twelve", reply = "Noted 12.")
        val compacted = sessionService.getActiveMessages(id).size < sessionService.getMessages(id).size
        fake.replyJson(claimReportJson())

        val _ = prepare(id)

        assertTrue(compacted)
        val texts = fake.prompts.last().instructions.map { it.text.orEmpty() }
        assertTrue(GNOME in texts && "turn twelve" in texts, texts.toString())
    }

    @Test
    fun `a prepare leaves the stored conversation unchanged`() {
        val id = "stored-unchanged"
        tell(id, GNOME, reply = "Noted.")
        val before = sessionService.getMessages(id).map { it.messageType to it.text }
        fake.replyJson(claimReportJson(policyNumber = null))

        val _ = prepare(id)

        assertEquals(before, sessionService.getMessages(id).map { it.messageType to it.text })
    }

    @Test
    fun `a prepare of an unknown conversation asks for details and no model`() {
        val events = prepare("stored-never-used")

        val question = assertIs<ChatEvent.TextDelta>(events.first()).text
        assertTrue(question.contains("policy number"), question)
        assertEquals([ChatEvent.Done], events.drop(1))
        assertEquals([], fake.prompts)
    }

    @Test
    fun `a prepare reads only its own conversation`() {
        tell("stored-own", "A kaiju flattened my shed", reply = "Noted.")
        tell("stored-other", GNOME, reply = "Noted.")
        fake.replyJson(claimReportJson())

        val _ = prepare("stored-own")

        val texts = fake.prompts.last().instructions.map { it.text.orEmpty() }
        assertTrue("A kaiju flattened my shed" in texts, texts.toString())
        assertTrue(texts.none { it.contains(GNOME) }, texts.toString())
    }

    /** One claimant turn in conversation [id], answered with [reply] (after any tool call queued before). */
    private fun tell(id: String, message: String, reply: String) {
        fake.replyChunks(reply)
        val _ = client.postChat(claimantJson(message, id))
    }

    private fun prepare(id: String): List<ChatEvent> =
        sseEvents(client.postChat(claimJson(ChatAction.PREPARE_CLAIM, id)).responseBody ?: byteArrayOf())

    /** A claimant conversation of [turns] stored user and assistant pairs, the first about the gnome. */
    private fun seedTurns(id: String, turns: Int) {
        val _ = sessionService.findOrCreate(
            CreateSessionRequest.builder()
                .id(id)
                .metadata(mapOf(SessionAttributes.AGENT_NAME to CLAIMANT_AGENT, TITLE to GNOME))
                .build(),
        )
        (1..turns).forEach {
            sessionService.appendMessage(id, UserMessage(if (it == 1) GNOME else "seeded turn $it"))
            sessionService.appendMessage(id, AssistantMessage("Noted seeded turn $it."))
        }
    }

    private companion object {
        const val GNOME = "my garden gnome is gone"
        const val CLASS_5_QUESTION = "Is my car covered after a class 5 kaiju event?"
        const val ACT_OF_KAIJU_LABEL = "[exclusions.md - Act of Kaiju exclusion]"
    }
}
