package dev.frederikpietzko.springaidemo.coremcp.security

import org.springframework.ai.mcp.annotation.McpMeta
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/** The client id (token subject) of the claimant agent; every other caller is the adjuster agent. */
private const val CLAIMANT_AGENT = "claimant-agent"

/** The `_meta` key under which the backend sends the claimant's customer id with a claimant call. */
private const val CLAIMANT_ID = "claimantId"

/**
 * What the caller of a tool may do (ADR-0009). It reads the caller's access token from the security context, which
 * Spring AI sets on the request thread that runs a synchronous tool. Each check answers `null` when the call is
 * allowed, otherwise the sentence the tool returns as its error.
 */
@Component
class CallerAccess {
    /** Refuses a token without [scope], for example `payout:write`. */
    fun scopeDenial(scope: String): String? =
        if (caller().authorities.any { it.authority == "SCOPE_$scope" }) {
            null
        } else {
            "Access denied: this tool needs the scope $scope, which the caller's token does not have"
        }

    /** Refuses the claimant agent; [action] says what only the adjuster agent may do. */
    fun claimantDenial(action: String): String? =
        if (isClaimant()) "Access denied: only the adjuster agent may $action" else null

    /**
     * Refuses a claimant call for data of another customer than the one in [meta] (`claimantId`), or without one.
     * [customerId] owns the item, [item] names it in the answer (`claim 4712`), which carries none of its data.
     * The adjuster agent reaches every customer's data.
     */
    fun ownershipDenial(meta: McpMeta, customerId: Long, item: String): String? {
        if (!isClaimant()) return null
        val claimantId = meta.get(CLAIMANT_ID)?.toString()?.toLongOrNull()
        return when (claimantId) {
            null -> "Access denied: a claimant call needs the claimant's customer id as _meta $CLAIMANT_ID"
            customerId -> null
            else -> "Access denied: $item belongs to another customer"
        }
    }

    private fun isClaimant(): Boolean = caller().name == CLAIMANT_AGENT

    private fun caller(): Authentication =
        checkNotNull(SecurityContextHolder.getContext().authentication) { "a tool ran without an authenticated caller" }
}
