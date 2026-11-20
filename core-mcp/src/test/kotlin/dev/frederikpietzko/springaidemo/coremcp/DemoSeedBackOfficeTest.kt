package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.StatusUpdate
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.applySeed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.fileSandboxClaim
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The demo seed set `back-office` (the claim the adjuster presentation script reviews), applied through a JDBC script
 * runner and checked through the MCP client. Every test ends with `reset`, so the shared database is at the migration
 * state for the other tests.
 */
@CoreMcpIntegrationTest
class DemoSeedBackOfficeTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Autowired
    private lateinit var claims: ClaimService

    @Autowired
    private lateinit var dataSource: DataSource

    @AfterEach
    fun resetDatabase() = dataSource.applySeed("reset")

    @Test
    fun `the claim is FILED on KP-1001 in HILLTOP with damage items totalling 120000`() {
        dataSource.applySeed("back-office")

        val claim = claim()

        assertEquals("FILED", claim["status"].stringValue())
        assertEquals("KP-1001", claim["policyNumber"].stringValue())
        assertEquals("HILLTOP", claim["district"].stringValue())
        assertEquals(DAMAGE_TOTAL, damageTotal(claim))
    }

    @Test
    fun `the approved claim is paid its damage total minus the deductible`() {
        dataSource.applySeed("back-office")
        approve()

        val payout = mcp.call("issuePayout", mapOf("claimNumber" to CLAIM))

        assertFalse(payout.failed, payout.text)
        assertEquals(PAYOUT, json.readTree(payout.text)["amountMinor"].longValue())
        assertEquals("PAID", claim()["status"].stringValue())
    }

    @Test
    fun `applying back-office twice adds the claim and each damage item once`() {
        dataSource.applySeed("back-office")
        val once = getClaim().text

        dataSource.applySeed("back-office")

        val customerClaims = json.readTree(mcp.call("listClaimsForCustomer", mapOf("customerId" to 1)).text)
            .values().map { it["claimNumber"].longValue() }
        assertEquals(1, customerClaims.count { it == CLAIM }, customerClaims.toString())
        assertEquals(once, getClaim().text)
    }

    @Test
    fun `applying back-office again restores a paid claim to FILED with its items and no payout`() {
        dataSource.applySeed("back-office")
        val original = claim()
        approve()
        assertFalse(mcp.call("issuePayout", mapOf("claimNumber" to CLAIM)).failed)

        dataSource.applySeed("back-office")

        val restored = claim()
        assertEquals("FILED", restored["status"].stringValue())
        assertEquals(original["damageItems"], restored["damageItems"])
        assertTrue(restored["payout"] == null || restored["payout"].isNull, restored.toString())
    }

    @Test
    fun `a claim filed after back-office gets a number above 4810`() {
        dataSource.applySeed("back-office")

        val claimNumber = claims.fileSandboxClaim(30000)

        assertTrue(claimNumber > CLAIM, "claim number $claimNumber")
    }

    @Test
    fun `reset removes the claim and keeps the policy KP-1001`() {
        dataSource.applySeed("back-office")

        dataSource.applySeed("reset")

        val result = getClaim()
        assertTrue(result.failed && "not found" in result.text, result.text)
        assertFalse(mcp.call("findPolicy", mapOf("policyNumber" to "KP-1001")).failed)
    }

    @Test
    fun `the migrated database does not hold the claim`() {
        assertTrue(getClaim().failed)
    }

    private fun approve() {
        assertIs<StatusUpdate.Updated>(claims.updateStatus(CLAIM, ClaimStatus.UNDER_REVIEW))
        assertIs<StatusUpdate.Updated>(claims.updateStatus(CLAIM, ClaimStatus.APPROVED))
    }

    private fun getClaim() = mcp.call("getClaim", mapOf("claimNumber" to CLAIM))

    private fun claim(): JsonNode = json.readTree(getClaim().text)

    private fun damageTotal(claim: JsonNode): Long =
        claim["damageItems"].values().sumOf { it["estimatedAmountMinor"].longValue() }

    private companion object {
        const val CLAIM = 4810L
        const val DAMAGE_TOTAL = 120000L
        const val PAYOUT = 70000L
    }
}
