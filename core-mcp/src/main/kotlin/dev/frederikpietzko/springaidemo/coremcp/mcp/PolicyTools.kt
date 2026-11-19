package dev.frederikpietzko.springaidemo.coremcp.mcp

import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyService
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** MCP tools about policies. */
@Component
class PolicyTools(private val policies: PolicyService, private val json: JsonMapper) {
    @McpTool(
        description = "Looks up an insurance policy by its number: holder (customer id and name), validity dates, " +
            "deductible in minor units and the covered damage categories (VEHICLE, BUILDING, CONTENTS, GARDEN).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
    )
    fun findPolicy(
        @McpToolParam(description = "Policy number, for example KP-1001") policyNumber: String,
    ): CallToolResult =
        when (val result = policies.find(policyNumber)) {
            is PolicyLookup.Found -> ToolResults.ok(json, result.policy)
            PolicyLookup.NotFound -> ToolResults.error("Policy $policyNumber not found")
        }
}
