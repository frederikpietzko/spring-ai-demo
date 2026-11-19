package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.fileSandboxClaim
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `updateClaimStatus` over MCP, contract only (the transition table is tested on `ClaimService`). */
@CoreMcpIntegrationTest
class ClaimToolsStatusTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Autowired
    private lateinit var claims: ClaimService

    @Test
    fun `updateClaimStatus binds the status name and getClaim reports the new status`() {
        val claimNumber = claims.fileSandboxClaim(30000)

        val result = mcp.call("updateClaimStatus", mapOf("claimNumber" to claimNumber, "status" to "UNDER_REVIEW"))

        assertFalse(result.failed, result.text)
        assertEquals("UNDER_REVIEW", statusOf(claimNumber))
    }

    @Test
    fun `an illegal jump is a tool error naming both statuses and the status stays`() {
        val claimNumber = claims.fileSandboxClaim(30000)

        val result = mcp.call("updateClaimStatus", mapOf("claimNumber" to claimNumber, "status" to "APPROVED"))

        assertTrue(result.failed)
        assertTrue("FILED" in result.text && "APPROVED" in result.text, result.text)
        assertEquals("FILED", statusOf(claimNumber))
    }

    private fun statusOf(claimNumber: Long): String =
        json.readTree(mcp.call("getClaim", mapOf("claimNumber" to claimNumber)).text)["status"].stringValue()
}
