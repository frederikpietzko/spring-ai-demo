package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.APPROVED
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.PAID
import dev.frederikpietzko.springaidemo.coremcp.domain.PayoutResult
import dev.frederikpietzko.springaidemo.coremcp.domain.PayoutService
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.sandboxClaimIn
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Payouts on sandbox claims (policy `KP-9000`, deductible 10000). */
@CoreMcpIntegrationTest
class PayoutServiceTest {
    @Autowired
    private lateinit var payouts: PayoutService

    @Autowired
    private lateinit var claims: ClaimService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `an approved claim is paid its total minus the deductible and becomes PAID`() {
        val claimNumber = claims.sandboxClaimIn(APPROVED, [30000, 5000])

        val result = payouts.issue(claimNumber)

        val payout = assertIs<PayoutResult.Issued>(result).payout
        assertEquals(25000, payout.amountMinor)
        val claim = assertIs<ClaimLookup.Found>(claims.find(claimNumber)).claim
        assertEquals(PAID, claim.status)
        assertEquals(payout, claim.payout)
    }

    @ParameterizedTest
    @EnumSource(names = ["FILED", "UNDER_REVIEW", "REJECTED"])
    fun `a claim that is not approved gets no payout`(status: ClaimStatus) {
        val claimNumber = claims.sandboxClaimIn(status)

        val result = payouts.issue(claimNumber)

        assertEquals(PayoutResult.NotApproved(status), result)
        assertEquals(0, payoutRows(claimNumber))
    }

    @Test
    fun `damage that does not exceed the deductible pays nothing and the claim stays approved`() {
        val claimNumber = claims.sandboxClaimIn(APPROVED, [10000])

        val result = payouts.issue(claimNumber)

        assertEquals(PayoutResult.NothingPayable(10000, 10000), result)
        assertEquals(0, payoutRows(claimNumber))
        assertEquals(APPROVED, assertIs<ClaimLookup.Found>(claims.find(claimNumber)).claim.status)
    }

    @Test
    fun `a second call returns the existing payout and stores no second one`() {
        val claimNumber = claims.sandboxClaimIn(APPROVED, [30000, 5000])
        val first = assertIs<PayoutResult.Issued>(payouts.issue(claimNumber)).payout

        val second = payouts.issue(claimNumber)

        assertEquals(PayoutResult.AlreadyIssued(first), second)
        assertEquals(1, payoutRows(claimNumber))
    }

    @Test
    fun `the database refuses a second payout row for a claim`() {
        val claimNumber = claims.sandboxClaimIn(APPROVED, [30000])
        assertIs<PayoutResult.Issued>(payouts.issue(claimNumber))

        assertFailsWith<DuplicateKeyException> {
            jdbc.update("INSERT INTO payouts (claim_id, amount_minor, issued_at) VALUES (?, 1, now())", claimNumber)
        }
    }

    @Test
    fun `an unknown claim gets no payout`() {
        val result = payouts.issue(999999)

        assertEquals(PayoutResult.NotFound, result)
    }

    private fun payoutRows(claimNumber: Long): Int? =
        jdbc.queryForObject<Int>("SELECT count(*) FROM payouts WHERE claim_id = ?", claimNumber)
}
