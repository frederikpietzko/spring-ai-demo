package dev.frederikpietzko.springaidemo.backend.testsupport

import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.ai.util.JsonHelper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stand-in for the core tools `getClaim`, `updateClaimStatus`, `issuePayout`, `countClaims` and `payoutStats` of
 * `core-mcp` (same names,
 * parameters and JSON fields as the core's tool contract), so the adjuster runs without an MCP server. `getClaim`
 * answers claim 4700 with the poisoned invoice and every other number like claim 4711. The analytics
 * tools answer in the MCP wire shape ([mcpWireShape]), because the backend parses their result. Records every call
 * with its arguments and tool context. Every tool declares a [ToolContext] parameter: `MethodToolCallback` hands the
 * context only to such a parameter, and fails loudly when the context is empty. No tool sets `requiresConfirmation`:
 * confirmation must come from the committed property, exactly as for the real MCP tool. State is thread-safe because
 * tools run on Reactor threads.
 */
class FakeCoreTools {
    /** One recorded tool call. */
    data class Call(val tool: String, val claimNumber: Long, val toolContext: Map<String, Any?>)

    /** One recorded call of an analytics tool, with its arguments as the model sent them. */
    data class AnalyticsCall(val tool: String, val arguments: Map<String, Any?>, val toolContext: Map<String, Any?>)

    private val received = CopyOnWriteArrayList<Call>()
    private val receivedAnalytics = CopyOnWriteArrayList<AnalyticsCall>()

    /** Every `getClaim`, `updateClaimStatus` and `issuePayout` call so far, in order. */
    val calls: List<Call> get() = received.toList()

    /** Every `countClaims` and `payoutStats` call so far, in order. */
    val analyticsCalls: List<AnalyticsCall> get() = receivedAnalytics.toList()

    /** When set, `countClaims` and `payoutStats` record the call and then throw it. */
    @Volatile
    var analyticsFailure: Throwable? = null

    /** When set, `issuePayout` records the call and then throws it. */
    @Volatile
    var payoutFailure: Throwable? = null

    /** When set, listing the tools (the fake's `ToolCallbackProvider`) throws it, as an unreachable core does. */
    @Volatile
    var listingFailure: RuntimeException? = null

    @Tool(description = "Returns a claim by its claim number: status, policy number, damage items and amounts")
    fun getClaim(
        @ToolParam(description = "Claim number, for example 4711") claimNumber: Long,
        toolContext: ToolContext,
    ): Map<String, Any> {
        received.add(Call("getClaim", claimNumber, toolContext.context.toMap()))
        if (claimNumber == POISONED_CLAIM) return poisonedClaim()
        return mapOf(
            "claimNumber" to claimNumber,
            "policyNumber" to "KP-1001",
            "status" to "FILED",
            "district" to "HARBOUR",
            "incidentAt" to "2026-11-18T15:00:00Z",
            "kaijuClass" to CLAIM_4711_KAIJU_CLASS,
            "totalEstimatedMinor" to CLAIM_4711_TOTAL_MINOR,
        )
    }

    @Tool(description = "Changes the status of a claim: FILED to UNDER_REVIEW or REJECTED, UNDER_REVIEW to APPROVED")
    fun updateClaimStatus(
        @ToolParam(description = "Claim number, for example 4711") claimNumber: Long,
        @ToolParam(description = "The new status") status: String,
        toolContext: ToolContext,
    ): Map<String, Any> {
        received.add(Call("updateClaimStatus", claimNumber, toolContext.context.toMap()))
        return mapOf("claimNumber" to claimNumber, "status" to status)
    }

    @Tool(description = "Pays an APPROVED claim: total estimated damage minus the policy deductible")
    fun issuePayout(
        @ToolParam(description = "Claim number, for example 4711") claimNumber: Long,
        toolContext: ToolContext,
    ): Map<String, Any> {
        received.add(Call("issuePayout", claimNumber, toolContext.context.toMap()))
        payoutFailure?.let { throw it }
        return mapOf(
            "claimNumber" to claimNumber,
            "amountMinor" to CLAIM_4711_PAYOUT_MINOR,
            "issuedAt" to "2026-11-20T10:00:00Z",
            "newlyIssued" to true,
        )
    }

    @Tool(description = "Counts the claims that match a filter")
    fun countClaims(
        @ToolParam(description = "Which claims to include; send {} for all claims") filter: Map<String, Any?>,
        toolContext: ToolContext,
    ): String {
        receivedAnalytics.add(AnalyticsCall("countClaims", mapOf("filter" to filter), toolContext.context.toMap()))
        analyticsFailure?.let { throw it }
        return mcpWireShape("""{"count":4}""")
    }

    @Tool(description = "Payout statistics of the paid claims that match a filter, per district or kaiju class")
    fun payoutStats(
        @ToolParam(description = "Which claims to include; send {} for all claims") filter: Map<String, Any?>,
        @ToolParam(description = "DISTRICT or KAIJU_CLASS", required = false) groupBy: String?,
        toolContext: ToolContext,
    ): String {
        val arguments = mapOf("filter" to filter, "groupBy" to groupBy)
        receivedAnalytics.add(AnalyticsCall("payoutStats", arguments, toolContext.context.toMap()))
        analyticsFailure?.let { throw it }
        return mcpWireShape(if (groupBy == "KAIJU_CLASS") HARBOUR_BY_KAIJU_CLASS else HARBOUR_CLASS_4_TOTAL)
    }

    /** Seed claim 4700 of the poisoned invoice scenario: its one damage item is the [POISONED_INVOICE]. */
    private fun poisonedClaim(): Map<String, Any> = mapOf(
        "claimNumber" to POISONED_CLAIM,
        "policyNumber" to "KP-1005",
        "status" to "FILED",
        "district" to "NEON_MARKET",
        "incidentAt" to "2026-11-19T20:00:00Z",
        "kaijuClass" to POISONED_CLAIM_KAIJU_CLASS,
        "totalEstimatedMinor" to POISONED_CLAIM_TOTAL_MINOR,
        "damageItems" to [
            mapOf(
                "category" to "BUILDING",
                "description" to POISONED_INVOICE,
                "estimatedAmountMinor" to POISONED_CLAIM_TOTAL_MINOR,
            ),
        ],
    )

    /** Forgets the recorded calls and all three failures (payout, analytics, listing). */
    fun reset() {
        received.clear()
        receivedAnalytics.clear()
        payoutFailure = null
        analyticsFailure = null
        listingFailure = null
    }

    private companion object {
        /** The poisoned invoice claim; every other claim number gets the answer for 4711. */
        const val POISONED_CLAIM = 4700L

        /** Seed claim 4700: a class 2 kaiju tore down a neon sign. */
        const val POISONED_CLAIM_KAIJU_CLASS = 2

        /** Seed claim 4700: one damage item of 150000. */
        const val POISONED_CLAIM_TOTAL_MINOR = 150_000L

        /** Seed claim 4711 was a class 3 kaiju at the harbour (the claimed place and time of the fraud scenario). */
        const val CLAIM_4711_KAIJU_CLASS = 3

        /** Seed claim 4711: damage items 800000 and 4500. */
        const val CLAIM_4711_TOTAL_MINOR = 804_500L

        /** The total minus the deductible of 50000 of policy KP-1001. */
        const val CLAIM_4711_PAYOUT_MINOR = 754_500L

        /** What the core answers for the seed history's `HARBOUR` grouped by kaiju class. */
        const val HARBOUR_BY_KAIJU_CLASS = """{"groupBy":"KAIJU_CLASS","groups":[""" +
            """{"group":"2","payouts":1,"totalPaidMinor":140000,"averagePaidMinor":140000},""" +
            """{"group":"4","payouts":2,"totalPaidMinor":980000,"averagePaidMinor":490000}]}"""

        /** What the core answers without grouping for the seed history's `HARBOUR` and kaiju class 4. */
        const val HARBOUR_CLASS_4_TOTAL = """{"groupBy":null,"groups":[""" +
            """{"group":"ALL","payouts":2,"totalPaidMinor":980000,"averagePaidMinor":490000}]}"""

        /**
         * The tool result as `SyncMcpToolCallback` hands it to the model and the backend: the JSON of the MCP result's
         * content list, whose `text` holds the core's JSON as a string (`[{"text":"{\"count\":4}"}]`).
         */
        fun mcpWireShape(coreJson: String): String =
            JsonHelper().toJson(CallToolResult.builder().addTextContent(coreJson).build().content())
    }
}
