package dev.frederikpietzko.springaidemo.coremcp.domain

import java.time.LocalDate

/** An insurance policy with its holder. Amounts are minor units of one fictional currency. */
data class Policy(
    val policyNumber: String,
    val customerId: Long,
    val holderName: String,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val deductibleMinor: Long,
    val categories: List<DamageCategory>,
)

/** Result of looking up a policy by its number. */
sealed interface PolicyLookup {
    data class Found(val policy: Policy) : PolicyLookup

    data object NotFound : PolicyLookup
}
