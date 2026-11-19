package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.StatusUpdate
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.sandboxClaimIn
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The transition table: `FILED` -> `UNDER_REVIEW` or `REJECTED`, `UNDER_REVIEW` -> `APPROVED` or `REJECTED`. */
@CoreMcpIntegrationTest
class ClaimServiceStatusTest {
    @Autowired
    private lateinit var claims: ClaimService

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("FILED, UNDER_REVIEW", "FILED, REJECTED", "UNDER_REVIEW, APPROVED", "UNDER_REVIEW, REJECTED")
    fun `an allowed transition updates the claim`(from: ClaimStatus, to: ClaimStatus) {
        val claimNumber = claims.sandboxClaimIn(from)

        val result = claims.updateStatus(claimNumber, to)

        assertEquals(to, assertIs<StatusUpdate.Updated>(result).claim.status)
        assertEquals(to, statusOf(claimNumber))
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "FILED, APPROVED",
        "FILED, PAID",
        "UNDER_REVIEW, FILED",
        "APPROVED, PAID",
        "APPROVED, REJECTED",
        "REJECTED, FILED",
        "REJECTED, UNDER_REVIEW",
        "REJECTED, APPROVED",
    )
    fun `an illegal transition is refused and the status stays`(from: ClaimStatus, to: ClaimStatus) {
        val claimNumber = claims.sandboxClaimIn(from)

        val result = claims.updateStatus(claimNumber, to)

        assertEquals(StatusUpdate.IllegalTransition(from, to), result)
        assertEquals(from, statusOf(claimNumber))
    }

    @Test
    fun `a status change on an unknown claim is refused`() {
        val result = claims.updateStatus(999999, ClaimStatus.UNDER_REVIEW)

        assertEquals(StatusUpdate.NotFound, result)
    }

    private fun statusOf(claimNumber: Long): ClaimStatus =
        assertIs<ClaimLookup.Found>(claims.find(claimNumber)).claim.status
}
