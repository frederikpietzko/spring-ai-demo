package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.fraud.FraudAssessment
import dev.frederikpietzko.springaidemo.backend.fraud.FraudVerdict
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.TodoItem
import dev.frederikpietzko.springaidemo.chatprotocol.TodoStatus
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Delegation to the subagents through `POST /chat`. One scripted fake serves the orchestrator and the subagents in call
 * order: orchestrator step, the subagent's steps, orchestrator step. Each prompt is told apart by its system text,
 * model and tools.
 */
@IntegrationTest
class AdjusterSubagentTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Test
    fun `the orchestrator delegates the damage check and gets the subagent's answer`() {
        scriptDamageCheck()

        val events = postAdjuster()

        val subagent = fake.prompts[1]
        val system = subagent.instructions.filterIsInstance<SystemMessage>().single().text.orEmpty()
        assertTrue(system.contains("You are the damage assessor"), system)
        assertTrue(system.contains(DAMAGE_ESTIMATION_BODY), system)
        assertEquals(DAMAGE_PROMPT, subagent.instructions.filterIsInstance<UserMessage>().single().text)
        assertEquals(["getClaim"], toolNames(subagent))
        assertEquals("Items look consistent.", jsonMapper.readValue(taskResponse(fake.prompts[3]), String::class.java))
        assertEquals([ChatEvent.TextDelta("The damage looks fine."), ChatEvent.Done], events)
    }

    @Test
    fun `the orchestrator and the subagent run on their own models`() {
        scriptDamageCheck()

        postAdjuster()

        val models = fake.prompts.map { it.options?.model }
        assertEquals(["claude-sonnet-5-5", "claude-haiku-5-5", "claude-haiku-5-5", "claude-sonnet-5-5"], models)
    }

    @Test
    fun `a model chosen in the Task call is ignored`() {
        scriptDamageCheck(extra = ""","model":"opus"""")

        postAdjuster()

        assertEquals("claude-haiku-5-5", fake.prompts[1].options?.model)
    }

    @Test
    fun `the subagent's core calls carry the adjuster identity`() {
        scriptDamageCheck()

        postAdjuster()

        assertEquals([FakeCoreTools.Call("getClaim", 4711, IDENTITY)], coreTools.calls)
    }

    @Test
    fun `an unknown subagent is reported to the orchestrator`() {
        fake.requestTool("Task", task("general-purpose"))
        fake.replyChunks("Cannot delegate.")

        val events = postAdjuster()

        assertEquals(2, fake.prompts.size)
        val response = taskResponse(fake.prompts[1])
        assertTrue(response.contains("general-purpose"), response)
        assertEquals([ChatEvent.TextDelta("Cannot delegate."), ChatEvent.Done], events)
    }

    @Test
    fun `a background task is refused and runs no subagent`() {
        fake.requestTool("Task", task("damage-assessor", ""","run_in_background":true"""))
        fake.replyChunks("Cannot run it.")

        val events = postAdjuster()

        assertEquals(2, fake.prompts.size)
        val response = taskResponse(fake.prompts[1])
        assertTrue(response.contains("Background tasks are not supported"), response)
        assertEquals([ChatEvent.TextDelta("Cannot run it."), ChatEvent.Done], events)
    }

    @Test
    fun `the fraud investigator is offered exactly its two read-only tools`() {
        fake.requestTool("Task", task("fraud-investigator", prompt = FRAUD_PROMPT))
        fake.reply("No contradiction found.")
        fake.replyChunks("Checked.")

        postAdjuster()

        assertEquals(["getClaim", "searchSightingBulletins"], toolNames(fake.prompts[1]).sorted())
    }

    @Test
    fun `a fraud investigator asking for issuePayout runs nothing and fails the Task call`() {
        fake.requestTool("Task", task("fraud-investigator", prompt = FRAUD_PROMPT))
        fake.requestTool("issuePayout", CLAIM_4711)
        fake.replyChunks("Not possible.")

        val events = postAdjuster()

        assertEquals([], coreTools.calls.filter { it.tool == "issuePayout" })
        val response = taskResponse(fake.prompts[2])
        assertTrue(response.isNotBlank() && !response.contains("\"verdict\""), response)
        assertEquals([ChatEvent.TextDelta("Not possible."), ChatEvent.Done], events)
    }

    @Test
    fun `a damage assessor asking for issuePayout runs nothing and fails the Task call`() {
        fake.requestTool("Task", task("damage-assessor"))
        fake.requestTool("issuePayout", CLAIM_4711)
        fake.replyChunks("Not possible.")

        val events = postAdjuster()

        assertEquals([], coreTools.calls.filter { it.tool == "issuePayout" })
        val response = taskResponse(fake.prompts[2])
        assertTrue(response.isNotBlank() && response != "\"\"", response)
        assertEquals([ChatEvent.TextDelta("Not possible."), ChatEvent.Done], events)
    }

    @Test
    fun `the fraud assessment reaches the orchestrator as structured output`() {
        fake.requestTool("Task", task("fraud-investigator", prompt = FRAUD_PROMPT))
        fake.requestTool("getClaim", CLAIM_4711)
        fake.replyJson(FLAGGED_BY_SB_102)
        fake.replyChunks("Claim 4711 is flagged.")

        val events = postAdjuster()

        val (verdict, evidence) = jsonMapper.readValue(taskResponse(fake.prompts[3]), FraudAssessment::class.java)
        assertEquals(FraudVerdict.FLAGGED, verdict)
        assertEquals(["SB-102"], evidence.map { it.bulletinId })
        val user = fake.prompts[2].instructions.filterIsInstance<UserMessage>().last().text.orEmpty()
        assertTrue(user.startsWith(FRAUD_PROMPT) && user.contains("JSON") && user.contains("\"verdict\""), user)
        assertEquals([ChatEvent.TextDelta("Claim 4711 is flagged."), ChatEvent.Done], events)
    }

    @Test
    fun `an unreadable fraud assessment is an error, not a verdict`() {
        fake.requestTool("Task", task("fraud-investigator", prompt = FRAUD_PROMPT))
        fake.reply("I think it is fine.")
        fake.replyChunks("The check failed.")

        postAdjuster()

        val response = taskResponse(fake.prompts[2])
        assertFails { jsonMapper.readValue(response, FraudAssessment::class.java) }
        assertFalse(response.contains("\"verdict\""), response)
        assertNotEquals("\"I think it is fine.\"", response)
    }

    @Test
    fun `approving a paused reply with a Task call runs the delegation and the payout`() {
        fake.requestTools("Task" to task("damage-assessor"), "issuePayout" to CLAIM_4711)
        val pause = assertIs<ChatEvent.ConfirmationRequest>(postAdjuster().single())
        fake.reply("Items look consistent.")
        fake.replyChunks("Paid.")

        val events = decide(pause.confirmationId)

        assertEquals(["issuePayout"], coreTools.calls.map { it.tool })
        val taskResult = jsonMapper.readValue(taskResponse(fake.prompts.last()), String::class.java)
        assertEquals("Items look consistent.", taskResult)
        assertEquals([ChatEvent.TextDelta("Paid."), ChatEvent.Done], events)
    }

    @Test
    fun `todo updates surround the delegation`() {
        fake.requestTool("TodoWrite", todo("in_progress"))
        fake.requestTool("Task", task("fraud-investigator", prompt = FRAUD_PROMPT))
        fake.replyJson(FLAGGED_BY_SB_102)
        fake.requestTool("TodoWrite", todo("completed"))
        fake.replyChunks("Done.")

        val events = postAdjuster()

        val expected = [
            ChatEvent.TodoUpdate([TodoItem("Run a fraud check", TodoStatus.IN_PROGRESS, "Running a fraud check")]),
            ChatEvent.TodoUpdate([TodoItem("Run a fraud check", TodoStatus.COMPLETED, "Running a fraud check")]),
            ChatEvent.TextDelta("Done."),
            ChatEvent.Done,
        ]
        assertEquals(expected, events)
    }

    @Test
    fun `the adjuster instructions name both subagents`() {
        fake.replyChunks("ok")

        postAdjuster()

        val system = fake.prompts[0].instructions.filterIsInstance<SystemMessage>().single().text.orEmpty()
        assertTrue(system.contains("fraud-investigator") && system.contains("damage-assessor"), system)
    }

    @Test
    fun `the claimant is not offered Task`() {
        fake.replyChunks("ok")

        client.postChat(claimantJson("Hi"))

        assertFalse("Task" in toolNames(fake.prompts.last()))
    }

    /** Orchestrator: `Task` for `damage-assessor`; subagent: `getClaim`, then its answer; orchestrator: the reply. */
    private fun scriptDamageCheck(extra: String = "") {
        fake.requestTool("Task", task("damage-assessor", extra, DAMAGE_PROMPT))
        fake.requestTool("getClaim", CLAIM_4711)
        fake.reply("Items look consistent.")
        fake.replyChunks("The damage looks fine.")
    }

    private fun task(subagent: String, extra: String = "", prompt: String = DAMAGE_PROMPT): String =
        """{"description":"Check the claim","prompt":"$prompt","subagent_type":"$subagent"$extra}"""

    private fun todo(status: String): String =
        """{"todos":[{"content":"Run a fraud check","status":"$status","activeForm":"Running a fraud check"}]}"""

    /** The result of the `Task` call in [prompt]. */
    private fun taskResponse(prompt: Prompt): String =
        prompt.instructions.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }
            .single { it.name == "Task" }.responseData

    private fun toolNames(prompt: Prompt): List<String> =
        assertIs<ToolCallingChatOptions>(prompt.options).toolCallbacks.orEmpty().map { it.toolDefinition.name() }

    private fun decide(confirmationId: String): List<ChatEvent> {
        val result = client.post().uri("/chat/decisions")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"confirmationId":"$confirmationId","approved":true}""")
            .exchange()
            .returnResult(ByteArray::class.java)
        return sseEvents(result.responseBody ?: byteArrayOf())
    }

    private fun postAdjuster(): List<ChatEvent> =
        sseEvents(client.postChat(ADJUSTER_REVIEW_4711).responseBody ?: byteArrayOf())

    private companion object {
        const val ADJUSTER_REVIEW_4711 = """{"persona":"ADJUSTER","message":"Review claim 4711"}"""
        const val DAMAGE_PROMPT = "Check the damage items of claim 4711."
        const val FRAUD_PROMPT = "Check claim 4711 against the sighting bulletins."
        const val CLAIM_4711 = """{"claimNumber":4711}"""
        const val FLAGGED_BY_SB_102 = """{"verdict":"FLAGGED","evidence":[{"bulletinId":"SB-102",""" +
            """"finding":"The kaiju was in Saltmarsh, 40 km from the harbour, at 14:45."}],"confidence":0.9}"""

        /** A line of the `damage-estimation` skill body, which is not in its description. */
        const val DAMAGE_ESTIMATION_BODY = "Every damage item of a claim has one category"
        val IDENTITY: Map<String, Any?> = mapOf("actor" to "adjuster-agent")
    }
}
