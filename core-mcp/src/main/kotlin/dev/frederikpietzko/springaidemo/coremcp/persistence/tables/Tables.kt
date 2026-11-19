package dev.frederikpietzko.springaidemo.coremcp.persistence.tables

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory
import dev.frederikpietzko.springaidemo.coremcp.domain.District
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// The tables of the database `core`. `:core-mcp:generateMigrations` turns them into Flyway SQL (ADR-0002).
// Amounts are whole minor units of one fictional currency; timestamps carry their offset (UTC in the seed).

private const val NAME_LENGTH = 200
private const val CODE_LENGTH = 20

object Customers : LongIdTable("customers") {
    val name = varchar("name", NAME_LENGTH)
}

object Policies : LongIdTable("policies") {
    val policyNumber = varchar("policy_number", CODE_LENGTH).uniqueIndex()
    val customer = reference("customer_id", Customers)
    val validFrom = date("valid_from")
    val validTo = date("valid_to")
    val deductibleMinor = long("deductible_minor")
}

object Coverages : LongIdTable("coverages") {
    val policy = reference("policy_id", Policies)
    val category = enumerationByName<DamageCategory>("category", CODE_LENGTH)

    init {
        uniqueIndex(policy, category)
    }
}

/** The id of a claim is its claim number. */
object Claims : LongIdTable("claims") {
    val policy = reference("policy_id", Policies)
    val status = enumerationByName<ClaimStatus>("status", CODE_LENGTH)
    val district = enumerationByName<District>("district", CODE_LENGTH)
    val incidentAt = timestampWithTimeZone("incident_at")
    val kaijuClass = integer("kaiju_class")
    val description = text("description")
    val filedAt = timestampWithTimeZone("filed_at")
}

object DamageItems : LongIdTable("damage_items") {
    val claim = reference("claim_id", Claims)
    val category = enumerationByName<DamageCategory>("category", CODE_LENGTH)
    val description = text("description")
    val estimatedAmountMinor = long("estimated_amount_minor")
}

/** At most one payout per claim: the unique index is the backstop of the idempotent payout. */
object Payouts : LongIdTable("payouts") {
    val claim = reference("claim_id", Claims).uniqueIndex()
    val amountMinor = long("amount_minor")
    val issuedAt = timestampWithTimeZone("issued_at")
}
