package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.applySeed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.fileSandboxClaim
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The demo seed set `desk-queue`, applied through a JDBC script runner and checked through the MCP client. Every test
 * ends with `reset`, so the shared database is at the migration state for the other tests.
 */
@CoreMcpIntegrationTest
class DemoSeedDeskQueueTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Autowired
    private lateinit var claims: ClaimService

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @AfterEach
    fun resetDatabase() = dataSource.applySeed("reset")

    @Test
    fun `the queue holds a filed, an under-review and an approved claim on three new policies`() {
        dataSource.applySeed("desk-queue")

        assertEquals(
            ["FILED", "UNDER_REVIEW", "APPROVED"],
            QUEUE.map { json.readTree(getClaim(it).text)["status"].stringValue() },
        )
        assertEquals([false, false, false], POLICIES.map { findPolicy(it).failed })
    }

    @Test
    fun `the queue's claims are in the districts NEON_MARKET and RIVERSIDE and each has damage items`() {
        dataSource.applySeed("desk-queue")

        val queue = QUEUE.map { json.readTree(getClaim(it).text) }

        assertTrue(queue.all { it["district"].stringValue() in ["NEON_MARKET", "RIVERSIDE"] }, queue.toString())
        assertTrue(queue.all { it["damageItems"].size() > 0 }, queue.toString())
    }

    @Test
    fun `the approved claim is paid its damage total minus the policy deductible`() {
        dataSource.applySeed("desk-queue")
        val claim = json.readTree(getClaim(APPROVED).text)
        val total = claim["damageItems"].values().sumOf { it["estimatedAmountMinor"].longValue() }
        val policy = json.readTree(findPolicy(claim["policyNumber"].stringValue()).text)
        val deductible = policy["deductibleMinor"].longValue()

        val payout = mcp.call("issuePayout", mapOf("claimNumber" to APPROVED))

        assertFalse(payout.failed, payout.text)
        assertEquals(total - deductible, json.readTree(payout.text)["amountMinor"].longValue())
        assertEquals("PAID", json.readTree(getClaim(APPROVED).text)["status"].stringValue())
    }

    @Test
    fun `applying desk-queue twice adds each claim, customer and policy once`() {
        dataSource.applySeed("desk-queue")

        dataSource.applySeed("desk-queue")

        val claimsPerCustomer = CUSTOMERS.map { customer ->
            json.readTree(mcp.call("listClaimsForCustomer", mapOf("customerId" to customer)).text)
                .values().map { it["claimNumber"].longValue() }
        }
        val onceEach: List<List<Long>> = [[4801], [4802], [APPROVED]]
        assertEquals(onceEach, claimsPerCustomer)
        assertEquals([false, false, false], POLICIES.map { findPolicy(it).failed })
    }

    @Test
    fun `a claim filed after desk-queue gets a number above 4803`() {
        dataSource.applySeed("desk-queue")

        val claimNumber = claims.fileSandboxClaim(30000)

        assertTrue(claimNumber > APPROVED, "claim number $claimNumber")
    }

    @Test
    fun `reset removes the queue's claims and policies`() {
        dataSource.applySeed("desk-queue")

        dataSource.applySeed("reset")

        assertEquals([true, true, true], QUEUE.map { getClaim(it).failed })
        assertEquals([true, true, true], POLICIES.map { findPolicy(it).failed })
    }

    @Test
    fun `the analytics districts keep their counts and payout statistics`() {
        val before = analytics()

        dataSource.applySeed("desk-queue")

        assertEquals(before, analytics())
    }

    @Test
    fun `the seed sets are not migrations and their rows are not in the migrated database`() {
        val scripts = jdbc.queryForList("SELECT script FROM flyway_schema_history", String::class.java).filterNotNull()

        assertTrue(scripts.none { "seed" in it && ("reset" in it || "desk" in it) }, scripts.toString())
        assertTrue(POLICIES.all { findPolicy(it).failed })
        assertTrue(QUEUE.all { getClaim(it).failed })
    }

    @Test
    fun `both seed sets run through a JDBC script runner without an error`() {
        dataSource.applySeed("reset")
        dataSource.applySeed("desk-queue")

        assertFalse(getClaim(APPROVED).failed)
    }

    private fun getClaim(claimNumber: Long) = mcp.call("getClaim", mapOf("claimNumber" to claimNumber))

    private fun findPolicy(policyNumber: String) = mcp.call("findPolicy", mapOf("policyNumber" to policyNumber))

    /** Claim counts and payout statistics of each analytics district, as the tools answer them. */
    private fun analytics(): List<String> = ANALYTICS_DISTRICTS.flatMap { district ->
        val filter = mapOf("filter" to mapOf("district" to district))
        [mcp.call("countClaims", filter).text, mcp.call("payoutStats", filter).text]
    }

    private companion object {
        const val APPROVED = 4803L
        val QUEUE: List<Long> = [4801, 4802, APPROVED]
        val CUSTOMERS: List<Long> = [201, 202, 203]
        val POLICIES: List<String> = ["KP-3001", "KP-3002", "KP-3003"]
        val ANALYTICS_DISTRICTS: List<String> = ["HARBOUR", "OLD_TOWN", "HILLTOP"]
    }
}
