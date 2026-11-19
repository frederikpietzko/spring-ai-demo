package dev.frederikpietzko.springaidemo.backend.testsupport

import dev.frederikpietzko.springaidemo.backend.springai.entity
import dev.frederikpietzko.springaidemo.backend.springai.responseEntity
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeChatModelTest {
    private val fake = FakeChatModel()
    private val client = ChatClient.create(fake)

    @Test
    fun `returns the scripted text reply`() {
        fake.reply("Hello")

        val answer = client.prompt().user("Hi").call().content()

        assertEquals("Hello", answer)
    }

    @Test
    fun `fails with the unexpected prompt when the script is exhausted`() {
        val error = assertFailsWith<IllegalStateException> {
            client.prompt().user("Surprise question").call().content()
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("script exhausted"), message)
        assertTrue(message.contains("Surprise question"), message)
    }

    @Test
    fun `records the received prompt with system and user message`() {
        fake.reply("ok")

        client.prompt().system("Be brief").user("Question").call().content()

        val messages = fake.prompts.single().instructions
        assertTrue(messages.any { it is SystemMessage && it.text == "Be brief" })
        assertTrue(messages.any { it is UserMessage && it.text?.startsWith("Question") == true })
    }

    @Test
    fun `advertises tool calling options so the tool loop runs`() {
        assertIs<ToolCallingChatOptions>(fake.options)
    }

    private data class Verdict(val claimId: String, val approved: Boolean)

    @Test
    fun `parses a scripted JSON reply into the requested type`() {
        fake.replyJson("""{"claimId":"c-1","approved":true}""")

        val verdict = client.prompt().user("Decide").call().entity<Verdict>()

        assertEquals(Verdict(claimId = "c-1", approved = true), verdict)
    }

    @Test
    fun `adds format instructions for the requested type to the prompt`() {
        fake.replyJson("""{"claimId":"c-1","approved":true}""")

        client.prompt().user("Decide").call().entity<Verdict>()

        val userText = fake.prompts.single().userMessage.text.orEmpty()
        assertTrue(userText.contains("claimId"), userText)
        assertTrue(userText.contains("JSON", ignoreCase = true), userText)
    }

    @Test
    fun `parses a scripted JSON array into a generic list`() {
        fake.replyJson("""[{"claimId":"c-1","approved":true},{"claimId":"c-2","approved":false}]""")

        val verdicts = client.prompt().user("Decide all").call().entity<List<Verdict>>()

        assertEquals(
            [Verdict(claimId = "c-1", approved = true), Verdict(claimId = "c-2", approved = false)],
            verdicts,
        )
    }

    @Test
    fun `returns the parsed entity together with the chat response`() {
        val json = """{"claimId":"c-1","approved":true}"""
        fake.replyJson(json)

        val result = client.prompt().user("Decide").call().responseEntity<Verdict>()

        assertEquals(Verdict(claimId = "c-1", approved = true), result.entity)
        assertEquals(json, result.response?.result?.output?.text)
    }

    @Test
    fun `reset restarts the generated tool call ids`() {
        fake.requestTool("getClaim", "{}")
        fake.reset()
        fake.requestTool("getClaim", "{}")

        val response = fake.call(Prompt("probe"))

        assertEquals("call-1", response.result?.output?.toolCalls?.single()?.id)
    }
}
