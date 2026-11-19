package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.RestTestClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The payout confirmation of the adjuster agent: the pause on `POST /chat` and the decision. */
@IntegrationTest
class AdjusterConfirmationTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Test
    fun `a payout request pauses the loop with one confirmation request and runs no payout`() {
        fake.requestTool("getClaim", CLAIM_4711)
        fake.requestTool("issuePayout", CLAIM_4711)

        val events = postChat(ADJUSTER_PAY_4711)

        val pause = assertIs<ChatEvent.ConfirmationRequest>(events.single())
        assertEquals("issuePayout", pause.toolName)
        assertEquals(CLAIM_4711, pause.arguments)
        assertEquals(["getClaim"], coreTools.calls.map { it.tool })
    }

    @Test
    fun `approving a claim pauses the loop like a payout and changes no status`() {
        fake.requestTool("updateClaimStatus", APPROVE_4711)

        val events = postChat(ADJUSTER_PAY_4711)

        val pause = assertIs<ChatEvent.ConfirmationRequest>(events.single())
        assertEquals("updateClaimStatus", pause.toolName)
        assertEquals(APPROVE_4711, pause.arguments)
        assertEquals([], coreTools.calls)
    }

    @Test
    fun `moving a claim to review needs no confirmation`() {
        fake.requestTool("updateClaimStatus", """{"claimNumber":4711,"status":"UNDER_REVIEW"}""")
        fake.replyChunks("Claim 4711 is under review.")

        val events = postChat(ADJUSTER_PAY_4711)

        assertEquals([ChatEvent.TextDelta("Claim 4711 is under review."), ChatEvent.Done], events)
        assertEquals([FakeCoreTools.Call("updateClaimStatus", 4711, IDENTITY)], coreTools.calls)
    }

    @Test
    fun `denying an approval changes no status and tells the model it was not approved`() {
        fake.requestTool("updateClaimStatus", APPROVE_4711)
        val pause = assertIs<ChatEvent.ConfirmationRequest>(postChat(ADJUSTER_PAY_4711).single())
        fake.reply("Not approved.")

        val events = decide(pause.confirmationId, approved = false)

        assertEquals([ChatEvent.TextDelta("Not approved."), ChatEvent.Done], events)
        assertEquals([], coreTools.calls)
        assertEquals("The adjuster did not approve this call.", lastToolResult())
    }

    @Test
    fun `a read-only tool runs without a confirmation`() {
        fake.requestTool("getClaim", CLAIM_4711)
        fake.replyChunks("Claim 4711 is filed.")

        val events = postChat(ADJUSTER_PAY_4711)

        assertTrue(events.none { it is ChatEvent.ConfirmationRequest }, events.toString())
        assertEquals(ChatEvent.Done, events.last())
    }

    @Test
    fun `approving runs the payout once with the identity and streams the continuation`() {
        fake.requestTool("getClaim", CLAIM_4711)
        val confirmationId = pauseForPayout()
        fake.replyChunks("Paid ", "out")

        val events = decide(confirmationId, approved = true)

        assertEquals([ChatEvent.TextDelta("Paid "), ChatEvent.TextDelta("out"), ChatEvent.Done], events)
        val payouts = coreTools.calls.filter { it.tool == "issuePayout" }
        assertEquals([FakeCoreTools.Call("issuePayout", 4711, IDENTITY)], payouts)
        assertTrue(lastToolResult().contains("754500"), lastToolResult())
    }

    @Test
    fun `the continuation after a decision carries the tool rounds before the pause`() {
        fake.requestTool("getClaim", CLAIM_4711)
        val confirmationId = pauseForPayout()
        fake.reply("Paid out.")

        val _ = decide(confirmationId, approved = true)

        val continuation = fake.prompts.last().instructions
        val toolCalls = continuation.filterIsInstance<AssistantMessage>().flatMap { it.toolCalls.map { it.name() } }
        val toolResults = continuation.filterIsInstance<ToolResponseMessage>().flatMap { it.responses.map { it.name } }
        assertEquals(["getClaim", "issuePayout"], toolCalls)
        assertEquals(["getClaim", "issuePayout"], toolResults)
        assertEquals(1, continuation.filterIsInstance<SystemMessage>().size, continuation.toString())
        assertEquals(["Pay out claim 4711"], continuation.filterIsInstance<UserMessage>().map { it.text })
    }

    @Test
    fun `a search paused together with a payout runs after approval`() {
        fake.requestTools("searchSightingBulletins" to SALTMARSH_SEARCH, "issuePayout" to CLAIM_4711)
        val pause = assertIs<ChatEvent.ConfirmationRequest>(postChat(ADJUSTER_PAY_4711).last())
        fake.replyChunks("Done")

        val events = decide(pause.confirmationId, approved = true)

        assertEquals(ChatEvent.Done, events.last())
        val results = fake.prompts.last().instructions.filterIsInstance<ToolResponseMessage>()
            .flatMap { it.responses }
            .associate { it.name to it.responseData }
        val expectedTools: Set<String> = ["searchSightingBulletins", "issuePayout"]
        assertEquals(expectedTools, results.keys)
        val searchResult = results.getValue("searchSightingBulletins")
        assertTrue(searchResult.contains("SB-102"), searchResult)
    }

    @Test
    fun `denying runs no payout and hands the denial to the model`() {
        val confirmationId = pauseForPayout()
        fake.reply("Understood, no payout.")

        val events = decide(confirmationId, approved = false)

        assertEquals([ChatEvent.TextDelta("Understood, no payout."), ChatEvent.Done], events)
        assertEquals([], coreTools.calls)
        assertEquals("The adjuster did not approve this payout.", lastToolResult())
    }

    @Test
    fun `denying runs the other calls of the paused reply and denies only the payout`() {
        fake.requestTools("getClaim" to CLAIM_4711, "issuePayout" to CLAIM_4711)
        val pause = assertIs<ChatEvent.ConfirmationRequest>(postChat(ADJUSTER_PAY_4711).single())
        fake.reply("No payout.")

        val events = decide(pause.confirmationId, approved = false)

        assertEquals([ChatEvent.TextDelta("No payout."), ChatEvent.Done], events)
        assertEquals([FakeCoreTools.Call("getClaim", 4711, IDENTITY)], coreTools.calls)
        val results = fake.prompts.last().instructions.filterIsInstance<ToolResponseMessage>().last().responses
            .associate { it.name to it.responseData }
        assertTrue(results["getClaim"].orEmpty().contains("\"status\":\"FILED\""), results.toString())
        assertEquals("The adjuster did not approve this payout.", results["issuePayout"])
    }

    @Test
    fun `replaying a decision runs nothing again`() {
        val confirmationId = pauseForPayout()
        fake.reply("Paid out.")
        decide(confirmationId, approved = true)

        val replay = decide(confirmationId, approved = true)

        assertIs<ChatEvent.Error>(replay.single())
        assertEquals(1, coreTools.calls.size)
    }

    @Test
    fun `a decision on an id that was never issued answers one error and runs nothing`() {
        val events = decide("never-issued", approved = true)

        assertIs<ChatEvent.Error>(events.single())
        assertTrue(fake.prompts.isEmpty(), fake.prompts.toString())
        assertEquals([], coreTools.calls)
    }

    @Test
    fun `a payout failing with a checked exception becomes one generic error event`() {
        val confirmationId = pauseForPayout()
        val promptsBeforeTheDecision = fake.prompts.size
        coreTools.payoutFailure = IOException("payout exploded")
        fake.reply("must stay unused")

        val result = post("/chat/decisions", decision(confirmationId, approved = true))

        assertEquals(HttpStatus.OK, result.status)
        assertTrue(MediaType.TEXT_EVENT_STREAM.isCompatibleWith(result.responseHeaders.contentType))
        val error = assertIs<ChatEvent.Error>(sseEvents(result.responseBody ?: byteArrayOf()).single())
        assertFalse(error.message.contains("payout exploded"), error.message)
        assertEquals(promptsBeforeTheDecision, fake.prompts.size)
    }

    @Test
    fun `an ordinary payout error reaches the model as the tool result`() {
        val confirmationId = pauseForPayout()
        coreTools.payoutFailure = IllegalStateException("Payout service is down")
        fake.reply("Payout failed.")

        val events = decide(confirmationId, approved = true)

        assertEquals([ChatEvent.TextDelta("Payout failed."), ChatEvent.Done], events)
        assertTrue(lastToolResult().contains("Payout service is down"), lastToolResult())
    }

    /** Scripts and posts an adjuster request whose reply asks for `issuePayout`; returns the confirmation id. */
    private fun pauseForPayout(): String {
        fake.requestTool("issuePayout", CLAIM_4711)
        return assertIs<ChatEvent.ConfirmationRequest>(postChat(ADJUSTER_PAY_4711).last()).confirmationId
    }

    private fun lastToolResult(): String =
        fake.prompts.last().instructions.filterIsInstance<ToolResponseMessage>().last().responses.single().responseData

    private fun decide(confirmationId: String, approved: Boolean): List<ChatEvent> =
        sseEvents(post("/chat/decisions", decision(confirmationId, approved)).responseBody ?: byteArrayOf())

    private fun decision(confirmationId: String, approved: Boolean) =
        """{"confirmationId":"$confirmationId","approved":$approved}"""

    private fun postChat(json: String): List<ChatEvent> =
        sseEvents(post("/chat", json).responseBody ?: byteArrayOf())

    private fun post(path: String, json: String): EntityExchangeResult<ByteArray> =
        client.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body(json)
            .exchange()
            .returnResult(ByteArray::class.java)

    private companion object {
        const val ADJUSTER_PAY_4711 = """{"persona":"ADJUSTER","message":"Pay out claim 4711"}"""
        const val CLAIM_4711 = """{"claimNumber":4711}"""
        const val APPROVE_4711 = """{"claimNumber":4711,"status":"APPROVED"}"""
        const val SALTMARSH_SEARCH = """{"query":"kaiju sighting","from":"2026-11-18T13:00:00Z",""" +
            """"to":"2026-11-18T17:00:00Z","place":"SALTMARSH"}"""
        val IDENTITY: Map<String, Any?> = mapOf("actor" to "adjuster-agent")
    }
}
