package dev.frederikpietzko.springaidemo.backend.testsupport

import dev.frederikpietzko.springaidemo.backend.intake.ClaimantCore
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Stand-in for the claimant agent's connection to `core-mcp`, so claims are filed without an MCP server. It answers
 * the core's `fileClaim` tool like the core does: the new claim as JSON with a claim number from the core's sequence
 * (4714 upwards, after the seed claims), or a result marked as an error for a policy number ending in `-0000`, which
 * the core does not know ("Policy KP-0000 not found"). Records every filed claim with the request's arguments and
 * `_meta`. State is thread-safe; [reset] runs before each test ([FakeChatModelReset]).
 */
class FakeClaimantCore : ClaimantCore {
    /** A claim the fake filed: its number, the tool arguments and the MCP `_meta` of the request. */
    data class FiledClaim(val claimNumber: Long, val arguments: Map<String, Any?>, val meta: Map<String, Any?>)

    private val json = JsonMapper.shared()
    private val nextNumber = AtomicLong(FIRST_CLAIM_NUMBER)
    private val claims = CopyOnWriteArrayList<FiledClaim>()

    /** Every claim filed so far, in order. */
    val filed: List<FiledClaim> get() = claims.toList()

    /** When set, every call throws it, as an unreachable core does. */
    @Volatile
    var failure: RuntimeException? = null

    override fun callTool(request: CallToolRequest): CallToolResult {
        failure?.let { throw it }
        check(request.name() == "fileClaim") { "The fake core serves fileClaim only, not ${request.name()}" }
        val arguments = request.arguments().orEmpty()
        val policyNumber = arguments["policyNumber"].toString()
        if (policyNumber.endsWith("-0000")) {
            return CallToolResult.builder().isError(true).addTextContent("Policy $policyNumber not found").build()
        }
        val claim = FiledClaim(nextNumber.getAndIncrement(), arguments, request.meta().orEmpty())
        claims += claim
        val answer = mapOf("claimNumber" to claim.claimNumber, "status" to "FILED") + arguments
        return CallToolResult.builder().addTextContent(json.writeValueAsString(answer)).build()
    }

    /** Forgets the failure; the filed claims stay (like the core's), so tests assert on their own claims only. */
    fun reset() {
        failure = null
    }

    private companion object {
        /** The core's next claim number after the seed claims 4711 to 4713. */
        const val FIRST_CLAIM_NUMBER = 4714L
    }
}
