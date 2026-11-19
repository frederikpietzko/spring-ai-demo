package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.TodoItem
import dev.frederikpietzko.springaidemo.chatprotocol.TodoStatus
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The adjuster's todo list through `POST /chat`: every `TodoWrite` call of the model becomes a todo update. */
@IntegrationTest
class AdjusterTodoTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Test
    fun `todo writes become todo updates in order, before the text that follows`() {
        fake.requestTool("TodoWrite", FIRST_LIST)
        fake.requestTool("TodoWrite", SECOND_LIST)
        fake.replyChunks("Done.")

        val events = postAdjuster()

        val second = ChatEvent.TodoUpdate(
            [
                TodoItem("Verify the policy", TodoStatus.COMPLETED, "Verifying the policy"),
                TodoItem("Check coverage", TodoStatus.IN_PROGRESS, "Checking coverage"),
            ],
        )
        assertEquals([FIRST_UPDATE, second, ChatEvent.TextDelta("Done."), ChatEvent.Done], events)
    }

    @Test
    fun `a reply that ends with a todo write still ends with done`() {
        fake.requestTool("TodoWrite", FIRST_LIST)
        fake.replyChunks()

        val events = postAdjuster()

        assertEquals([FIRST_UPDATE, ChatEvent.Done], events)
    }

    @Test
    fun `an invalid todo list is rejected to the model and sends no todo update`() {
        fake.requestTool("TodoWrite", TWO_IN_PROGRESS)
        fake.replyChunks("Ok.")

        val events = postAdjuster()

        assertEquals([ChatEvent.TextDelta("Ok."), ChatEvent.Done], events)
        val toolResponse = fake.prompts[1].instructions.filterIsInstance<ToolResponseMessage>().single()
        val result = toolResponse.responses.single().responseData
        assertTrue(result.contains("Only ONE task"), result)
    }

    @Test
    fun `the adjuster is offered the todo and skill tools next to the core tools`() {
        fake.replyChunks("ok")

        postAdjuster()

        assertEquals(setOf("getClaim", "issuePayout", "Skill", "TodoWrite"), toolNames(0).toSet())
    }

    @Test
    fun `the claimant is offered neither the todo nor the skill tool`() {
        fake.replyChunks("ok")

        client.postChat(claimantJson("Hi"))

        assertEquals(["conversation_search"], toolNames(fake.prompts.lastIndex))
    }

    @Test
    fun `approving a reply with a todo write and a payout runs both and streams the todo update`() {
        val confirmationId = pauseForTodoAndPayout()
        fake.replyChunks("Paid.")

        val events = decide(confirmationId, approved = true)

        assertEquals([FIRST_UPDATE, ChatEvent.TextDelta("Paid."), ChatEvent.Done], events)
        assertEquals(["issuePayout"], coreTools.calls.map { it.tool })
    }

    @Test
    fun `denying a reply with a todo write and a payout runs the todo write only`() {
        val confirmationId = pauseForTodoAndPayout()
        fake.reply("No payout.")

        val events = decide(confirmationId, approved = false)

        assertEquals([FIRST_UPDATE, ChatEvent.TextDelta("No payout."), ChatEvent.Done], events)
        assertEquals([], coreTools.calls)
        val results = fake.prompts.last().instructions.filterIsInstance<ToolResponseMessage>().last().responses
            .associate { it.name to it.responseData }
        assertNotEquals(DENIED, results["TodoWrite"])
        assertEquals(DENIED, results["issuePayout"])
    }

    /** Scripts and posts an adjuster request whose one reply asks for `TodoWrite` and `issuePayout`. */
    private fun pauseForTodoAndPayout(): String {
        fake.requestTools("TodoWrite" to FIRST_LIST, "issuePayout" to """{"claimNumber":4711}""")
        return assertIs<ChatEvent.ConfirmationRequest>(postAdjuster().single()).confirmationId
    }

    private fun decide(confirmationId: String, approved: Boolean): List<ChatEvent> {
        val result = client.post().uri("/chat/decisions")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"confirmationId":"$confirmationId","approved":$approved}""")
            .exchange()
            .returnResult(ByteArray::class.java)
        return sseEvents(result.responseBody ?: byteArrayOf())
    }

    private fun toolNames(prompt: Int): List<String> =
        assertIs<ToolCallingChatOptions>(fake.prompts[prompt].options).toolCallbacks.orEmpty()
            .map { it.toolDefinition.name() }

    private fun postAdjuster(): List<ChatEvent> =
        sseEvents(client.postChat(ADJUSTER_REVIEW_4711).responseBody ?: byteArrayOf())

    private companion object {
        const val ADJUSTER_REVIEW_4711 = """{"persona":"ADJUSTER","message":"Review claim 4711"}"""
        const val DENIED = "The adjuster did not approve this payout."
        const val FIRST_LIST = """{"todos":[""" +
            """{"content":"Verify the policy","status":"in_progress","activeForm":"Verifying the policy"},""" +
            """{"content":"Check coverage","status":"pending","activeForm":"Checking coverage"}]}"""
        const val SECOND_LIST = """{"todos":[""" +
            """{"content":"Verify the policy","status":"completed","activeForm":"Verifying the policy"},""" +
            """{"content":"Check coverage","status":"in_progress","activeForm":"Checking coverage"}]}"""
        const val TWO_IN_PROGRESS = """{"todos":[""" +
            """{"content":"Verify the policy","status":"in_progress","activeForm":"Verifying the policy"},""" +
            """{"content":"Check coverage","status":"in_progress","activeForm":"Checking coverage"}]}"""

        /** The todo update of [FIRST_LIST]. */
        val FIRST_UPDATE = ChatEvent.TodoUpdate(
            [
                TodoItem("Verify the policy", TodoStatus.IN_PROGRESS, "Verifying the policy"),
                TodoItem("Check coverage", TodoStatus.PENDING, "Checking coverage"),
            ],
        )
    }
}
