package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.APPROVED
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.FILED
import dev.frederikpietzko.springaidemo.coremcp.domain.CustomerClaims
import dev.frederikpietzko.springaidemo.coremcp.testsupport.AuthServerProcess
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.SANDBOX_POLICY
import dev.frederikpietzko.springaidemo.coremcp.testsupport.claimantClientWithScopes
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.sandboxClaimIn
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The owner of the sandbox policy `KP-9000`, so the claimant calls here are only about scopes. */
private val SANDBOX_CLAIMANT: Map<String, Any> = mapOf("claimantId" to 4)

/** Every tool checks the scope of its caller's token first; a missing scope is a tool error naming it. */
@CoreMcpIntegrationTest
class ToolScopesTest {
    @Autowired
    private lateinit var adjuster: McpTestClient

    @Autowired
    @Qualifier("claimantMcpClient")
    private lateinit var claimant: McpTestClient

    @Autowired
    private lateinit var claims: ClaimService

    @Autowired
    private lateinit var authServer: AuthServerProcess

    @Autowired
    private lateinit var environment: Environment

    @Test
    fun `a claimant token cannot pay out`() {
        val claimNumber = claims.sandboxClaimIn(APPROVED)

        val result = claimant.call("issuePayout", mapOf("claimNumber" to claimNumber), SANDBOX_CLAIMANT)

        assertTrue(result.failed)
        assertTrue("payout:write" in result.text, result.text)
        val claim = assertNotNull((claims.find(claimNumber) as? ClaimLookup.Found)?.claim)
        assertEquals(APPROVED, claim.status)
        assertNull(claim.payout)
    }

    @Test
    fun `a token without the scope of the tool files no claim`() {
        val before = sandboxClaimCount()

        val result = claimantClientWithScopes(environment, authServer, "claims:read").use {
            it.call("fileClaim", aFileClaimArguments(), SANDBOX_CLAIMANT)
        }

        assertTrue(result.failed)
        assertTrue("claims:write" in result.text, result.text)
        assertEquals(before, sandboxClaimCount())
    }

    @Test
    fun `an adjuster token pays out`() {
        val claimNumber = claims.sandboxClaimIn(APPROVED)

        val result = adjuster.call("issuePayout", mapOf("claimNumber" to claimNumber))

        assertFalse(result.failed, result.text)
        assertEquals(ClaimStatus.PAID, (claims.find(claimNumber) as? ClaimLookup.Found)?.claim?.status)
    }

    @Test
    fun `a claimant token cannot read analytics`() {
        val count = claimant.call("countClaims", mapOf("filter" to mapOf<String, Any>()), SANDBOX_CLAIMANT)
        val stats = claimant.call("payoutStats", mapOf("filter" to mapOf<String, Any>()), SANDBOX_CLAIMANT)

        assertTrue(count.failed)
        assertTrue("analytics:read" in count.text, count.text)
        assertTrue(stats.failed)
        assertTrue("analytics:read" in stats.text, stats.text)
    }

    @Test
    fun `an adjuster token reads analytics`() {
        val result = adjuster.call("countClaims", mapOf("filter" to mapOf("district" to "HARBOUR")))

        assertFalse(result.failed, result.text)
        assertTrue("count" in result.text, result.text)
    }

    @Test
    fun `a claimant token cannot approve a claim`() {
        val claimNumber = claims.sandboxClaimIn(FILED)

        val result = claimant.call(
            "updateClaimStatus",
            mapOf("claimNumber" to claimNumber, "status" to "UNDER_REVIEW"),
            SANDBOX_CLAIMANT,
        )

        assertTrue(result.failed)
        assertEquals(FILED, (claims.find(claimNumber) as? ClaimLookup.Found)?.claim?.status)
    }

    private fun sandboxClaimCount(): Int =
        (claims.listForCustomer(4) as CustomerClaims.Found).summaries.count { it.policyNumber == SANDBOX_POLICY }

    private fun aFileClaimArguments(): Map<String, Any> = mapOf(
        "policyNumber" to SANDBOX_POLICY,
        "district" to "HARBOUR",
        "incidentAt" to "2026-11-19T10:00:00Z",
        "kaijuClass" to 2,
        "description" to "A kaiju leaned on the garage.",
        "damageItems" to [
            mapOf("category" to "BUILDING", "description" to "Garage wall", "estimatedAmountMinor" to 20000),
        ],
    )
}
