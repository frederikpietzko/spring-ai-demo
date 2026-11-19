package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.ClaimLookupTool
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The adjuster agent through `POST /chat`, with the scripted model and the fake core tools of the shared context. */
@IntegrationTest
class AdjusterChatTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Autowired
    @Qualifier("mcpToolCallbacks")
    private lateinit var mcpToolCallbacks: ToolCallbackProvider

    @Autowired
    private lateinit var builder: ChatClient.Builder

    @Test
    fun `a tool result reaches the model and its answer streams`() {
        fake.requestTool("getClaim", CLAIM_4711)
        fake.replyChunks("Claim 4711 ", "is filed")

        val events = sseEvents(postChat(ADJUSTER_CLAIM_4711).responseBody ?: byteArrayOf())

        assertEquals([ChatEvent.TextDelta("Claim 4711 "), ChatEvent.TextDelta("is filed"), ChatEvent.Done], events)
        assertEquals([FakeCoreTools.Call("getClaim", 4711, IDENTITY)], coreTools.calls)
        val toolResponse = fake.prompts[1].instructions.filterIsInstance<ToolResponseMessage>().single()
        val result = assertNotNull(toolResponse.responses.single().responseData)
        assertTrue(result.contains("\"status\":\"FILED\""), result)
    }

    @Test
    fun `the adjuster instructions reach the model as the system message`() {
        fake.replyChunks("ok")

        postChat(ADJUSTER_CLAIM_4711)

        val system = fake.prompts.last().instructions.filterIsInstance<SystemMessage>().single().text.orEmpty()
        assertTrue(system.contains("adjuster assistant"), system)
        assertFalse(system.contains("claims desk assistant"), system)
    }

    @Test
    fun `the claimant is not offered the core tools`() {
        fake.replyChunks("ok")

        postChat("""{"persona":"CLAIMANT","message":"Hi"}""")

        val options = assertIs<ToolCallingChatOptions>(fake.prompts.last().options)
        // Only the claimant's own recall tool, none of the core tools.
        assertEquals(["conversation_search"], options.toolCallbacks.orEmpty().map { it.toolDefinition.name() })
    }

    @Test
    fun `the tool sees the adjuster identity in its tool context`() {
        fake.requestTool("getClaim", CLAIM_4711)
        fake.replyChunks("ok")

        postChat(ADJUSTER_CLAIM_4711)

        assertEquals(IDENTITY, coreTools.calls.single().toolContext)
    }

    @Test
    fun `the model never sees the identity`() {
        fake.requestTool("getClaim", CLAIM_4711)
        fake.replyChunks("ok")

        postChat(ADJUSTER_CLAIM_4711)

        val texts = fake.prompts.flatMap { prompt -> prompt.instructions.map { it.text.orEmpty() } }
        assertTrue(texts.none { it.contains("adjuster-agent") }, texts.toString())
        val toolResults = fake.prompts.flatMap { it.instructions.filterIsInstance<ToolResponseMessage>() }
            .flatMap { it.responses.map { response -> response.responseData } }
        assertTrue(toolResults.none { it.contains("adjuster-agent") }, toolResults.toString())
    }

    @Test
    fun `an unreachable core system becomes one generic error event`() {
        coreTools.listingFailure = IllegalStateException("Client failed to initialize listing tools")

        val result = postChat(ADJUSTER_CLAIM_4711)

        assertEquals(HttpStatus.OK, result.status)
        assertEquals([ChatEvent.Error(GENERIC_ERROR)], sseEvents(result.responseBody ?: byteArrayOf()))
        assertTrue(fake.prompts.isEmpty(), fake.prompts.toString())
    }

    @Test
    fun `a local tool that reuses a core tool name fails the request before the model is asked`() {
        // ClaimLookupTool declares a local getClaim, the name of a core tool.
        val clashing = builder.defaultTools(mcpToolCallbacks, ClaimLookupTool()).build()
        fake.reply("never used")

        val error = assertFailsWith<IllegalStateException> {
            clashing.prompt().user("Claim 4711?").toolContext(mapOf("actor" to "adjuster-agent")).call().content()
        }

        assertTrue(error.message.orEmpty().contains("getClaim"), error.message)
        assertTrue(fake.prompts.isEmpty(), fake.prompts.toString())
    }

    private fun postChat(json: String): EntityExchangeResult<ByteArray> =
        client.post().uri("/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body(json)
            .exchange()
            .returnResult(ByteArray::class.java)

    private companion object {
        const val ADJUSTER_CLAIM_4711 = """{"persona":"ADJUSTER","message":"What is the status of claim 4711?"}"""
        const val CLAIM_4711 = """{"claimNumber":4711}"""
        const val GENERIC_ERROR = "The claims desk is unavailable."
        val IDENTITY: Map<String, Any?> = mapOf("actor" to "adjuster-agent")
    }
}
