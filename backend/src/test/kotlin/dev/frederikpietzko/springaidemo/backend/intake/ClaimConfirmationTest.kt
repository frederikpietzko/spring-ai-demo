package dev.frederikpietzko.springaidemo.backend.intake

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
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
 * Prepared claims and their confirmation, filed through the real in-memory adapter. The adapter is shared by all tests
 * of the context, so every test files a claim with its own description and asserts on that, never on counts.
 */
@IntegrationTest
class ClaimConfirmationTest {
    @Autowired
    private lateinit var intake: ClaimIntake

    @Autowired
    private lateinit var filing: InMemoryClaimFiling

    @Autowired
    private lateinit var fake: FakeChatModel

    @Test
    fun `a prepared claim is filed once and then forgotten`() {
        val ready = prepareCard("confirm-once", "Probe: filed once")

        val first = intake.confirm("confirm-once")
        val second = intake.confirm("confirm-once")

        val filed = assertIs<FilingResult.Filed>(first)
        assertEquals([FiledClaim(filed.claimNumber, ready.claim)], filedWith("Probe: filed once"))
        assertNoPreparedClaim(second)
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
    fun `a refused policy is not filed and stays prepared`() {
        val _ = prepareCard("confirm-refused", "Probe: refused", policyNumber = "KP-0000")

        val first = intake.confirm("confirm-refused")
        val second = intake.confirm("confirm-refused")

        val refused = assertIs<FilingResult.Rejected>(first)
        assertTrue(refused.reason.contains("KP-0000"), refused.reason)
        assertEquals(refused, second)
        assertEquals([], filedWith("Probe: refused"))
    }

    @Test
    fun `filed claims get distinct numbers from 4714 upwards`() {
        val _ = prepareCard("confirm-numbers-a", "Probe: numbers a")
        val _ = prepareCard("confirm-numbers-b", "Probe: numbers b")

        val first = assertIs<FilingResult.Filed>(intake.confirm("confirm-numbers-a")).claimNumber
        val second = assertIs<FilingResult.Filed>(intake.confirm("confirm-numbers-b")).claimNumber

        assertTrue(first != second && first >= FIRST_CLAIM_NUMBER && second >= FIRST_CLAIM_NUMBER, "$first, $second")
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

    private fun filedWith(description: String): List<FiledClaim> =
        filing.filed.filter { it.claim.description == description }

    private fun assertNoPreparedClaim(result: FilingResult) {
        val rejected = assertIs<FilingResult.Rejected>(result)
        assertTrue(rejected.reason.contains("no prepared claim"), rejected.reason)
    }

    private companion object {
        const val KAIJU_ON_CAR = "A kaiju sat on my car"
    }
}
