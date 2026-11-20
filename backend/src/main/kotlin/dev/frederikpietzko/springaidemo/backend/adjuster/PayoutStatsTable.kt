package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.formatMinor
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The name of the core tool whose result becomes a table: renaming the tool means changing this constant. */
const val PAYOUT_STATS = "payoutStats"

/**
 * The table of a `payoutStats` result as the MCP client delivers it: `[{"text":"<the core's JSON>"}]`, so the core's
 * `{"groupBy":...,"groups":[...]}` is read from the `text` of the first content and parsed again. Every cell is a
 * string. Returns `null` for any other shape (an empty list, no `text`, text that is not JSON, no `groups`).
 */
fun payoutStatsTable(json: JsonMapper, result: String): ChatEvent.AnalyticsTable? {
    val stats = coreJson(json, result) ?: return null
    val groups = stats.path("groups")
    val header = HEADERS[stats.path("groupBy").asString(null)]
    return if (groups.isArray && header != null) table(header, groups) else null
}

/** Title and name of the first column per `groupBy` value; `null` is no grouping. */
private val HEADERS: Map<String?, Pair<String, String>> = mapOf(
    null to ("Payouts" to "Group"),
    "DISTRICT" to ("Payouts by district" to "District"),
    "KAIJU_CLASS" to ("Payouts by kaiju class" to "Kaiju class"),
)

private fun table(header: Pair<String, String>, groups: JsonNode): ChatEvent.AnalyticsTable {
    val [title, groupColumn] = header
    return ChatEvent.AnalyticsTable(
        title = title,
        columns = [groupColumn, "Payouts", "Total paid", "Average paid"],
        rows = groups.values().map { group ->
            [
                group.path("group").asString(),
                group.path("payouts").asString(),
                formatMinor(group.path("totalPaidMinor").asLong()),
                formatMinor(group.path("averagePaidMinor").asLong()),
            ]
        },
    )
}

/** The core's JSON inside the `text` of the first content, or `null`. */
private fun coreJson(json: JsonMapper, result: String): JsonNode? {
    val text = readTree(json, result)?.path(0)?.path("text")
    return if (text != null && text.isString) readTree(json, text.stringValue()) else null
}

private fun readTree(json: JsonMapper, text: String): JsonNode? =
    try {
        json.readTree(text)
    } catch (_: JacksonException) {
        null
    }
