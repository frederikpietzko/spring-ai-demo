package dev.frederikpietzko.springaidemo.coremcp.mcp

import dev.frederikpietzko.springaidemo.coremcp.domain.AnalyticsService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimFilter
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.CountResult
import dev.frederikpietzko.springaidemo.coremcp.domain.District
import dev.frederikpietzko.springaidemo.coremcp.domain.InvalidFilter
import dev.frederikpietzko.springaidemo.coremcp.domain.StatsGrouping
import dev.frederikpietzko.springaidemo.coremcp.domain.StatsResult
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** The `{}` hint lives here, not in a system prompt: tool descriptions are not rendered as templates. */
private const val FILTER_DESCRIPTION =
    "Which claims to include. Every field is optional and all given fields must match; send {} for all claims."

/** The filter of the analytics tools as the model sends it. Every field is optional (`required = false`). */
data class ClaimFilterInput(
    @field:McpToolParam(
        description = "District: HARBOUR, OLD_TOWN, NEON_MARKET, RIVERSIDE or HILLTOP",
        required = false,
    )
    val district: District? = null,
    @field:McpToolParam(description = "Kaiju class from 1 (smallest) to 5 (strongest)", required = false)
    val kaijuClass: Int? = null,
    @field:McpToolParam(description = "Claim status", required = false)
    val status: ClaimStatus? = null,
    @field:McpToolParam(
        description = "First incident day, inclusive, as an ISO date in UTC, for example 2026-11-01",
        required = false,
    )
    val from: String? = null,
    @field:McpToolParam(
        description = "Last incident day, inclusive, as an ISO date in UTC, for example 2026-11-30",
        required = false,
    )
    val to: String? = null,
) {
    fun toFilter(): ClaimFilter = ClaimFilter(district, kaijuClass, status, from, to)
}

/** Read-only MCP tools that count and aggregate claims by a typed filter; the model never writes SQL. */
@Component
class AnalyticsTools(private val analytics: AnalyticsService, private val json: JsonMapper) {
    @McpTool(
        description = "Counts the claims that match a filter (district, kaiju class, status, incident period). " +
            "Use it for every question about how many claims there are; never estimate a count.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
    )
    fun countClaims(@McpToolParam(description = FILTER_DESCRIPTION) filter: ClaimFilterInput): CallToolResult =
        when (val result = analytics.countClaims(filter.toFilter())) {
            is CountResult.Counted -> ToolResults.ok(json, result)
            is InvalidFilter -> ToolResults.error("Invalid filter: ${result.message}")
        }

    @McpTool(
        description = "Payout statistics of the paid claims that match a filter: number of payouts, total and " +
            "average paid in minor units, per district or kaiju class, or in total without groupBy. Use it for every " +
            "question about payouts; never compute or estimate amounts yourself.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
    )
    fun payoutStats(
        @McpToolParam(description = FILTER_DESCRIPTION) filter: ClaimFilterInput,
        @McpToolParam(description = "Group by DISTRICT or KAIJU_CLASS; leave it out for one total", required = false)
        groupBy: StatsGrouping?,
    ): CallToolResult =
        when (val result = analytics.payoutStats(filter.toFilter(), groupBy)) {
            is StatsResult.Stats -> ToolResults.ok(json, result)
            is InvalidFilter -> ToolResults.error("Invalid filter: ${result.message}")
        }
}
