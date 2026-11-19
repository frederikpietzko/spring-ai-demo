package dev.frederikpietzko.springaidemo.coremcp.domain

import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Claims
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Customers
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.DamageItems
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.DamageItems.estimatedAmountMinor
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Payouts
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Policies
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

private const val EXAMPLE_TIME = "2026-11-18T15:00:00Z"

/** Reads, files and moves claims. */
@Service
@Transactional
@MustUseReturnValues
class ClaimService(private val policies: PolicyService, private val clock: Clock) {
    /** The claim with [claimNumber], or [ClaimLookup.NotFound]. */
    @Transactional(readOnly = true)
    fun find(claimNumber: Long): ClaimLookup {
        val row = claimRow(claimNumber) ?: return ClaimLookup.NotFound
        return ClaimLookup.Found(toClaim(row))
    }

    /** The summaries of all claims on the policies of customer [customerId], or [CustomerClaims.UnknownCustomer]. */
    @Transactional(readOnly = true)
    fun listForCustomer(customerId: Long): CustomerClaims {
        if (Customers.selectAll().where { Customers.id eq customerId }.empty()) return CustomerClaims.UnknownCustomer
        val summaries = (Claims innerJoin Policies).selectAll()
            .where { Policies.customer eq customerId }
            .orderBy(Claims.id)
            .map { toClaim(it).summary() }
        return CustomerClaims.Found(summaries)
    }

    /** Files a new claim with status [ClaimStatus.FILED] after checking [request] against the rules and the policy. */
    fun file(request: FileClaimRequest): FileResult {
        val invalid = validate(request)
        val incidentAt = parseIncidentAt(request.incidentAt)
        val policy = (policies.find(request.policyNumber) as? PolicyLookup.Found)?.policy
        return when {
            invalid != null -> invalid
            incidentAt == null -> FileResult.Invalid("incidentAt", "must be ISO-8601 with offset, e.g. $EXAMPLE_TIME")
            policy == null -> FileResult.PolicyNotFound
            incidentAt.utcDate() !in policy.validFrom..policy.validTo ->
                FileResult.IncidentOutsidePolicy(policy.validFrom, policy.validTo)
            else -> FileResult.Filed(insert(request, incidentAt))
        }
    }

    /** Moves the claim to [target] if the transition table allows it. */
    fun updateStatus(claimNumber: Long, target: ClaimStatus): StatusUpdate {
        val current = Claims.select(Claims.status)
            .where { Claims.id eq claimNumber }
            .forUpdate()
            .singleOrNull()
            ?.get(Claims.status) ?: return StatusUpdate.NotFound
        return if (target in ALLOWED_TRANSITIONS[current].orEmpty()) {
            Claims.update({ Claims.id eq claimNumber }) { it[status] = target }
            StatusUpdate.Updated(load(claimNumber))
        } else {
            StatusUpdate.IllegalTransition(current, target)
        }
    }

    private fun validate(request: FileClaimRequest): FileResult.Invalid? =
        when {
            request.kaijuClass !in KAIJU_CLASS_RANGE -> FileResult.Invalid("kaijuClass", "must be between 1 and 5")
            request.description.isBlank() -> FileResult.Invalid("description", "must not be blank")
            request.damageItems.isEmpty() -> FileResult.Invalid("damageItems", "at least one damage item is required")
            else -> request.damageItems.withIndex().firstNotNullOfOrNull { (index, value) -> validate(index, value) }
        }

    private fun validate(index: Int, item: DamageItem): FileResult.Invalid? =
        when {
            item.estimatedAmountMinor <= 0 ->
                FileResult.Invalid("damageItems[$index].estimatedAmountMinor", "must be greater than zero")
            item.description.isBlank() -> FileResult.Invalid("damageItems[$index].description", "must not be blank")
            else -> null
        }

    /** Stores the claim and its damage items (one transaction) and reads it back. */
    private fun insert(request: FileClaimRequest, incidentAt: OffsetDateTime): Claim {
        val policyRow = Policies.select(Policies.id).where { Policies.policyNumber eq request.policyNumber }.single()
        val claimId = Claims.insertAndGetId {
            it[policy] = policyRow[Policies.id]
            it[status] = ClaimStatus.FILED
            it[district] = request.district
            it[Claims.incidentAt] = incidentAt
            it[kaijuClass] = request.kaijuClass
            it[description] = request.description
            it[filedAt] = OffsetDateTime.now(clock)
        }
        request.damageItems.forEach { item ->
            DamageItems.insert {
                it[claim] = claimId
                it[category] = item.category
                it[description] = item.description
                it[estimatedAmountMinor] = item.estimatedAmountMinor
            }
        }
        return load(claimId.value)
    }

    private fun claimRow(claimNumber: Long): ResultRow? =
        (Claims innerJoin Policies).selectAll().where { Claims.id eq claimNumber }.singleOrNull()

    /** A claim that must exist, for example one this transaction just wrote. */
    private fun load(claimNumber: Long): Claim =
        toClaim(checkNotNull(claimRow(claimNumber)) { "claim $claimNumber vanished inside its transaction" })

    private fun toClaim(row: ResultRow): Claim {
        val claimId = row[Claims.id]
        val items = DamageItems.selectAll()
            .where { DamageItems.claim eq claimId }
            .orderBy(DamageItems.id)
            .map { DamageItem(it[DamageItems.category], it[DamageItems.description], it[estimatedAmountMinor]) }
        val payout = Payouts.selectAll()
            .where { Payouts.claim eq claimId }
            .singleOrNull()
            ?.let { Payout(it[Payouts.amountMinor], it[Payouts.issuedAt].toInstant()) }
        return Claim(
            claimNumber = claimId.value,
            policyNumber = row[Policies.policyNumber],
            customerId = row[Policies.customer].value,
            status = row[Claims.status],
            district = row[Claims.district],
            incidentAt = row[Claims.incidentAt].toInstant(),
            kaijuClass = row[Claims.kaijuClass],
            description = row[Claims.description],
            damageItems = items,
            payout = payout,
        )
    }
}

private fun parseIncidentAt(text: String): OffsetDateTime? =
    try {
        OffsetDateTime.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

private fun OffsetDateTime.utcDate() = withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
