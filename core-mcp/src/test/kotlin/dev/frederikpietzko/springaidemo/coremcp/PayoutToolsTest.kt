package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.REJECTED
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.fileSandboxClaim
import dev.frederikpietzko.springaidemo.coremcp.testsupport.sandboxClaimIn
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `issuePayout` over MCP, contract only (the payout rules are tested on `PayoutService`). */
@CoreMcpIntegrationTest
class PayoutToolsTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Autowired
    private lateinit var claims: ClaimService

    @Test
    fun `an approved claim is paid once and a second call returns the same payout`() {
        val claimNumber = claims.fileSandboxClaim(30000, 5000)
        approveOverMcp(claimNumber)

        val first = mcp.call("issuePayout", mapOf("claimNumber" to claimNumber))
        val second = mcp.call("issuePayout", mapOf("claimNumber" to claimNumber))

        assertFalse(first.failed, first.text)
        assertFalse(second.failed, second.text)
        val firstPayout = json.readTree(first.text)
        val secondPayout = json.readTree(second.text)
        assertEquals(25000, firstPayout["amountMinor"].longValue())
        assertTrue(firstPayout["newlyIssued"].booleanValue())
        assertEquals(25000, secondPayout["amountMinor"].longValue())
        assertFalse(secondPayout["newlyIssued"].booleanValue())
        val claim = json.readTree(mcp.call("getClaim", mapOf("claimNumber" to claimNumber)).text)
        assertEquals("PAID", claim["status"].stringValue())
        assertEquals(25000, claim["payout"]["amountMinor"].longValue())
    }

    @Test
    fun `a rejected claim is a tool error naming its status`() {
        val claimNumber = claims.sandboxClaimIn(REJECTED)

        val result = mcp.call("issuePayout", mapOf("claimNumber" to claimNumber))

        assertTrue(result.failed)
        assertTrue("REJECTED" in result.text, result.text)
    }

    private fun approveOverMcp(claimNumber: Long) {
        val steps = ["UNDER_REVIEW", "APPROVED"].map { status ->
            mcp.call("updateClaimStatus", mapOf("claimNumber" to claimNumber, "status" to status))
        }
        check(steps.none { it.failed }) { "approval failed: ${steps.map { it.text }}" }
    }
}
