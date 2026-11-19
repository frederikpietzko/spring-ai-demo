package dev.frederikpietzko.springaidemo.coremcp.domain

/**
 * Which claims an analytics question is about. Every field is optional; the given ones must all match. [from] and [to]
 * are inclusive ISO dates (`2026-11-01`) of the incident day in UTC; the service parses them and refuses a bad one.
 */
data class ClaimFilter(
    val district: District? = null,
    val kaijuClass: Int? = null,
    val status: ClaimStatus? = null,
    val from: String? = null,
    val to: String? = null,
)

/**
 * What payout statistics can be grouped by. No `STATUS`: only paid claims have a payout, so it would always be one
 * `PAID` group (`status` stays a filter field).
 */
enum class StatsGrouping { DISTRICT, KAIJU_CLASS }

/**
 * The payouts of one group: [group] is the district name, the kaiju class or `ALL` without grouping. Amounts are minor
 * units; [averagePaidMinor] is the exact average rounded half up.
 */
data class PayoutGroup(val group: String, val payouts: Long, val totalPaidMinor: Long, val averagePaidMinor: Long)

/** Result of counting claims. */
sealed interface CountResult {
    data class Counted(val count: Long) : CountResult
}

/** Result of aggregating payouts. */
sealed interface StatsResult {
    /** One group per value of [groupBy] that has a payout, ordered; a single `ALL` group without grouping. */
    data class Stats(val groupBy: StatsGrouping?, val groups: List<PayoutGroup>) : StatsResult
}

/** A filter value the service refuses; [message] names the value. Both analytics results can be this. */
data class InvalidFilter(val message: String) :
    CountResult,
    StatsResult
