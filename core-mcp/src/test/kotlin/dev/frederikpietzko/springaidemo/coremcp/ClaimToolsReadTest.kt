package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The read tools over MCP, contract only (the rules are tested on `ClaimService`). */
@CoreMcpIntegrationTest
class ClaimToolsReadTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Test
    fun `getClaim returns claim 4711 with its items, integer amounts and an ISO incident time`() {
        val result = mcp.call("getClaim", mapOf("claimNumber" to 4711))

        assertFalse(result.failed, result.text)
        val claim = json.readTree(result.text)
        assertEquals(4711, claim["claimNumber"].longValue())
        assertEquals("FILED", claim["status"].stringValue())
        assertEquals("HARBOUR", claim["district"].stringValue())
        assertEquals(3, claim["kaijuClass"].asInt())
        assertEquals("2026-11-18T15:00:00Z", claim["incidentAt"].stringValue())
        assertEquals(804500, claim["totalEstimatedMinor"].longValue())
        val items = claim["damageItems"].values()
        assertEquals([800000L, 4500L], items.map { it["estimatedAmountMinor"].longValue() })
        assertTrue(items.all { it["estimatedAmountMinor"].isIntegralNumber })
        assertTrue(claim["totalEstimatedMinor"].isIntegralNumber)
    }

    @Test
    fun `getClaim with an unknown number is one tool error naming it`() {
        val result = mcp.call("getClaim", mapOf("claimNumber" to 999999))

        assertTrue(result.failed)
        assertTrue("999999" in result.text, result.text)
    }

    @Test
    fun `listClaimsForCustomer lists the one claim of customer 1`() {
        val result = mcp.call("listClaimsForCustomer", mapOf("customerId" to 1))

        assertFalse(result.failed, result.text)
        assertEquals([4711L], json.readTree(result.text).values().map { it["claimNumber"].longValue() })
    }

    @Test
    fun `listClaimsForCustomer returns an empty list for customer 5`() {
        val result = mcp.call("listClaimsForCustomer", mapOf("customerId" to 5))

        assertFalse(result.failed, result.text)
        assertEquals("[]", result.text)
    }

    @Test
    fun `listClaimsForCustomer with an unknown customer is one tool error naming it`() {
        val result = mcp.call("listClaimsForCustomer", mapOf("customerId" to 999999))

        assertTrue(result.failed)
        assertTrue("999999" in result.text, result.text)
    }
}
