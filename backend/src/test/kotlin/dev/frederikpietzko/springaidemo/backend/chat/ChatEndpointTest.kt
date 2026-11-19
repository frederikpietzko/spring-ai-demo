package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@IntegrationTest
class ChatEndpointTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var auditTrail: AuditTrail

    @Autowired
    private lateinit var controller: ChatController

    @Test
    fun `streams the reply chunks in order and then done`() {
        fake.replyChunks("Hel", "lo")

        val result = postChat(CLAIMANT_HI)

        assertEquals(HttpStatus.OK, result.status)
        assertTrue(MediaType.TEXT_EVENT_STREAM.isCompatibleWith(result.responseHeaders.contentType))
        assertEquals(
            [ChatEvent.TextDelta("Hel"), ChatEvent.TextDelta("lo"), ChatEvent.Done],
            sseEvents(result.responseBody ?: byteArrayOf()),
        )
    }

    @Test
    fun `non-ASCII text survives the stream`() {
        fake.replyChunks("Größe ", "🦖")

        val result = postChat("""{"persona":"CLAIMANT","message":"Wie groß?"}""")

        assertEquals(
            [ChatEvent.TextDelta("Größe "), ChatEvent.TextDelta("🦖"), ChatEvent.Done],
            sseEvents(result.responseBody ?: byteArrayOf()),
        )
    }

    @Test
    fun `a failure after some chunks ends the stream with one generic error and no done`() {
        fake.replyChunksThenFail("provider exploded", "Hel")

        val events = sseEvents(postChat(CLAIMANT_HI).responseBody ?: byteArrayOf())

        assertEquals([ChatEvent.TextDelta("Hel"), ChatEvent.Error(GENERIC_ERROR)], events)
    }

    @Test
    fun `a failure before the first chunk yields only one error`() {
        fake.replyChunksThenFail("early")

        val events = sseEvents(postChat(CLAIMANT_HI).responseBody ?: byteArrayOf())

        assertEquals([ChatEvent.Error(GENERIC_ERROR)], events)
    }

    @Test
    fun `a confirmation request is the last event, with no done or error after it`() {
        fake.requestTool("issuePayout", """{"claimNumber":4711}""")

        val result = postChat("""{"persona":"ADJUSTER","message":"Pay 4711"}""")

        assertIs<ChatEvent.ConfirmationRequest>(sseEvents(result.responseBody ?: byteArrayOf()).single())
    }

    @Test
    suspend fun `an Error from the model is rethrown instead of becoming an error event`() {
        // Over HTTP an Error can only break the connection, so the controller's event flow is collected directly. Not a
        // StackOverflowError: Reactor throws JVM-fatal errors on its scheduler thread, and the stream never ends.
        fake.replyChunksThenFail(NotImplementedError("simulated"), "Hel")

        assertFailsWith<NotImplementedError> { controller.chat(ChatRequest(Persona.CLAIMANT, "Hi")).toList() }
    }

    @Test
    fun `a claimant request is answered by the claimant agent, without tools`() {
        fake.replyChunks("ok")

        postChat(CLAIMANT_HI)

        val prompt = fake.prompts.last()
        val system = prompt.instructions.filterIsInstance<SystemMessage>().single().text.orEmpty()
        assertTrue(system.contains("claims desk assistant"), system)
        assertEquals([], assertIs<ToolCallingChatOptions>(prompt.options).toolCallbacks.orEmpty())
    }

    @Test
    fun `an adjuster request is answered by the adjuster agent and ends with a terminal event`() {
        fake.replyChunks("ok")

        val events = sseEvents(postChat("""{"persona":"ADJUSTER","message":"Hi"}""").responseBody ?: byteArrayOf())

        val system = fake.prompts.last().instructions.filterIsInstance<SystemMessage>().single().text.orEmpty()
        assertTrue(system.contains("adjuster assistant"), system)
        assertEquals([ChatEvent.TextDelta("ok"), ChatEvent.Done], events)
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"persona":"AUDITOR","message":"Hi"}""", "not json"])
    fun `an invalid request is rejected with 400 before any model call`(body: String) {
        val result = postChat(body)

        assertEquals(HttpStatus.BAD_REQUEST, result.status)
        assertTrue(fake.prompts.isEmpty(), fake.prompts.toString())
    }

    @Test
    fun `the claimant instructions reach the model as the system message`() {
        fake.replyChunks("ok")

        postChat(CLAIMANT_HI)

        val system = fake.prompts.last().instructions.filterIsInstance<SystemMessage>().single()
        assertTrue(system.text.orEmpty().contains("claims desk assistant"), system.text)
    }

    @Test
    fun `the claimant instructions allow explaining the policy wording but not looking up the claimant's policy`() {
        fake.replyChunks("ok")

        postChat(CLAIMANT_HI)

        val system = fake.prompts.last().instructions.filterIsInstance<SystemMessage>().single().text.orEmpty()
        assertTrue(system.contains("policy wording"), system)
        assertTrue(system.contains("cannot look up the claimant's own policy"), system)
    }

    @Test
    fun `the user text reaches the model unchanged, braces included`() {
        fake.replyChunks("ok")

        postChat("""{"persona":"CLAIMANT","message":"see {claimId} and {}"}""")

        // Whether policy wording is added around it depends on what the test embedding model retrieves.
        val userText = fake.prompts.last().userMessage.text.orEmpty()
        assertTrue(userText.contains("see {claimId} and {}"), userText)
    }

    @Test
    fun `the user text with braces survives the retrieval template unchanged`() {
        fake.replyChunks("ok")

        postChat("""{"persona":"CLAIMANT","message":"{claimId} class 5 kaiju event {}"}""")

        val userText = fake.prompts.last().userMessage.text.orEmpty()
        assertTrue(userText.contains("{claimId} class 5 kaiju event {}"), userText)
        assertTrue(userText.contains(ACT_OF_KAIJU_LABEL), userText)
    }

    @Test
    fun `a second request does not see the first message`() {
        fake.replyChunks("one")
        fake.replyChunks("two")

        postChat("""{"persona":"CLAIMANT","message":"first question"}""")
        postChat("""{"persona":"CLAIMANT","message":"second question"}""")

        val texts = fake.prompts.last().instructions.map { it.text.orEmpty() }
        assertTrue(texts.any { it.contains("second question") }, texts.toString())
        assertTrue(texts.none { it.contains("first question") }, texts.toString())
    }

    @Test
    fun `a successful stream is audited without changing the events`() {
        val message = "audit probe: successful stream"
        fake.replyChunks("Hel", "lo")

        val events = sseEvents(postChat(claimantRequest(message)).responseBody ?: byteArrayOf())

        assertEquals([ChatEvent.TextDelta("Hel"), ChatEvent.TextDelta("lo"), ChatEvent.Done], events)
        val (outcome, replyChars) = auditTrail.entries.single { it.userMessage == message }
        assertEquals(AuditOutcome.SUCCESS, outcome)
        assertEquals(5, replyChars)
    }

    @Test
    fun `the audit records the claimant's own words, not the retrieval template`() {
        val message = "audit probe class 5 kaiju event coverage"
        fake.replyChunks("ok")

        postChat(claimantRequest(message))

        assertTrue(auditTrail.entries.any { it.userMessage == message }, auditTrail.entries.toString())
        assertTrue(auditTrail.entries.none { it.userMessage.contains("[exclusions.md") }, auditTrail.entries.toString())
    }

    @Test
    fun `a failed stream is audited as a failure`() {
        val message = "audit probe: failed stream"
        fake.replyChunksThenFail("boom", "Hel")

        val events = sseEvents(postChat(claimantRequest(message)).responseBody ?: byteArrayOf())

        assertEquals([ChatEvent.TextDelta("Hel"), ChatEvent.Error(GENERIC_ERROR)], events)
        assertEquals(AuditOutcome.FAILURE, auditTrail.entries.single { it.userMessage == message }.outcome)
    }

    @Test
    fun `a coverage question is answered from the policy wording`() {
        fake.replyChunks("Not ", "covered")

        val events = sseEvents(postChat(claimantRequest(CLASS_5_QUESTION)).responseBody ?: byteArrayOf())

        assertEquals([ChatEvent.TextDelta("Not "), ChatEvent.TextDelta("covered"), ChatEvent.Done], events)
        val userText = fake.prompts.last().userMessage.text.orEmpty()
        assertTrue(userText.contains("$ACT_OF_KAIJU_LABEL\n$ACT_OF_KAIJU_FIRST_SENTENCE"), userText)
        assertTrue(userText.contains(CLASS_5_QUESTION), userText)
    }

    private fun claimantRequest(message: String) = """{"persona":"CLAIMANT","message":"$message"}"""

    private fun postChat(json: String): EntityExchangeResult<ByteArray> =
        client.post().uri("/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body(json)
            .exchange()
            .returnResult(ByteArray::class.java)

    private companion object {
        const val CLAIMANT_HI = """{"persona":"CLAIMANT","message":"Hi"}"""
        const val GENERIC_ERROR = "The claims desk is unavailable."
        const val CLASS_5_QUESTION = "Is my car covered after a class 5 kaiju event?"
        const val ACT_OF_KAIJU_LABEL = "[exclusions.md - Act of Kaiju exclusion]"
        const val ACT_OF_KAIJU_FIRST_SENTENCE =
            "The Act of Kaiju exclusion: damage from a class 5 kaiju event is not covered."
    }
}
