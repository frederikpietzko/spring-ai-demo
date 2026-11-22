package dev.frederikpietzko.springai.kotlin

import dev.frederikpietzko.springai.kotlin.fakes.RecordingAdvisor
import dev.frederikpietzko.springai.kotlin.fakes.ScriptedChatModel
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.annotation.Tool
import org.springframework.core.io.ByteArrayResource
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatClientBuilderDslTest {
    private val model = ScriptedChatModel()
    private val builder = ChatClient.builder(model)

    @Test
    fun `system prompt from a resource`() {
        val client = builder.build { system(ByteArrayResource("Be brief".toByteArray())) }

        ask(client)

        assertEquals("Be brief", lastPrompt().systemMessage.text)
    }

    @Test
    fun `system prompt from a string`() {
        val client = builder.build { system("Be kind") }

        ask(client)

        assertEquals("Be kind", lastPrompt().systemMessage.text)
    }

    @Test
    fun `templated system prompt`() {
        val client = builder.build {
            system {
                text("Today is {today}")
                param("today", "Monday")
            }
        }

        ask(client)

        assertEquals("Today is Monday", lastPrompt().systemMessage.text)
    }

    @Test
    fun `tools, options and tool context reach the model`() {
        val client = builder.build {
            tools(Echo())
            options { model("m-1") }
            toolContext(mapOf("actor" to "a"))
        }

        ask(client)

        val options = assertIs<ToolCallingChatOptions>(lastPrompt().options)
        assertEquals(["echo"], options.toolCallbacks.orEmpty().map { it.toolDefinition.name() })
        assertEquals("m-1", options.model)
        assertEquals("a", options.toolContext?.get("actor"))
    }

    @Test
    fun `an empty block answers like build`() {
        val plain = builder.build()
        val built = builder.build { }

        val plainAnswer = ask(plain)
        val builtAnswer = ask(built)

        assertEquals(plainAnswer, builtAnswer)
        assertEquals(model.prompts[0].instructions, model.prompts[1].instructions)
    }

    @Test
    fun `block order does not decide the advisor order`() {
        val journal = CopyOnWriteArrayList<String>()
        val client = builder.build {
            advisors {
                +RecordingAdvisor("late", order = 100, journal = journal)
                +RecordingAdvisor("early", order = 10, journal = journal)
            }
        }

        ask(client)

        assertEquals(["early", "late"], journal)
    }

    @Test
    fun `a list of advisors adds each of them`() {
        val first = RecordingAdvisor("first")
        val second = RecordingAdvisor("second")
        val client = builder.build { advisors { +[first, second] } }

        ask(client)

        assertEquals([1, 1], [first.requests.size, second.requests.size])
    }

    @Test
    fun `structured output validation retries with the validation error`() {
        model.reply("no verdict")
        model.reply("""{"fraud": false, "reason": "none"}""")
        val client = builder.build { advisors { structuredOutputValidation<Verdict>(maxRepeatAttempts = 1) } }

        val answer = client.prompt().user("Judge").call().content()

        assertEquals("""{"fraud": false, "reason": "none"}""", answer)
        assertEquals(2, model.prompts.size)
        assertTrue(model.prompts[1].userMessage.text.orEmpty().contains("Output JSON validation failed"))
    }

    private fun ask(client: ChatClient): String? {
        model.reply("ok")
        return client.prompt().user("U").call().content()
    }

    private fun lastPrompt(): Prompt = model.prompts.last()

    class Echo {
        @Tool(description = "Repeats the text")
        fun echo(text: String): String = text
    }

    data class Verdict(val fraud: Boolean, val reason: String)
}
