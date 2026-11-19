package dev.frederikpietzko.springaidemo.backend.intake

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeClaimantCore
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimReportJson
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Prepared claims and their confirmation, filed through the real filing adapter into the fake claimant connection to
 * the core. The fake is shared by all tests of the context, so every test files a claim with its own description and
 * asserts on that, never on counts.
 */
@IntegrationTest
class ClaimConfirmationTest {
    @Autowired
    private lateinit var intake: ClaimIntake

    @Autowired
    private lateinit var core: FakeClaimantCore

    @Autowired
    private lateinit var fake: FakeChatModel

    @Test
    fun `a prepared claim is filed once in the core and then forgotten`() {
        val _ = prepareCard("confirm-once", "Probe: filed once")

        val first = intake.confirm("confirm-once")
        val second = intake.confirm("confirm-once")

        val filed = assertIs<FilingResult.Filed>(first)
        val inCore = filedWith("Probe: filed once").single()
        assertEquals(inCore.claimNumber, filed.claimNumber)
        assertNoPreparedClaim(second)
    }

    @Test
    fun `the claim of the card reaches the core's fileClaim tool as the demo claimant`() {
        val _ = prepareCard("confirm-arguments", "Probe: arguments")

        val _ = intake.confirm("confirm-arguments")

        val filed = filedWith("Probe: arguments").single()
        val expectedArguments: Map<String, Any?> = mapOf(
            "policyNumber" to "KP-1001",
            "district" to "HARBOUR",
            "incidentAt" to "2026-11-19T15:00:00Z",
            "kaijuClass" to 3,
            "description" to "Probe: arguments",
            "damageItems" to [
                mapOf("category" to "VEHICLE", "description" to "Car roof", "estimatedAmountMinor" to 800_000L),
            ],
        )
        assertEquals(expectedArguments, filed.arguments)
        assertEquals(mapOf<String, Any?>("actor" to "claimant-agent", "claimantId" to 1L), filed.meta)
    }

    @Test
    fun `an unreachable core fails the confirmation and keeps the claim prepared`() {
        val _ = prepareCard("confirm-core-down", "Probe: core down")
        core.failure = IllegalStateException("core-mcp is down")

        val _ = assertFailsWith<IllegalStateException> { intake.confirm("confirm-core-down") }
        core.failure = null
        val retried = intake.confirm("confirm-core-down")

        assertIs<FilingResult.Filed>(retried)
        assertEquals(1, filedWith("Probe: core down").size)
    }

    @Test
    fun `a conversation that was never prepared has nothing to confirm`() {
        assertNoPreparedClaim(intake.confirm("confirm-unknown"))
    }

    @Test
    fun `confirming another conversation leaves the prepared claim alone`() {
        val _ = prepareCard("confirm-isolated-a", "Probe: isolated")

        val other = intake.confirm("confirm-isolated-b")
        val own = intake.confirm("confirm-isolated-a")

        assertNoPreparedClaim(other)
        assertIs<FilingResult.Filed>(own)
        assertEquals(1, filedWith("Probe: isolated").size)
    }

    @Test
    fun `preparing again replaces the prepared claim`() {
        val _ = prepareCard("confirm-replaced", "Probe: replaced first")
        val _ = prepareCard("confirm-replaced", "Probe: replaced second")

        val _ = intake.confirm("confirm-replaced")

        assertEquals([], filedWith("Probe: replaced first"))
        assertEquals(1, filedWith("Probe: replaced second").size)
    }

    @Test
    fun `a follow-up drops the earlier prepared claim`() {
        val _ = prepareCard("confirm-follow-up", "Probe: follow-up")
        fake.replyJson(claimReportJson(description = "Probe: follow-up", policyNumber = null))
        val followUp = intake.prepare("confirm-follow-up", [UserMessage(KAIJU_ON_CAR)])

        val confirmed = intake.confirm("confirm-follow-up")

        assertIs<IntakeResult.NeedsDetails>(followUp)
        assertNoPreparedClaim(confirmed)
        assertEquals([], filedWith("Probe: follow-up"))
    }

    @Test
    fun `an unreadable reply drops the earlier prepared claim`() {
        val _ = prepareCard("confirm-unreadable", "Probe: unreadable")
        fake.replyJson("not json")
        val _ = intake.prepare("confirm-unreadable", [UserMessage(KAIJU_ON_CAR)])

        assertNoPreparedClaim(intake.confirm("confirm-unreadable"))
        assertEquals([], filedWith("Probe: unreadable"))
    }

    @Test
    fun `a failing model call drops the earlier prepared claim`() {
        val _ = prepareCard("confirm-failure", "Probe: failure")
        fake.replyChunksThenFail("provider exploded")
        val _ = assertFailsWith<IllegalStateException> {
            intake.prepare("confirm-failure", [UserMessage(KAIJU_ON_CAR)])
        }

        assertNoPreparedClaim(intake.confirm("confirm-failure"))
        assertEquals([], filedWith("Probe: failure"))
    }

    @Test
    fun `an empty conversation drops the earlier prepared claim`() {
        val _ = prepareCard("confirm-emptied", "Probe: emptied")
        val _ = intake.prepare("confirm-emptied", [])

        assertNoPreparedClaim(intake.confirm("confirm-emptied"))
        assertEquals([], filedWith("Probe: emptied"))
    }

    @Test
    fun `a claim the core refuses is not filed, keeps the core's reason and stays prepared`() {
        val _ = prepareCard("confirm-refused", "Probe: refused", policyNumber = "KP-0000")

        val first = intake.confirm("confirm-refused")
        val second = intake.confirm("confirm-refused")

        val refused = assertIs<FilingResult.Rejected>(first)
        assertEquals("Policy KP-0000 not found", refused.reason)
        assertEquals(refused, second)
        assertEquals([], filedWith("Probe: refused"))
    }

    @Test
    fun `filed claims get the distinct numbers the core assigns`() {
        val _ = prepareCard("confirm-numbers-a", "Probe: numbers a")
        val _ = prepareCard("confirm-numbers-b", "Probe: numbers b")

        val first = assertIs<FilingResult.Filed>(intake.confirm("confirm-numbers-a")).claimNumber
        val second = assertIs<FilingResult.Filed>(intake.confirm("confirm-numbers-b")).claimNumber

        assertEquals(filedWith("Probe: numbers a").single().claimNumber, first)
        assertEquals(filedWith("Probe: numbers b").single().claimNumber, second)
        assertTrue(first != second, "$first, $second")
    }

    /** Prepares a claim card for [conversationId] from a complete report with [description] and [policyNumber]. */
    private fun prepareCard(
        conversationId: String,
        description: String,
        policyNumber: String = "KP-1001",
    ): IntakeResult.Ready {
        fake.replyJson(claimReportJson(description = description, policyNumber = policyNumber))
        return assertIs<IntakeResult.Ready>(intake.prepare(conversationId, [UserMessage(KAIJU_ON_CAR)]))
    }

    private fun filedWith(description: String): List<FakeClaimantCore.FiledClaim> =
        core.filed.filter { it.arguments["description"] == description }

    private fun assertNoPreparedClaim(result: FilingResult) {
        val rejected = assertIs<FilingResult.Rejected>(result)
        assertTrue(rejected.reason.contains("no prepared claim"), rejected.reason)
    }

    private companion object {
        const val KAIJU_ON_CAR = "A kaiju sat on my car"
    }
}
