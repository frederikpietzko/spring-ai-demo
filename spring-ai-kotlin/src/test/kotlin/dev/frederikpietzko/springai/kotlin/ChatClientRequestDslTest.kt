package dev.frederikpietzko.springai.kotlin

import dev.frederikpietzko.springai.kotlin.fakes.RecordingAdvisor
import dev.frederikpietzko.springai.kotlin.fakes.ScriptedChatModel
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.annotation.Tool
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

class ChatClientRequestDslTest {
    private val model = ScriptedChatModel()
    private val client = ChatClient.builder(model).build()

    @Test
    fun `request values reach the model and the tools`() {
        val recorder = ContextRecorder()
        model.requestTool("record", "{}")
        model.reply("done")

        val _ = client.prompt {
            system("S")
            user("U")
            tools(recorder)
            toolContext(mapOf("k" to "v"))
        }.call().content()

        assertEquals(["S", "U"], model.prompts.first().instructions.map { it.text })
        assertEquals(["v"], recorder.seen)
    }

    @Test
    fun `templated system prompt in a request`() {
        model.reply("ok")

        val _ = client.prompt {
            system {
                text("Today is {today}")
                param("today", "Monday")
            }
            user("U")
        }.call().content()

        assertEquals("Today is Monday", model.prompts.single().systemMessage.text)
    }

    @Test
    fun `earlier messages come between the system prompt and the user message`() {
        model.reply("ok")

        val _ = client.prompt {
            system("S")
            messages([UserMessage("earlier"), AssistantMessage("reply")])
            user("now")
        }.call().content()

        assertEquals(["S", "earlier", "reply", "now"], model.prompts.single().instructions.map { it.text })
    }

    @Test
    fun `request advisors see the request params`() {
        val recorder = RecordingAdvisor("recorder")
        model.reply("ok")

        val _ = client.prompt {
            user("U")
            advisors {
                +recorder
                param("p", 1)
            }
        }.call().content()

        assertEquals(1, recorder.requests.single().context()["p"])
    }

    @Test
    fun `user message metadata reaches the advisors`() {
        val recorder = RecordingAdvisor("recorder")
        model.reply("ok")

        val _ = client.prompt {
            user {
                text("Hi")
                metadata("typed", "Hi")
            }
            advisors { +recorder }
        }.call().content()

        assertEquals("Hi", recorder.requests.single().prompt().userMessage.metadata["typed"])
    }

    @Test
    fun `request values add to the client defaults`() {
        val journal = CopyOnWriteArrayList<String>()
        val withDefaults = ChatClient.builder(model).build {
            system("D")
            advisors { +RecordingAdvisor("a", order = 1, journal = journal) }
        }
        model.reply("ok")

        val _ = withDefaults.prompt {
            user("U")
            advisors { +RecordingAdvisor("b", order = 2, journal = journal) }
        }.call().content()

        assertEquals("D", model.prompts.single().systemMessage.text)
        assertEquals(["a", "b"], journal)
    }

    @Test
    fun `the lambda overload sits next to Spring's prompt overloads`() {
        model.reply("one")
        model.reply("two")

        val answers = [client.prompt("U").call().content(), client.prompt(Prompt("U")).call().content()]

        assertEquals(["one", "two"], answers)
    }

    /** A tool that records the tool context entry `k` of every call. */
    class ContextRecorder {
        private val recorded = CopyOnWriteArrayList<String?>()

        /** The entry `k` of each call's tool context, in call order. */
        val seen: List<String?> get() = recorded.toList()

        @Tool(description = "Records the tool context")
        fun record(toolContext: ToolContext): String {
            recorded.add(toolContext.context["k"] as? String)
            return "recorded"
        }
    }
}
