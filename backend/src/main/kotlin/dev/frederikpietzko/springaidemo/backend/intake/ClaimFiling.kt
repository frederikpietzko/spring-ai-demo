package dev.frederikpietzko.springaidemo.backend.intake

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.format.DateTimeFormatter

/** The core tool that files a claim. */
private const val FILE_CLAIM = "fileClaim"

/** A fixed demo claimant (customer 1 of the core's seed, owner of `KP-1001`) until end-user login exists (ADR-0009). */
const val DEMO_CLAIMANT_ID = 1L

/** Who files: sent as MCP `_meta` only, never in a message (ADR-0009). */
private val CLAIMANT_IDENTITY: Map<String, Any> = mapOf("actor" to "claimant-agent", "claimantId" to DEMO_CLAIMANT_ID)

/** The outcome of filing a claim. */
sealed interface FilingResult {
    data class Filed(val claimNumber: Long) : FilingResult

    /** Not filed; [reason] is meant for the claimant. */
    data class Rejected(val reason: String) : FilingResult
}

/** One MCP tool call on the claimant's connection to the core (`core-claimant`); the seam the tests fake. */
fun interface ClaimantCore {
    fun callTool(request: CallToolRequest): CallToolResult
}

/**
 * Files a validated claim in the core system with its `fileClaim` MCP tool (ADR-0003: the core owns claims and their
 * numbers). A claim the core refuses is rejected with the core's own words; an unreachable core throws.
 */
@Component
class ClaimFiling(private val core: ClaimantCore, private val json: JsonMapper) {
    fun file(claim: ValidClaim): FilingResult {
        val request = CallToolRequest.builder(FILE_CLAIM)
            .arguments(claim.toArguments())
            .meta(CLAIMANT_IDENTITY)
            .build()
        val result = core.callTool(request)
        val text = result.content().filterIsInstance<TextContent>().joinToString("\n") { it.text() }
        if (result.isError() == true) return FilingResult.Rejected(text)
        val claimNumber = json.readTree(text).path("claimNumber")
        check(claimNumber.isIntegralNumber) { "$FILE_CLAIM answered without a claim number" }
        return FilingResult.Filed(claimNumber.asLong())
    }
}

/** The arguments of `fileClaim`, named and shaped as the core's tool schema expects them. */
private fun ValidClaim.toArguments(): Map<String, Any> = mapOf(
    "policyNumber" to policyNumber,
    "district" to district.name,
    "incidentAt" to DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(incidentAt),
    "kaijuClass" to kaijuClass,
    "description" to description,
    "damageItems" to damageItems.map {
        mapOf(
            "category" to it.category.name,
            "description" to it.description,
            "estimatedAmountMinor" to it.estimatedAmountMinor,
        )
    },
)
