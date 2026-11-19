package dev.frederikpietzko.springaidemo.coremcp.domain

import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Claims
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Payouts
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Counts and aggregates claims by a typed filter, in SQL. No free SQL reaches the database. */
@Service
@Transactional(readOnly = true)
@MustUseReturnValues
class AnalyticsService {
    /** The number of claims matching [filter], or [InvalidFilter]. */
    fun countClaims(filter: ClaimFilter): CountResult {
        invalid(filter)?.let { return it }
        return CountResult.Counted(Claims.selectAll().matching(filter).count())
    }

    /** Count, total and average of the payouts of the claims matching [filter], per [groupBy] value or in total. */
    fun payoutStats(filter: ClaimFilter, groupBy: StatsGrouping?): StatsResult {
        invalid(filter)?.let { return it }
        val payouts = Payouts.id.count()
        val total = Payouts.amountMinor.sum()
        val group: Column<*>? = when (groupBy) {
            StatsGrouping.DISTRICT -> Claims.district
            StatsGrouping.KAIJU_CLASS -> Claims.kaijuClass
            null -> null
        }
        val query = (Claims innerJoin Payouts).select(listOfNotNull(group, payouts, total)).matching(filter)
        group?.let { query.groupBy(it).orderBy(it) }
        // Without grouping SQL answers one row even when nothing matches; its sum is null, so it becomes no group.
        val groups = query.mapNotNull { row ->
            row[total]?.let { totalPaid ->
                PayoutGroup(
                    group = group?.let { row[it].toString() } ?: "ALL",
                    payouts = row[payouts],
                    totalPaidMinor = totalPaid,
                    averagePaidMinor = averageHalfUp(totalPaid, row[payouts]),
                )
            }
        }
        return StatsResult.Stats(groupBy, groups)
    }

    /** The first refused value of [filter], or `null` when every given value is valid. */
    private fun invalid(filter: ClaimFilter): InvalidFilter? {
        val from = filter.from?.let(::parseDate)
        val to = filter.to?.let(::parseDate)
        return when {
            filter.from != null && from == null -> InvalidFilter("from '${filter.from}' is not a date like 2026-11-01")
            filter.to != null && to == null -> InvalidFilter("to '${filter.to}' is not a date like 2026-11-30")
            filter.kaijuClass != null && filter.kaijuClass !in KAIJU_CLASS_RANGE ->
                InvalidFilter("kaijuClass ${filter.kaijuClass} is not between 1 and 5")
            from != null && to != null && from > to -> InvalidFilter("from $from is after to $to")
            else -> null
        }
    }
}

/**
 * The exact average rounded half up (2.5 becomes 3). Not SQL `avg`: Exposed reads it as a decimal rounded half to
 * even, so the half-up rounding would come too late.
 */
private fun averageHalfUp(total: Long, count: Long): Long =
    total.toBigDecimal().divide(count.toBigDecimal(), 0, RoundingMode.HALF_UP).toLong()

/** One condition per given field; the period covers whole UTC days, `to` included. Call after [invalid]. */
private fun Query.matching(filter: ClaimFilter): Query = apply {
    filter.district?.let { andWhere { Claims.district eq it } }
    filter.kaijuClass?.let { andWhere { Claims.kaijuClass eq it } }
    filter.status?.let { andWhere { Claims.status eq it } }
    filter.from?.let { andWhere { Claims.incidentAt greaterEq startOfUtcDay(LocalDate.parse(it)) } }
    filter.to?.let { andWhere { Claims.incidentAt less startOfUtcDay(LocalDate.parse(it).plusDays(1)) } }
}

private fun startOfUtcDay(day: LocalDate): OffsetDateTime = day.atStartOfDay().atOffset(ZoneOffset.UTC)

private fun parseDate(text: String): LocalDate? =
    try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
