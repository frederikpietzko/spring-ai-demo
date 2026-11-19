package dev.frederikpietzko.springaidemo.coremcp.mcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.CustomerClaims
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageItem
import dev.frederikpietzko.springaidemo.coremcp.domain.District
import dev.frederikpietzko.springaidemo.coremcp.domain.FileClaimRequest
import dev.frederikpietzko.springaidemo.coremcp.domain.FileResult
import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyService
import dev.frederikpietzko.springaidemo.coremcp.domain.StatusUpdate
import dev.frederikpietzko.springaidemo.coremcp.security.CallerAccess
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.ai.mcp.annotation.McpMeta
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** One damage item as the model sends it to `fileClaim`. */
data class DamageItemInput(
    @field:McpToolParam(description = "Damage category: VEHICLE, BUILDING, CONTENTS or GARDEN")
    val category: DamageCategory,
    @field:McpToolParam(description = "What was damaged")
    val description: String,
    @field:McpToolParam(description = "Estimated cost in minor units (whole number, greater than zero)")
    val estimatedAmountMinor: Long,
)

/**
 * MCP tools about claims: read, file and move them along the status flow. Each checks the scope of its caller first;
 * for a claimant, the claim, customer or policy must also be the claimant's own (the `_meta` the backend sends,
 * [McpMeta], is no part of the tool schema).
 */
@Component
class ClaimTools(
    private val claims: ClaimService,
    private val policies: PolicyService,
    private val json: JsonMapper,
    private val access: CallerAccess,
) {
    @McpTool(
        description = "Returns a claim by its claim number: status, policy number, incident (district, time, kaiju " +
            "class), description, damage items, total estimated amount and payout if paid. Amounts in minor units.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
    )
    fun getClaim(
        @McpToolParam(description = "Claim number, for example 4711") claimNumber: Long,
        meta: McpMeta,
    ): CallToolResult {
        access.scopeDenial("claims:read")?.let { return ToolResults.error(it) }
        return when (val result = claims.find(claimNumber)) {
            is ClaimLookup.Found ->
                access.ownershipDenial(meta, result.claim.customerId, "claim $claimNumber")?.let(ToolResults::error)
                    ?: ToolResults.ok(json, result.claim)
            ClaimLookup.NotFound -> ToolResults.error("Claim $claimNumber not found")
        }
    }

    @McpTool(
        description = "Lists the claims on all policies of a customer as summaries (claim number, policy number, " +
            "status, district, incident time, total estimated amount in minor units).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
    )
    fun listClaimsForCustomer(
        @McpToolParam(description = "Customer id, for example 1") customerId: Long,
        meta: McpMeta,
    ): CallToolResult {
        access.scopeDenial("claims:read")?.let { return ToolResults.error(it) }
        return when (val result = claims.listForCustomer(customerId)) {
            is CustomerClaims.Found ->
                access.ownershipDenial(meta, customerId, "customer $customerId")?.let(ToolResults::error)
                    ?: ToolResults.ok(json, result.summaries)
            CustomerClaims.UnknownCustomer -> ToolResults.error("Customer $customerId not found")
        }
    }

    @McpTool(
        description = "Files a new claim with status FILED on an existing policy and returns it with its new claim " +
            "number. The incident must fall within the policy validity; amounts are whole minor units.",
    )
    @Suppress("LongParameterList") // the flat parameters are the MCP tool schema the model sees
    fun fileClaim(
        @McpToolParam(description = "Policy number, for example KP-1001") policyNumber: String,
        @McpToolParam(description = "District of the incident: HARBOUR, OLD_TOWN, NEON_MARKET, RIVERSIDE or HILLTOP")
        district: District,
        @McpToolParam(description = "Incident time, ISO-8601 with offset, for example 2026-11-18T15:00:00Z")
        incidentAt: String,
        @McpToolParam(description = "Kaiju class from 1 (smallest) to 5 (strongest)") kaijuClass: Int,
        @McpToolParam(description = "What happened, in the claimant's words") description: String,
        @McpToolParam(description = "At least one damaged item with its estimated cost")
        damageItems: List<DamageItemInput>,
        meta: McpMeta,
    ): CallToolResult {
        (access.scopeDenial("claims:write") ?: policyOwnershipDenial(meta, policyNumber))
            ?.let { return ToolResults.error(it) }
        val request = FileClaimRequest(
            policyNumber = policyNumber,
            district = district,
            incidentAt = incidentAt,
            kaijuClass = kaijuClass,
            description = description,
            damageItems = damageItems.map { DamageItem(it.category, it.description, it.estimatedAmountMinor) },
        )
        return when (val result = claims.file(request)) {
            is FileResult.Filed -> ToolResults.ok(json, result.claim)
            FileResult.PolicyNotFound -> ToolResults.error("Policy $policyNumber not found")
            is FileResult.IncidentOutsidePolicy -> ToolResults.error(
                "Incident $incidentAt is outside the validity of policy $policyNumber " +
                    "(${result.validFrom} to ${result.validTo})",
            )
            is FileResult.Invalid -> ToolResults.error("Invalid ${result.field}: ${result.reason}")
        }
    }

    @McpTool(
        description = "Changes the status of a claim. Allowed: FILED to UNDER_REVIEW or REJECTED, UNDER_REVIEW to " +
            "APPROVED or REJECTED. PAID is reached only through issuePayout.",
    )
    fun updateClaimStatus(
        @McpToolParam(description = "Claim number, for example 4711") claimNumber: Long,
        @McpToolParam(description = "The new status") status: ClaimStatus,
    ): CallToolResult {
        // claims:write also lets the claimant file claims; reviewing them is the adjuster's job only.
        (access.scopeDenial("claims:write") ?: access.claimantDenial("change a claim status"))
            ?.let { return ToolResults.error(it) }
        return when (val result = claims.updateStatus(claimNumber, status)) {
            is StatusUpdate.Updated -> ToolResults.ok(json, result.claim)
            StatusUpdate.NotFound -> ToolResults.error("Claim $claimNumber not found")
            is StatusUpdate.IllegalTransition ->
                ToolResults.error("Cannot change claim $claimNumber from ${result.from} to ${result.to}")
        }
    }

    /** A claimant files only on their own policy; an unknown policy is left to the filing, which reports it. */
    private fun policyOwnershipDenial(meta: McpMeta, policyNumber: String): String? {
        val owner = (policies.find(policyNumber) as? PolicyLookup.Found)?.policy?.customerId ?: return null
        return access.ownershipDenial(meta, owner, "policy $policyNumber")
    }
}
