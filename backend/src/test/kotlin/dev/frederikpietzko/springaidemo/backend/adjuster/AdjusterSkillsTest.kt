package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The adjuster's skills through `POST /chat`: what the model sees up front, and a skill loaded on demand. */
@IntegrationTest
class AdjusterSkillsTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Test
    fun `skill names and descriptions are offered up front, skill bodies are not`() {
        fake.replyChunks("ok")

        client.postChat(ADJUSTER_REVIEW_4711)

        val prompt = fake.prompts[0]
        val options = assertIs<ToolCallingChatOptions>(prompt.options)
        val skill = options.toolCallbacks.orEmpty().single { it.toolDefinition.name() == "Skill" }.toolDefinition
        val upFront = skill.description()
        listOf("kaiju-classification", "damage-estimation", KAIJU_DESCRIPTION, DAMAGE_DESCRIPTION).forEach {
            assertTrue(upFront.contains(it), "$it missing in: $upFront")
        }
        val everythingUpFront = options.toolCallbacks.orEmpty().map { it.toolDefinition.description() } +
            prompt.instructions.map { it.text.orEmpty() }
        listOf(KAIJU_BODY, DAMAGE_BODY).forEach { body ->
            assertTrue(everythingUpFront.none { it.contains(body) }, "skill body sent up front: $body")
        }
    }

    @Test
    fun `the model loads a skill and receives its body as the tool result`() {
        fake.requestTool("Skill", """{"command":"kaiju-classification"}""")
        fake.replyChunks("Class 3.")

        val events = sseEvents(client.postChat(ADJUSTER_REVIEW_4711).responseBody ?: byteArrayOf())

        assertEquals([ChatEvent.TextDelta("Class 3."), ChatEvent.Done], events)
        val toolResponse = fake.prompts[1].instructions.filterIsInstance<ToolResponseMessage>().single()
        val result = toolResponse.responses.single().responseData
        assertTrue(result.contains(KAIJU_BODY), result)
    }

    private companion object {
        const val ADJUSTER_REVIEW_4711 = """{"persona":"ADJUSTER","message":"Review claim 4711"}"""
        const val KAIJU_DESCRIPTION =
            "How to rate a kaiju event on the class 1-5 scale. Use it before you judge or compare kaiju classes."
        const val DAMAGE_DESCRIPTION =
            "How to check damage items, their categories and amounts per district. Use it before you judge an estimate."

        /** One sentence of each skill body, free of quotes, newlines and backslashes (tool results are JSON). */
        const val KAIJU_BODY = "A class 5 event is an Act of Kaiju and the policy wording excludes it from coverage."
        const val DAMAGE_BODY =
            "A single garden item worth more than a whole building item is suspicious; ask the adjuster to look at it."
    }
}
