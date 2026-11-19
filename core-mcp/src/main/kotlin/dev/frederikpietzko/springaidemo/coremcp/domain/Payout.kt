package dev.frederikpietzko.springaidemo.coremcp.domain

import java.time.Instant

/** The money paid for a claim (at most one per claim), in minor units. */
data class Payout(val amountMinor: Long, val issuedAt: Instant)

/** Result of issuing the payout of a claim. */
sealed interface PayoutResult {
    /** A new payout was stored and the claim is now `PAID`. */
    data class Issued(val payout: Payout) : PayoutResult

    /** The claim was already paid: this is its one existing payout. */
    data class AlreadyIssued(val payout: Payout) : PayoutResult

    data object NotFound : PayoutResult

    data class NotApproved(val status: ClaimStatus) : PayoutResult

    /** The damage does not exceed the deductible, so there is nothing to pay. */
    data class NothingPayable(val totalEstimatedMinor: Long, val deductibleMinor: Long) : PayoutResult
}
