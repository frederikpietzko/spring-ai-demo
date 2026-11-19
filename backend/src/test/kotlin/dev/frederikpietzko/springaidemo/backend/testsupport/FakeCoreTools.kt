package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stand-in for the core tools `getClaim` and `issuePayout` of `core-mcp` (same names, parameter and JSON fields as
 * the core's tool contract), so the adjuster runs without an MCP server. Records every call with its arguments and
 * tool context. Both tools declare a [ToolContext] parameter: `MethodToolCallback` hands the context only to such a
 * parameter, and fails loudly when the context is empty. Neither tool sets `requiresConfirmation`: confirmation must
 * come from the committed property, exactly as for the real MCP tool. State is thread-safe because tools run on
 * Reactor threads.
 */
class FakeCoreTools {
    /** One recorded tool call. */
    data class Call(val tool: String, val claimNumber: Long, val toolContext: Map<String, Any?>)

    private val received = CopyOnWriteArrayList<Call>()

    /** Every tool call so far, in order. */
    val calls: List<Call> get() = received.toList()

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

    /** Forgets the recorded calls and both failures. */
    fun reset() {
        received.clear()
        payoutFailure = null
        listingFailure = null
    }

    private companion object {
        /** Seed claim 4711 was a class 3 kaiju at the harbour (the claimed place and time of the fraud scenario). */
        const val CLAIM_4711_KAIJU_CLASS = 3

        /** Seed claim 4711: damage items 800000 and 4500. */
        const val CLAIM_4711_TOTAL_MINOR = 804_500L

        /** The total minus the deductible of 50000 of policy KP-1001. */
        const val CLAIM_4711_PAYOUT_MINOR = 754_500L
    }
}
