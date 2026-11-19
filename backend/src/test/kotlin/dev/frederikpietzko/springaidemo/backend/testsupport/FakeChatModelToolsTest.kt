package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tool and streaming behaviour of the fake through the real, injected [ChatClient.Builder] of the shared context. */
@IntegrationTest
class FakeChatModelToolsTest {
    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var builder: ChatClient.Builder

    private val tool = ClaimLookupTool()

    @Test
    fun `chat client builder answers from the scripted fake`() {
        fake.reply("Hello")

        val answer = builder.build().prompt().user("Hi").call().content()

        assertEquals("Hello", answer)
    }

    @Test
    fun `executes the requested tool once and feeds its result into the second call`() {
        fake.requestTool("getClaim", """{"id":"4711"}""")
        fake.reply("Claim 4711 is open.")

        val answer = builder.build().prompt().user("What is the status of claim 4711?").tools(tool).call().content()

        assertEquals("Claim 4711 is open.", answer)
        assertEquals(["4711"], tool.executedWith)
        val toolResponse = fake.prompts[1].instructions.filterIsInstance<ToolResponseMessage>().single()
        val responseData = assertNotNull(toolResponse.responses.single().responseData)
        assertTrue(responseData.contains("claim 4711 is open"), responseData)
    }

    @Test
    fun `one reply requesting two tools runs both and both results reach the next call`() {
        fake.requestTools("getClaim" to """{"id":"4711"}""", "getClaim" to """{"id":"4712"}""")
        fake.reply("Both claims are open.")

        val answer = builder.build().prompt().user("Status of claims 4711 and 4712?").tools(tool).call().content()

        assertEquals("Both claims are open.", answer)
        assertEquals(["4711", "4712"], tool.executedWith)
        val results = fake.prompts[1].instructions.filterIsInstance<ToolResponseMessage>()
            .flatMap { it.responses.map { response -> response.responseData } }
        assertEquals(["\"claim 4711 is open\"", "\"claim 4712 is open\""], results)
    }

    @Test
    fun `streams scripted text chunks in order and completes`() {
        fake.replyChunks("Hel", "lo")

        val chunks = builder.build().prompt().user("Hi").stream().content().collectList().block(Duration.ofSeconds(10))

        assertEquals(["Hel", "lo"], chunks)
    }

    @Test
    fun `streaming fails with the unexpected prompt when the script is exhausted`() {
        val error = assertFailsWith<IllegalStateException> {
            builder.build().prompt().user("Surprise question").stream().content()
                .collectList().block(Duration.ofSeconds(10))
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("script exhausted"), message)
        assertTrue(message.contains("Surprise question"), message)
    }

    @Test
    fun `executes a tool requested inside a streamed response and streams the final answer`() {
        fake.requestTool("getClaim", """{"id":"4711"}""")
        fake.replyChunks("Claim 4711 ", "is open.")

        val chunks = builder.build().prompt().user("Status of claim 4711?").tools(tool).stream().content()
            .collectList().block(Duration.ofSeconds(10))

        assertEquals(["Claim 4711 ", "is open."], chunks)
        assertEquals(["4711"], tool.executedWith)
    }
}
