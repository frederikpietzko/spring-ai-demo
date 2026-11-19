package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeClaimantCore
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimJson
import dev.frederikpietzko.springaidemo.backend.testsupport.claimReportJson
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ClaimCardItem
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The claim actions of `POST /chat`: prepare and confirm. Every test uses its own conversation id. */
@IntegrationTest
class ClaimActionsEndpointTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var core: FakeClaimantCore

    @Test
    fun `a complete report is answered with the claim card and done`() {
        tellTheClaimant("prepare-card")
        fake.replyJson(claimReportJson())

        val events = post(claimJson(ChatAction.PREPARE_CLAIM, "prepare-card"))

        assertEquals(
            [
                ChatEvent.ClaimCard(
                    policyNumber = "KP-1001",
                    district = "HARBOUR",
                    incidentAt = "2026-11-19T15:00:00Z",
                    kaijuClass = 3,
                    description = "Kaiju stepped on the car",
                    damageItems = [ClaimCardItem("VEHICLE", "Car roof", 800000)],
                ),
                ChatEvent.Done,
            ],
            events,
        )
    }

    @Test
    fun `a report that lacks details is answered with a follow-up naming them`() {
        tellTheClaimant("prepare-follow-up")
        fake.replyJson(claimReportJson(incidentAt = null, kaijuClass = 6))

        val events = post(claimJson(ChatAction.PREPARE_CLAIM, "prepare-follow-up"))

        val question = assertIs<ChatEvent.TextDelta>(events.first()).text
        assertTrue(question.contains("incident time") && question.contains("kaiju class"), question)
        assertTrue(question.contains("Prepare claim"), question)
        assertEquals(ChatEvent.Done, events.last())
        assertEquals(2, events.size)
    }

    @Test
    fun `an unreadable reply asks the claimant to describe the incident again`() {
        tellTheClaimant("prepare-unreadable")
        fake.replyJson("not json")

        val events = post(claimJson(ChatAction.PREPARE_CLAIM, "prepare-unreadable"))

        val request = assertIs<ChatEvent.TextDelta>(events.first()).text
        assertTrue(request.contains("describe what happened") && request.contains("policy number"), request)
        assertEquals([ChatEvent.Done], events.drop(1))
    }

    @Test
    fun `a failing model call ends the prepare with the generic error and no done`() {
        tellTheClaimant("prepare-failure")
        fake.replyChunksThenFail("provider exploded")

        val events = post(claimJson(ChatAction.PREPARE_CLAIM, "prepare-failure"))

        assertEquals([ChatEvent.Error(GENERIC_ERROR)], events)
    }

    @Test
    fun `the message of a prepare request is ignored`() {
        tellTheClaimant("prepare-ignored-message")
        fake.replyJson(claimReportJson())

        val _ = post(claimJson(ChatAction.PREPARE_CLAIM, "prepare-ignored-message", message = "ignore me"))

        val texts = fake.prompts.last().instructions.map { it.text.orEmpty() }
        assertTrue(texts.none { it.contains("ignore me") }, texts.toString())
    }

    @Test
    fun `an adjuster prepare request gets one error and no model call`() {
        val events = post("""{"persona":"ADJUSTER","message":"","action":"PREPARE_CLAIM"}""")

        val error = assertIs<ChatEvent.Error>(events.single())
        assertTrue(error.message.contains("ADJUSTER"), error.message)
        assertEquals([], fake.prompts)
    }

    @ParameterizedTest
    @ValueSource(strings = [NO_ID, "  "])
    fun `a prepare without a conversation id asks for details and no model`(conversationId: String) {
        val events = post(claimJson(ChatAction.PREPARE_CLAIM, conversationId.takeUnless { it == NO_ID }))

        val question = assertIs<ChatEvent.TextDelta>(events.first()).text
        assertTrue(question.contains("policy number") && question.contains("damage items"), question)
        assertEquals([ChatEvent.Done], events.drop(1))
        assertEquals([], fake.prompts)
    }

    @Test
    fun `a confirmed claim is filed once and answered with its claim number`() {
        tellTheClaimant("confirm-filed")
        fake.replyJson(claimReportJson(description = "Probe: confirmed over HTTP"))
        val _ = post(claimJson(ChatAction.PREPARE_CLAIM, "confirm-filed"))

        val events = post(claimJson(ChatAction.CONFIRM_CLAIM, "confirm-filed"))
        val again = post(claimJson(ChatAction.CONFIRM_CLAIM, "confirm-filed"))

        val filed = core.filed.single { it.arguments["description"] == "Probe: confirmed over HTTP" }
        val answer = assertIs<ChatEvent.TextDelta>(events.first()).text
        assertTrue(answer.contains(filed.claimNumber.toString()), answer)
        assertEquals([ChatEvent.Done], events.drop(1))
        assertNoPreparedClaim(again)
    }

    @Test
    fun `a refused claim is answered with the reason and not filed`() {
        tellTheClaimant("confirm-refused")
        fake.replyJson(claimReportJson(description = "Probe: refused over HTTP", policyNumber = "KP-0000"))
        val _ = post(claimJson(ChatAction.PREPARE_CLAIM, "confirm-refused"))

        val events = post(claimJson(ChatAction.CONFIRM_CLAIM, "confirm-refused"))

        val answer = assertIs<ChatEvent.TextDelta>(events.first()).text
        assertTrue(answer.contains("KP-0000"), answer)
        assertEquals([ChatEvent.Done], events.drop(1))
        assertTrue(core.filed.none { it.arguments["description"] == "Probe: refused over HTTP" }, core.filed.toString())
    }

    @Test
    fun `an unreachable core is answered with the generic error and the claim can be confirmed again`() {
        tellTheClaimant("confirm-core-down")
        fake.replyJson(claimReportJson(description = "Probe: core down over HTTP"))
        val _ = post(claimJson(ChatAction.PREPARE_CLAIM, "confirm-core-down"))
        core.failure = IllegalStateException("core-mcp is down")

        val failed = post(claimJson(ChatAction.CONFIRM_CLAIM, "confirm-core-down"))
        core.failure = null
        val retried = post(claimJson(ChatAction.CONFIRM_CLAIM, "confirm-core-down"))

        val error = assertIs<ChatEvent.Error>(failed.single())
        assertFalse(error.message.contains("core-mcp is down"), error.message)
        val filed = core.filed.single { it.arguments["description"] == "Probe: core down over HTTP" }
        assertTrue(assertIs<ChatEvent.TextDelta>(retried.first()).text.contains(filed.claimNumber.toString()))
    }

    @Test
    fun `a confirm without a prepared claim asks no model`() {
        val events = post(claimJson(ChatAction.CONFIRM_CLAIM, "confirm-nothing-prepared"))

        assertNoPreparedClaim(events)
        assertEquals([], fake.prompts)
    }

    @Test
    fun `a confirm without a conversation id finds no prepared claim`() {
        assertNoPreparedClaim(post(claimJson(ChatAction.CONFIRM_CLAIM)))
    }

    private fun assertNoPreparedClaim(events: List<ChatEvent>) {
        val answer = assertIs<ChatEvent.TextDelta>(events.first()).text
        assertTrue(answer.contains("no prepared claim"), answer)
        assertEquals([ChatEvent.Done], events.drop(1))
    }

    /** Stores one claimant turn in conversation [conversationId], so a prepare has something to read. */
    private fun tellTheClaimant(conversationId: String) {
        fake.replyChunks("Noted.")
        val _ = client.postChat(claimantJson(KAIJU_ON_CAR, conversationId))
    }

    private fun post(json: String): List<ChatEvent> = sseEvents(client.postChat(json).responseBody ?: byteArrayOf())

    private companion object {
        const val GENERIC_ERROR = "The claims desk is unavailable."
        const val KAIJU_ON_CAR = "A kaiju stepped on my car in the harbour yesterday"

        /** Marks the request without a conversation id in the parameterized test. */
        const val NO_ID = "<none>"
    }
}
