package dev.frederikpietzko.springaidemo.backend.guardrail

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeDecisionModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import kotlinx.serialization.builtins.ListSerializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The output half of the claimant chat's guardrail over `POST /chat`: the reply streams unchanged and is checked once
 * it has completed. Conversation ids are unique per test (shared database).
 */
@IntegrationTest
class GuardrailOutputTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var decisionModel: FakeDecisionModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Test
    fun `a flagged reply gets a notice after its chunks`() {
        decisionModel.phrase = "is approved"
        fake.replyChunks("Your payout of ", "1000 is approved.")

        val events = post(claimantJson(QUESTION, "guardrail-output-flagged"))

        assertEquals(
            [
                ChatEvent.TextDelta("Your payout of "),
                ChatEvent.TextDelta("1000 is approved."),
                ChatEvent.TextDelta(GuardrailAdvisor.NOTICE),
                ChatEvent.Done,
            ],
            events,
        )
    }

    @Test
    fun `a clean reply is unchanged`() {
        fake.replyChunks("Hel", "lo")

        val events = post(claimantJson(QUESTION, "guardrail-output-clean"))

        assertEquals([ChatEvent.TextDelta("Hel"), ChatEvent.TextDelta("lo"), ChatEvent.Done], events)
        assertEquals([QUESTION, "Hello"], decisionModel.inputs)
    }

    @Test
    fun `a failing stream is not checked`() {
        fake.replyChunksThenFail("provider exploded", "Hel")

        val events = post(claimantJson(QUESTION, "guardrail-output-failing"))

        assertEquals([ChatEvent.TextDelta("Hel"), ChatEvent.Error(GENERIC_ERROR)], events)
        assertEquals([QUESTION], decisionModel.inputs)
    }

    @Test
    fun `a reply without text is not checked`() {
        fake.requestTool("issuePayout", """{"claimNumber":4711}""")

        val events = post(claimantJson(QUESTION, "guardrail-output-no-text"))

        assertTrue(events.none { it is ChatEvent.TextDelta }, events.toString())
        assertEquals([QUESTION], decisionModel.inputs)
        assertEquals([], coreTools.calls)
    }

    @Test
    fun `the stored reply stays as the model wrote it`() {
        decisionModel.phrase = "is approved"
        fake.replyChunks("Your payout of ", "1000 is approved.")

        val _ = post(claimantJson(QUESTION, "guardrail-output-stored"))

        val stored = transcript("guardrail-output-stored").single { it.role == MessageRole.ASSISTANT }.text
        assertEquals("Your payout of 1000 is approved.", stored)
    }

    private fun post(json: String): List<ChatEvent> = sseEvents(client.postChat(json).responseBody ?: byteArrayOf())

    private fun transcript(id: String): List<ConversationMessage> {
        val body = client.get().uri("/conversations/{id}/messages", id).exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody
            .orEmpty()
        return ChatJson.decodeFromString(ListSerializer(ConversationMessage.serializer()), body)
    }

    private companion object {
        const val QUESTION = "When will my claim be paid?"
        const val GENERIC_ERROR = "The claims desk is unavailable."
    }
}
