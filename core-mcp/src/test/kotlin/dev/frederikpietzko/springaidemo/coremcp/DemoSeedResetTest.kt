package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.FileResult
import dev.frederikpietzko.springaidemo.coremcp.domain.PayoutResult
import dev.frederikpietzko.springaidemo.coremcp.domain.PayoutService
import dev.frederikpietzko.springaidemo.coremcp.domain.StatusUpdate
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.aClaimRequest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.applySeed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.fileSandboxClaim
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The demo seed set `reset`, applied through a JDBC script runner and checked through the MCP client. Every test ends
 * with `reset`, so the shared database is at the migration state for the other tests.
 */
@CoreMcpIntegrationTest
class DemoSeedResetTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Autowired
    private lateinit var claims: ClaimService

    @Autowired
    private lateinit var payouts: PayoutService

    @Autowired
    private lateinit var dataSource: DataSource

    @AfterEach
    fun resetDatabase() = dataSource.applySeed("reset")

    @Test
    fun `a paid scenario claim is FILED again without a payout and can be processed again`() {
        approve(4711)
        assertIs<PayoutResult.Issued>(payouts.issue(4711))

        dataSource.applySeed("reset")

        val claim = json.readTree(getClaim(4711).text)
        assertEquals("FILED", claim["status"].stringValue())
        assertTrue(claim["payout"] == null || claim["payout"].isNull, claim.toString())
        approve(4711)
        assertIs<PayoutResult.Issued>(payouts.issue(4711))
    }

    @Test
    fun `a claim filed at runtime outside the sandbox is removed`() {
        val filed = assertIs<FileResult.Filed>(claims.file(aClaimRequest(policyNumber = "KP-1001")))

        dataSource.applySeed("reset")

        val result = getClaim(filed.claim.claimNumber)
        assertTrue(result.failed && "not found" in result.text, result.text)
    }

    @Test
    fun `the claims history and its payout statistics are untouched`() {
        val before = history()

        dataSource.applySeed("reset")

        assertEquals(before, history())
    }

    @Test
    fun `claims on the sandbox policy survive`() {
        val claimNumber = claims.fileSandboxClaim(30000)

        dataSource.applySeed("reset")

        assertFalse(getClaim(claimNumber).failed)
    }

    @Test
    fun `applying reset twice gives the same result as once`() {
        dataSource.applySeed("reset")
        val once = scenario()

        dataSource.applySeed("reset")

        assertEquals(once, scenario())
    }

    private fun approve(claimNumber: Long) {
        assertIs<StatusUpdate.Updated>(claims.updateStatus(claimNumber, ClaimStatus.UNDER_REVIEW))
        assertIs<StatusUpdate.Updated>(claims.updateStatus(claimNumber, ClaimStatus.APPROVED))
    }

    private fun getClaim(claimNumber: Long) = mcp.call("getClaim", mapOf("claimNumber" to claimNumber))

    /** The history claims 4601 to 4612 and the payout statistics per district, as the tools answer them. */
    private fun history(): List<String> =
        (4601L..4612L).map { getClaim(it).text } + payoutStatsByDistrict()

    /** The scenario claims and the payout statistics per district, as the tools answer them. */
    private fun scenario(): List<String> = SCENARIO_CLAIMS.map { getClaim(it).text } + payoutStatsByDistrict()

    private fun payoutStatsByDistrict(): String =
        mcp.call("payoutStats", mapOf("filter" to emptyMap<String, Any>(), "groupBy" to "DISTRICT")).text

    private companion object {
        val SCENARIO_CLAIMS: List<Long> = [4700, 4711, 4712, 4713]
    }
}
