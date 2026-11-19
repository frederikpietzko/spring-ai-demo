package dev.frederikpietzko.springaidemo.backend.observability

import dev.frederikpietzko.springaidemo.backend.rag.CoverageQuestions
import dev.frederikpietzko.springaidemo.backend.testsupport.ClaimLookupTool
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.RecordingObservationHandler
import io.micrometer.observation.Observation
import org.awaitility.Awaitility.await
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.observation.ChatClientObservationContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Spring AI's built-in observations of a chat call, recorded in the shared test context (ADR-0010). */
@IntegrationTest
class ChatObservationTest {
    @Autowired
    private lateinit var builder: ChatClient.Builder

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var recorder: RecordingObservationHandler

    @Autowired
    private lateinit var coverageQuestions: CoverageQuestions

    @Autowired
    private lateinit var client: RestTestClient

    @Test
    fun `a blocking call through the injected builder is observed with its advisors`() {
        fake.reply("ok")

        builder.build().prompt().user("hello").call().content()

        val names = recorder.contexts.map { it.name }
        assertTrue(CHAT_CLIENT in names, names.toString())
        assertTrue(ADVISOR in names, names.toString())
    }

    /** Two runs: the second one starts after the first recorded a chat client call. */
    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `the recorder holds no observation of an earlier test`(run: Int) {
        val atStart = recorder.contexts.map { it.name }
        fake.reply("ok")

        builder.build().prompt().user("hello").call().content()

        assertTrue(CHAT_CLIENT !in atStart, "run $run started with $atStart")
    }

    @Test
    fun `answering a coverage question observes the retrieval`() {
        fake.reply("Not covered.")

        coverageQuestions.answer("Is my car covered after a class 5 kaiju event?")

        val names = recorder.contexts.map { it.name }
        assertTrue(VECTOR_STORE in names, names.toString())
        assertTrue(CHAT_CLIENT in names, names.toString())
    }

    @Test
    fun `a streamed claimant chat is observed once the stream has ended`() {
        val message = "streamed observation probe"

        val observed = chatAsClaimant(message)

        assertTrue(observed.isStream, observed.toString())
    }

    @Test
    fun `a tool call is observed without its arguments`() {
        fake.requestTool("getClaim", """{"id":"$MARKER"}""")
        fake.reply("ok")

        builder.build().prompt().user("What is the status of the claim?").tools(ClaimLookupTool()).call().content()

        val names = recorder.contexts.map { it.name }
        assertTrue(TOOL in names, names.toString())
        val leaks = recorder.contexts.flatMap { it.allKeyValues }.filter { MARKER in it.value }
        assertTrue(leaks.isEmpty(), leaks.toString())
    }

    @Test
    fun `the claimant's text is in no attribute`() {
        fake.reply("Not covered.")
        coverageQuestions.answer("Is my car covered after a class 5 kaiju event? Signed $MARKER")
        fake.reply("ok")
        builder.build().prompt().user("My name is $MARKER").call().content()

        chatAsClaimant("A kaiju flattened my car, says $MARKER")

        val leaks = recorder.contexts.flatMap { it.allKeyValues }.filter { MARKER in it.value }
        assertTrue(leaks.isEmpty(), leaks.toString())
    }

    @Test
    fun `the retrieval observation keeps its operation and clause count but not the question`() {
        fake.reply("Not covered.")

        coverageQuestions.answer("Is my car covered after a class 5 kaiju event? Signed $MARKER")

        val retrieval = recorder.contexts.single { it.name == VECTOR_STORE }
        val attributes = retrieval.allKeyValues.associate { it.key to it.value }
        assertEquals("query", attributes["db.operation.name"])
        assertNotNull(attributes["db.vector.query.top_k"], attributes.toString())
        assertTrue(attributes.values.none { MARKER in it }, attributes.toString())
    }

    /**
     * Posts [message] as a claimant to `/chat`, reads the stream to its end and returns the chat client observation of
     * that call. The streamed observation stops after the last event is sent, so it is awaited, matched by the text.
     */
    private fun chatAsClaimant(message: String): ChatClientObservationContext {
        fake.replyChunks("o", "k")

        client.post().uri("/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"persona":"CLAIMANT","message":"$message"}""")
            .exchange()
            .returnResult(ByteArray::class.java)

        val observed = await().atMost(Duration.ofSeconds(5))
            .until({ recorder.contexts.firstNotNullOfOrNull { it.chatClientCallWith(message) } }, { it != null })
        return checkNotNull(observed) { "awaited until present" }
    }

    private fun Observation.Context.chatClientCallWith(text: String): ChatClientObservationContext? =
        (this as? ChatClientObservationContext)?.takeIf { text in it.request.prompt().userMessage.text.orEmpty() }

    private companion object {
        const val CHAT_CLIENT = "spring.ai.chat.client"
        const val ADVISOR = "spring.ai.advisor"
        const val VECTOR_STORE = "db.vector.client.operation"
        const val TOOL = "spring.ai.tool"

        /** A word no prompt template, corpus clause or fixture contains. */
        const val MARKER = "gnome-7f3a"
    }
}
