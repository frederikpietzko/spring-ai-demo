package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The adjuster's analytics through `POST /chat`: a `payoutStats` result becomes an `analytics-table` event, and the
 * prompt carries today's date and the instruction to take numbers from the analytics tools.
 */
@IntegrationTest
class AdjusterAnalyticsTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Test
    fun `the stream carries the table before the answer`() {
        fake.requestTool("payoutStats", """{"filter":{"district":"HARBOUR"},"groupBy":"KAIJU_CLASS"}""")
        fake.replyChunks("Harbour paid out 1120000")

        val events = postAdjuster("What did the harbour pay out per kaiju class?")

        assertEquals(
            [
                ChatEvent.AnalyticsTable(
                    title = "Payouts by kaiju class",
                    columns = ["Kaiju class", "Payouts", "Total paid", "Average paid"],
                    rows = [["2", "1", "140000", "140000"], ["4", "2", "980000", "490000"]],
                ),
                ChatEvent.TextDelta("Harbour paid out 1120000"),
                ChatEvent.Done,
            ],
            events,
        )
        assertEquals([IDENTITY], coreTools.analyticsCalls.map { it.toolContext })
        val toolResult = fake.prompts[1].instructions.filterIsInstance<ToolResponseMessage>().single()
            .responses.single().responseData
        assertTrue(toolResult.contains("KAIJU_CLASS"), toolResult)
    }

    @Test
    fun `other tools give no table`() {
        fake.requestTool("countClaims", """{"filter":{"district":"HARBOUR"}}""")
        fake.replyChunks("Harbour has 4 claims")
        val countEvents = postAdjuster("How many harbour claims?")
        fake.requestTool("getClaim", """{"claimNumber":4711}""")
        fake.replyChunks("Claim 4711 is filed")

        val claimEvents = postAdjuster("Claim 4711?")

        assertEquals([ChatEvent.TextDelta("Harbour has 4 claims"), ChatEvent.Done], countEvents)
        assertEquals([ChatEvent.TextDelta("Claim 4711 is filed"), ChatEvent.Done], claimEvents)
    }

    @Test
    fun `a failed analytics call gives no table`() {
        coreTools.analyticsFailure = IllegalStateException("payoutStats failed")
        fake.requestTool("payoutStats", """{"filter":{}}""")
        fake.replyChunks("No statistics right now")

        val events = postAdjuster("What did we pay out?")

        assertEquals([ChatEvent.TextDelta("No statistics right now"), ChatEvent.Done], events)
    }

    @Test
    fun `the core tools are offered once, next to the skill, todo, task and bulletin search tools`() {
        fake.replyChunks("ok")

        postAdjuster("Hello")

        val options = assertIs<ToolCallingChatOptions>(fake.prompts.single().options)
        assertEquals(
            [
                "Skill", "Task", "TodoWrite", "countClaims", "getClaim", "issuePayout", "payoutStats",
                "searchSightingBulletins", "updateClaimStatus",
            ],
            options.toolCallbacks.orEmpty().map { it.toolDefinition.name() }.sorted(),
        )
    }

    @Test
    fun `the system message holds today's date and the analytics instruction`() {
        fake.replyChunks("ok")
        val before = LocalDate.now(ZoneOffset.UTC).toString()

        postAdjuster("How many claims came in this month?")

        val after = LocalDate.now(ZoneOffset.UTC).toString()
        val system = fake.prompts.single().instructions.filterIsInstance<SystemMessage>().single().text.orEmpty()
        assertTrue(system.contains(before) || system.contains(after), system)
        assertTrue(system.contains("countClaims") && system.contains("payoutStats"), system)
        assertFalse(system.contains("{today}"), system)
    }

    @Test
    fun `the adjuster prompt holds no brace besides the one today placeholder`() {
        val prompt = ClassPathResource("prompts/adjuster-system.md").getContentAsString(Charsets.UTF_8)

        val withoutPlaceholder = prompt.replaceFirst("{today}", "")

        assertTrue(prompt.contains("{today}"), prompt)
        assertFalse(withoutPlaceholder.contains('{') || withoutPlaceholder.contains('}'), withoutPlaceholder)
    }

    private fun postAdjuster(message: String): List<ChatEvent> {
        val result = client.post().uri("/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"persona":"ADJUSTER","message":"$message"}""")
            .exchange()
            .returnResult(ByteArray::class.java)
        return sseEvents(result.responseBody ?: byteArrayOf())
    }

    private companion object {
        val IDENTITY: Map<String, Any?> = mapOf("actor" to "adjuster-agent")
    }
}
