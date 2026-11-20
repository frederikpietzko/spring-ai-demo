package dev.frederikpietzko.springaidemo.backend.intake

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.time.OffsetDateTime

/** The kaiju classes a report may name: 1 (large lizard) to 5 (city-level). */
val KAIJU_CLASS_RANGE = 1..5

/** The districts of the city. */
enum class District { HARBOUR, OLD_TOWN, NEON_MARKET, RIVERSIDE, HILLTOP }

/** What kind of object a damage item is. */
enum class DamageCategory { VEHICLE, BUILDING, CONTENTS, GARDEN }

/**
 * The claim report the model extracts from the conversation: the typed draft of a claim. Every field is nullable, so
 * a fact the claimant has not given yet is a `null` that [validate] names, not a parse failure.
 */
data class ClaimReport(
    @field:JsonPropertyDescription("Policy number such as KP-1001; null if the claimant has not given it")
    val policyNumber: String?,
    @field:JsonPropertyDescription("District of the incident; null if unknown")
    val district: District?,
    @field:JsonPropertyDescription("Incident time, ISO-8601 with offset, e.g. 2026-11-19T15:00:00Z; null if unknown")
    val incidentAt: String?,
    @field:JsonPropertyDescription("Kaiju class from 1 (large lizard) to 5 (city-level); null if unknown")
    val kaijuClass: Int?,
    @field:JsonPropertyDescription("What happened, in the claimant's words")
    val description: String?,
    @field:JsonPropertyDescription("The damaged objects; null if none were named")
    val damageItems: List<DamageItem?>?,
)

/** One damaged object as the model reports it. */
data class DamageItem(
    val category: DamageCategory?,
    @field:JsonPropertyDescription("What was damaged")
    val description: String?,
    @field:JsonPropertyDescription("Estimated amount in whole minor units of the fictional currency")
    val estimatedAmountMinor: Long?,
)

/**
 * A validated claim report (ROADMAP glossary: Claim report), not a Claim: no status and no number until filed. Every
 * value is present and valid.
 */
data class ValidClaim(
    val policyNumber: String,
    val district: District,
    val incidentAt: OffsetDateTime,
    val kaijuClass: Int,
    val description: String,
    val damageItems: List<ValidDamageItem>,
)

/** A damage item of a [ValidClaim]: its amount is greater than zero. */
data class ValidDamageItem(val category: DamageCategory, val description: String, val estimatedAmountMinor: Long)

/** The outcome of preparing a claim from a conversation. */
sealed interface IntakeResult {
    /** The report is complete and valid. */
    data class Ready(val claim: ValidClaim) : IntakeResult

    /** The report lacks the [details] (short labels such as "incident time"), in report order. */
    data class NeedsDetails(val details: List<String>) : IntakeResult

    /** The model's reply could not be read as a claim report. */
    data object Unreadable : IntakeResult
}

/** [IntakeResult.Ready] when every detail is present and valid, else [IntakeResult.NeedsDetails] naming each one. */
fun ClaimReport.validate(): IntakeResult = with(Details()) {
    val policy = policyNumber?.takeIf { it.isNotBlank() }.orMissing("policy number")
    val district = district.orMissing("district")
    val incidentAt = incidentAt?.toIncidentTime().orMissing("incident time")
    val kaijuClass = kaijuClass?.takeIf { it in KAIJU_CLASS_RANGE }.orMissing("kaiju class (1 to 5)")
    val description = description?.takeIf { it.isNotBlank() }.orMissing("description")
    val items = damageItems?.takeIf { it.isNotEmpty() }.orMissing("damage items").orEmpty()
        .mapIndexed { index, item -> item.checked("damage item ${index + 1}") }
    if (missing.isNotEmpty()) return IntakeResult.NeedsDetails(missing.toList())
    // Every value is present here: an absent one was recorded as missing above.
    IntakeResult.Ready(
        ValidClaim(
            policyNumber = checkNotNull(policy),
            district = checkNotNull(district),
            incidentAt = checkNotNull(incidentAt),
            kaijuClass = checkNotNull(kaijuClass),
            description = checkNotNull(description),
            damageItems = items.filterNotNull(),
        ),
    )
}

/** Collects the details a report lacks while its values are checked one by one. */
private class Details {
    val missing = mutableListOf<String>()

    /** This value, or `null` with [detail] recorded as missing when it is absent or was rejected. */
    fun <T : Any> T?.orMissing(detail: String): T? = also { if (it == null) missing += detail }

    /** This item as a valid one, or `null` with its missing parts (or the whole item) recorded under [label]. */
    fun DamageItem?.checked(label: String): ValidDamageItem? {
        val item = orMissing(label) ?: return null
        val category = item.category.orMissing("$label: category")
        val description = item.description?.takeIf { it.isNotBlank() }.orMissing("$label: description")
        val amount = item.estimatedAmountMinor?.takeIf { it > 0 }.orMissing("$label: amount")
        return if (category == null || description == null || amount == null) {
            null
        } else {
            ValidDamageItem(category, description, amount)
        }
    }
}

private fun String.toIncidentTime(): OffsetDateTime? = runCatching { OffsetDateTime.parse(this) }.getOrNull()
