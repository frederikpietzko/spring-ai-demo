package dev.frederikpietzko.springaidemo.coremcp.mcp

import dev.frederikpietzko.springaidemo.coremcp.domain.Payout
import dev.frederikpietzko.springaidemo.coremcp.domain.PayoutResult
import dev.frederikpietzko.springaidemo.coremcp.domain.PayoutService
import dev.frederikpietzko.springaidemo.coremcp.security.CallerAccess
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** What `issuePayout` answers: the payout and whether this call created it. */
data class PayoutReceipt(val claimNumber: Long, val amountMinor: Long, val issuedAt: Instant, val newlyIssued: Boolean)

/** The MCP tool that pays claims; needs the scope `payout:write` (the adjuster agent). */
@Component
class PayoutTools(private val payouts: PayoutService, private val json: JsonMapper, private val access: CallerAccess) {
    @McpTool(
        description = "Pays an APPROVED claim: total estimated damage minus the policy deductible, in minor units; " +
            "the claim becomes PAID. Calling it again for a paid claim returns the existing payout.",
        annotations = McpTool.McpAnnotations(idempotentHint = true),
    )
    fun issuePayout(
        @McpToolParam(description = "Claim number, for example 4711") claimNumber: Long,
    ): CallToolResult = access.withScope("payout:write") {
        when (val result = payouts.issue(claimNumber)) {
            is PayoutResult.Issued -> receipt(claimNumber, result.payout, newlyIssued = true)
            is PayoutResult.AlreadyIssued -> receipt(claimNumber, result.payout, newlyIssued = false)
            PayoutResult.NotFound -> toolError("Claim $claimNumber not found")
            is PayoutResult.NotApproved ->
                toolError("Claim $claimNumber is ${result.status}; only APPROVED claims can be paid")
            is PayoutResult.NothingPayable -> toolError(
                "Claim $claimNumber: the damage of ${result.totalEstimatedMinor} does not exceed " +
                    "the deductible of ${result.deductibleMinor}; nothing to pay",
            )
        }
    }

    private fun receipt(claimNumber: Long, payout: Payout, newlyIssued: Boolean): CallToolResult =
        json.ok(PayoutReceipt(claimNumber, payout.amountMinor, payout.issuedAt, newlyIssued))
}
