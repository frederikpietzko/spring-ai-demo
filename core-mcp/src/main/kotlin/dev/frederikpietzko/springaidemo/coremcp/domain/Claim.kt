package dev.frederikpietzko.springaidemo.coremcp.domain

import java.time.Instant
import java.time.LocalDate

/** A claim against a policy; its number is the database id. Amounts are minor units of one fictional currency. */
data class Claim(
    val claimNumber: Long,
    val policyNumber: String,
    val customerId: Long,
    val status: ClaimStatus,
    val district: District,
    val incidentAt: Instant,
    val kaijuClass: Int,
    val description: String,
    val damageItems: List<DamageItem>,
    val payout: Payout?,
) {
    /** Sum of the estimated amounts of all damage items. */
    val totalEstimatedMinor: Long get() = damageItems.sumOf { it.estimatedAmountMinor }

    fun summary(): ClaimSummary =
        ClaimSummary(claimNumber, policyNumber, status, district, incidentAt, totalEstimatedMinor)
}

/** One damaged thing of a claim with its estimated cost. */
data class DamageItem(val category: DamageCategory, val description: String, val estimatedAmountMinor: Long)

/** The short form of a claim for lists. */
data class ClaimSummary(
    val claimNumber: Long,
    val policyNumber: String,
    val status: ClaimStatus,
    val district: District,
    val incidentAt: Instant,
    val totalEstimatedMinor: Long,
)

/** What a claimant reports. [incidentAt] is ISO-8601 with an offset; the service parses it and refuses a bad one. */
data class FileClaimRequest(
    val policyNumber: String,
    val district: District,
    val incidentAt: String,
    val kaijuClass: Int,
    val description: String,
    val damageItems: List<DamageItem>,
)

/** Result of filing a claim. */
sealed interface FileResult {
    data class Filed(val claim: Claim) : FileResult

    data object PolicyNotFound : FileResult

    data class IncidentOutsidePolicy(val validFrom: LocalDate, val validTo: LocalDate) : FileResult

    /** [field] names the offending input, for example `kaijuClass` or `damageItems[0].estimatedAmountMinor`. */
    data class Invalid(val field: String, val reason: String) : FileResult
}

/** Result of looking up a claim by its number. */
sealed interface ClaimLookup {
    data class Found(val claim: Claim) : ClaimLookup

    data object NotFound : ClaimLookup
}

/** Result of listing the claims of a customer. */
sealed interface CustomerClaims {
    data class Found(val summaries: List<ClaimSummary>) : CustomerClaims

    data object UnknownCustomer : CustomerClaims
}

/** Result of a status change. */
sealed interface StatusUpdate {
    data class Updated(val claim: Claim) : StatusUpdate

    data object NotFound : StatusUpdate

    data class IllegalTransition(val from: ClaimStatus, val to: ClaimStatus) : StatusUpdate
}
