package dev.frederikpietzko.springaidemo.coremcp.mcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyService
import org.springframework.ai.mcp.annotation.McpResource
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * MCP resources: documents a client can read by URI. An unknown id throws; the annotation callback turns that into an
 * MCP error whose message ends with the exception's message (`... Cause: Claim 999999 not found`).
 */
@Component
class CoreResources(
    private val claims: ClaimService,
    private val policies: PolicyService,
    private val json: JsonMapper,
) {
    @McpResource(
        uri = "claim://{claimNumber}",
        name = "claim",
        description = "A claim as JSON, the same content as the getClaim tool",
        mimeType = "application/json",
    )
    fun claim(claimNumber: String): String {
        val lookup = claimNumber.toLongOrNull()?.let { claims.find(it) } ?: ClaimLookup.NotFound
        return when (lookup) {
            is ClaimLookup.Found -> json.writeValueAsString(lookup.claim)
            ClaimLookup.NotFound -> throw IllegalArgumentException("Claim $claimNumber not found")
        }
    }

    @McpResource(
        uri = "policy://{policyNumber}/summary",
        name = "policy-summary",
        description = "A short Markdown summary of a policy: holder, validity, deductible and covered categories",
        mimeType = "text/markdown",
    )
    fun policySummary(policyNumber: String): String =
        when (val lookup = policies.find(policyNumber)) {
            is PolicyLookup.Found -> with(lookup.policy) {
                """
                |# Policy $policyNumber
                |
                |- Holder: $holderName (customer $customerId)
                |- Valid: $validFrom to $validTo
                |- Deductible: $deductibleMinor minor units per claim
                |- Covers: ${categories.joinToString()}
                """.trimMargin()
            }
            PolicyLookup.NotFound -> throw IllegalArgumentException("Policy $policyNumber not found")
        }
}
