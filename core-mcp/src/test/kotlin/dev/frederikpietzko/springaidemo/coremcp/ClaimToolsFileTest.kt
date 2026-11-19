package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.SANDBOX_POLICY
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `fileClaim` over MCP, contract only (the rule matrix is tested on `ClaimService`). */
@CoreMcpIntegrationTest
class ClaimToolsFileTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `fileClaim binds enums, the ISO time and nested damage items and the claim can be read back`() {
        val result = mcp.call("fileClaim", fileClaimArguments(kaijuClass = 2))

        assertFalse(result.failed, result.text)
        val filed = json.readTree(result.text)
        val claimNumber = filed["claimNumber"].longValue()
        assertTrue(claimNumber > 4713, "claim number $claimNumber")
        assertEquals("FILED", filed["status"].stringValue())
        val readBack = json.readTree(mcp.call("getClaim", mapOf("claimNumber" to claimNumber)).text)
        assertEquals(filed, readBack)
        assertEquals(
            ["GARDEN 12000", "CONTENTS 3000"],
            readBack["damageItems"].values().map {
                "${it["category"].stringValue()} ${it["estimatedAmountMinor"].longValue()}"
            },
        )
    }

    @Test
    fun `fileClaim with kaiju class 6 is a tool error naming the field and creates no claim`() {
        val before = jdbc.queryForObject<Int>("SELECT count(*) FROM claims")

        val result = mcp.call("fileClaim", fileClaimArguments(kaijuClass = 6))

        assertTrue(result.failed)
        assertTrue("kaijuClass" in result.text, result.text)
        assertEquals(before, jdbc.queryForObject<Int>("SELECT count(*) FROM claims"))
    }

    private fun fileClaimArguments(kaijuClass: Int): Map<String, Any> =
        mapOf(
            "policyNumber" to SANDBOX_POLICY,
            "district" to "RIVERSIDE",
            "incidentAt" to "2026-11-19T08:15:00+01:00",
            "kaijuClass" to kaijuClass,
            "description" to "A kaiju rolled through the allotments.",
            "damageItems" to listOf(
                mapOf("category" to "GARDEN", "description" to "Shed flattened", "estimatedAmountMinor" to 12000),
                mapOf("category" to "CONTENTS", "description" to "Lawn mower", "estimatedAmountMinor" to 3000),
            ),
        )
}
