package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.formatMinor
import tools.jackson.databind.json.JsonMapper

/** The name of the core tool whose result becomes a table. */
const val PAYOUT_STATS = "payoutStats"

/** The core's `payoutStats` answer. */
private data class PayoutStats(val groupBy: String?, val groups: List<PayoutGroup>)

private data class PayoutGroup(
    val group: String,
    val payouts: Long,
    val totalPaidMinor: Long,
    val averagePaidMinor: Long,
)

/** Title and name of the first column per `groupBy` value; `null` is no grouping. */
private val HEADERS: Map<String?, Pair<String, String>> = mapOf(
    null to ("Payouts" to "Group"),
    "DISTRICT" to ("Payouts by district" to "District"),
    "KAIJU_CLASS" to ("Payouts by kaiju class" to "Kaiju class"),
)

/**
 * The table of a `payoutStats` result as the MCP client delivers it: `[{"text":"<the core's JSON>"}]`. Returns `null`
 * for any other shape.
 */
fun payoutStatsTable(json: JsonMapper, result: String): ChatEvent.AnalyticsTable? =
    runCatching {
        val coreJson = json.readTree(result).path(0).path("text").stringValue()
        json.readValue(coreJson, PayoutStats::class.java)
    }.getOrNull()?.toTable()

private fun PayoutStats.toTable(): ChatEvent.AnalyticsTable? {
    val [title, groupColumn] = HEADERS[groupBy] ?: return null
    return ChatEvent.AnalyticsTable(
        title = title,
        columns = [groupColumn, "Payouts", "Total paid", "Average paid"],
        rows = groups.map {
            [it.group, "${it.payouts}", formatMinor(it.totalPaidMinor), formatMinor(it.averagePaidMinor)]
        },
    )
}
