package dev.frederikpietzko.springaidemo.coremcp.mcp

import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyService
import dev.frederikpietzko.springaidemo.coremcp.security.CallerAccess
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.ai.mcp.annotation.McpMeta
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** MCP tools about policies. A claimant sees only their own policies. */
@Component
class PolicyTools(
    private val policies: PolicyService,
    private val json: JsonMapper,
    private val access: CallerAccess,
) {
    @McpTool(
        description = "Looks up an insurance policy by its number: holder (customer id and name), validity dates, " +
            "deductible in minor units and the covered damage categories (VEHICLE, BUILDING, CONTENTS, GARDEN).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
    )
    fun findPolicy(
        @McpToolParam(description = "Policy number, for example KP-1001") policyNumber: String,
        meta: McpMeta,
    ): CallToolResult = access.withScope("policies:read") {
        when (val result = policies.find(policyNumber)) {
            is PolicyLookup.Found ->
                access.ownershipDenial(meta, result.policy.customerId, "policy $policyNumber")?.let(::toolError)
                    ?: json.ok(result.policy)
            PolicyLookup.NotFound -> toolError("Policy $policyNumber not found")
        }
    }
}
