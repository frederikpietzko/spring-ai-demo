package dev.frederikpietzko.springaidemo.backend.intake

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.format.DateTimeFormatter

/** The core tool that files a claim. */
private const val FILE_CLAIM = "fileClaim"

/**
 * The customer whose claims the claimant chat files: a fixed demo claimant (customer 1 of the core's seed, owner of
 * policy `KP-1001`) until end-user login exists (ADR-0009).
 */
const val DEMO_CLAIMANT_ID = 1L

/**
 * Who files: the identity keys the core reads from the MCP `_meta` of a claimant call (ADR-0009). They travel in the
 * request's `_meta` only, never in a message.
 */
private val CLAIMANT_IDENTITY: Map<String, Any> = mapOf("actor" to "claimant-agent", "claimantId" to DEMO_CLAIMANT_ID)

/** Files a validated claim report as a claim. */
fun interface ClaimFiling {
    /** Files [claim]: its new claim number, or the reason it was refused. */
    fun file(claim: ValidClaim): FilingResult
}

/** The outcome of filing a claim. */
sealed interface FilingResult {
    /** The claim was filed under [claimNumber]. */
    data class Filed(val claimNumber: Long) : FilingResult

    /** The claim was not filed; [reason] is meant for the claimant. */
    data class Rejected(val reason: String) : FilingResult
}

/**
 * The claimant agent's connection to the core system (`core-mcp`, MCP connection `core-claimant`, ADR-0009): one MCP
 * tool call. A refusal of the core is a result marked as an error; an unreachable core throws.
 */
fun interface ClaimantCore {
    /** Calls the tool [request] names and returns its result. */
    fun callTool(request: CallToolRequest): CallToolResult
}

/**
 * Files claims in the core system with its `fileClaim` MCP tool, as the claimant agent for the [DEMO_CLAIMANT_ID]
 * (ADR-0003: the core owns claims and their numbers). The claim number is the one the core assigns; a claim the core
 * refuses (an unknown policy, an incident outside the policy's validity, another customer's policy) is rejected with
 * the core's own words. An unreachable core, or an answer without a claim number, throws.
 */
@Component
class CoreClaimFiling(private val core: ClaimantCore, private val json: JsonMapper) : ClaimFiling {
    override fun file(claim: ValidClaim): FilingResult {
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
