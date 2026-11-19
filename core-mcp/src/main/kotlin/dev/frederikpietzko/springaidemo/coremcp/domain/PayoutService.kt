package dev.frederikpietzko.springaidemo.coremcp.domain

import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Claims
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.DamageItems
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Payouts
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Policies
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

/** Pays approved claims: at most one payout per claim, the policy deductible taken off once. */
@Service
@Transactional
@MustUseReturnValues
class PayoutService(private val clock: Clock) {
    /** Issues the payout of claim [claimNumber]; a second call returns the existing payout. */
    fun issue(claimNumber: Long): PayoutResult {
        // FOR UPDATE: two simultaneous calls for one claim run one after the other (the unique index is the backstop).
        val claim = (Claims innerJoin Policies).select(Claims.status, Policies.deductibleMinor)
            .where { Claims.id eq claimNumber }
            .forUpdate()
            .singleOrNull() ?: return PayoutResult.NotFound
        val status = claim[Claims.status]
        val total = totalEstimate(claimNumber)
        val deductible = claim[Policies.deductibleMinor]
        return when {
            status == ClaimStatus.PAID -> PayoutResult.AlreadyIssued(storedPayout(claimNumber))
            status != ClaimStatus.APPROVED -> PayoutResult.NotApproved(status)
            total <= deductible -> PayoutResult.NothingPayable(total, deductible)
            else -> PayoutResult.Issued(pay(claimNumber, amountMinor = total - deductible))
        }
    }

    private fun totalEstimate(claimNumber: Long): Long =
        DamageItems.select(DamageItems.estimatedAmountMinor)
            .where { DamageItems.claim eq claimNumber }
            .sumOf { it[DamageItems.estimatedAmountMinor] }

    /** Stores the payout and marks the claim `PAID`, in the caller's transaction. */
    private fun pay(claimNumber: Long, amountMinor: Long): Payout {
        Payouts.insert {
            it[claim] = claimNumber
            it[Payouts.amountMinor] = amountMinor
            it[issuedAt] = OffsetDateTime.now(clock)
        }
        Claims.update({ Claims.id eq claimNumber }) { it[status] = ClaimStatus.PAID }
        return storedPayout(claimNumber)
    }

    private fun storedPayout(claimNumber: Long): Payout {
        val row = Payouts.selectAll().where { Payouts.claim eq claimNumber }.single()
        return Payout(row[Payouts.amountMinor], row[Payouts.issuedAt].toInstant())
    }
}
