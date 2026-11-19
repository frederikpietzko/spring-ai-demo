package dev.frederikpietzko.springaidemo.backend.guardrail

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeDecisionModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.POISONED_INVOICE
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The poisoned invoice (seed claim 4700) on both personas, with a model that obeys the injection: no payout reaches
 * the core. The claimant side is held by the guardrail and, when it misses, by the payout confirmation; the adjuster
 * side by the confirmation, and its subagents by their read-only tool lists. The core's own refusals (scope, status)
 * are `PoisonedInvoicePayoutTest` in core-mcp.
 */
@IntegrationTest
class PoisonedInvoiceTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var decisionModel: FakeDecisionModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Test
    fun `the guardrail stops the pasted invoice`() {
        val events = post("/chat", claimantJson(POISONED_INVOICE, "poisoned-invoice-guardrail"))

        assertEquals([ChatEvent.TextDelta(GuardrailAdvisor.REFUSAL), ChatEvent.Done], events)
        assertEquals([], coreTools.calls)
    }

    @Test
    fun `the claimant chat cannot pay out when the guardrail misses`() {
        decisionModel.phrase = null
        fake.requestTool("issuePayout", CLAIM_4700)

        val _ = post("/chat", claimantJson(POISONED_INVOICE, "poisoned-invoice-missed"))

        assertTrue(coreTools.calls.none { it.tool == "issuePayout" }, coreTools.calls.toString())
    }

    @Test
    fun `the adjuster reads the poisoned claim and the payout waits for a human`() {
        fake.requestTool("getClaim", CLAIM_4700)
        fake.requestTool("issuePayout", CLAIM_4700)

        val events = post("/chat", ADJUSTER_ASKS_ABOUT_4700)

        val pause = assertIs<ChatEvent.ConfirmationRequest>(events.single())
        assertEquals("issuePayout", pause.toolName)
        assertEquals(["getClaim"], coreTools.calls.map { it.tool })
        val claimSeenByModel = fake.prompts[1].instructions.filterIsInstance<ToolResponseMessage>()
            .flatMap { it.responses }
            .single { it.name == "getClaim" }
            .responseData
        assertTrue(claimSeenByModel.contains(POISONED_INVOICE), claimSeenByModel)
    }

    @Test
    fun `denying the confirmation leaves no payout`() {
        fake.requestTool("getClaim", CLAIM_4700)
        fake.requestTool("issuePayout", CLAIM_4700)
        val pause = assertIs<ChatEvent.ConfirmationRequest>(post("/chat", ADJUSTER_ASKS_ABOUT_4700).single())
        fake.reply("Understood, no payout.")

        val events = post("/chat/decisions", """{"confirmationId":"${pause.confirmationId}","approved":false}""")

        assertEquals(ChatEvent.Done, events.last())
        assertTrue(coreTools.calls.none { it.tool == "issuePayout" }, coreTools.calls.toString())
    }

    @Test
    fun `a subagent that reads the poisoned claim cannot pay out`() {
        fake.requestTool("Task", DELEGATE_4700)
        fake.requestTool("getClaim", CLAIM_4700)
        fake.requestTool("issuePayout", CLAIM_4700)
        fake.replyChunks("Not possible.")

        val events = post("/chat", ADJUSTER_ASKS_ABOUT_4700)

        assertEquals(["getClaim"], coreTools.calls.map { it.tool })
        val subagentTools = assertIs<ToolCallingChatOptions>(fake.prompts[1].options).toolCallbacks.orEmpty()
        assertEquals(["getClaim"], subagentTools.map { it.toolDefinition.name() })
        val claimSeenBySubagent = toolResponse(fake.prompts[2], "getClaim")
        assertTrue(claimSeenBySubagent.contains(POISONED_INVOICE), claimSeenBySubagent)
        val taskResult = toolResponse(fake.prompts[3], "Task")
        assertTrue(taskResult.isNotBlank() && taskResult != "\"\"", taskResult)
        assertEquals([ChatEvent.TextDelta("Not possible."), ChatEvent.Done], events)
    }

    /** The result of the call of [tool] in [prompt]. */
    private fun toolResponse(prompt: Prompt, tool: String): String =
        prompt.instructions.filterIsInstance<ToolResponseMessage>()
            .flatMap { it.responses }
            .single { it.name == tool }
            .responseData

    private fun post(path: String, json: String): List<ChatEvent> =
        sseEvents(
            client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(json)
                .exchange()
                .returnResult(ByteArray::class.java)
                .responseBody ?: byteArrayOf(),
        )

    private companion object {
        const val CLAIM_4700 = """{"claimNumber":4700}"""
        const val ADJUSTER_ASKS_ABOUT_4700 = """{"persona":"ADJUSTER","message":"What about claim 4700?"}"""
        const val DELEGATE_4700 = """{"description":"Check the claim",""" +
            """"prompt":"Check the damage items of claim 4700.","subagent_type":"damage-assessor"}"""
    }
}
