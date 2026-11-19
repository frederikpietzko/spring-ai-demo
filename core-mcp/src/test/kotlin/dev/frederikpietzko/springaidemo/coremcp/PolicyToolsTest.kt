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

@CoreMcpIntegrationTest
class PolicyToolsTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Test
    fun `findPolicy returns the policy as JSON with integer amounts`() {
        val result = mcp.call("findPolicy", mapOf("policyNumber" to "KP-1001"))

        assertFalse(result.failed, result.text)
        val policy = json.readTree(result.text)
        assertEquals("KP-1001", policy["policyNumber"].stringValue())
        assertEquals(1, policy["customerId"].asLong())
        assertEquals("Mara Okafor", policy["holderName"].stringValue())
        assertEquals(50000, policy["deductibleMinor"].longValue())
        assertTrue(policy["deductibleMinor"].isIntegralNumber)
        assertEquals(["VEHICLE", "GARDEN"], policy["categories"].values().map { it.stringValue() })
        assertEquals("2026-01-01", policy["validFrom"].stringValue())
        assertEquals("2027-12-31", policy["validTo"].stringValue())
    }

    @Test
    fun `findPolicy with an unknown number is a tool error naming the number`() {
        val result = mcp.call("findPolicy", mapOf("policyNumber" to "KP-0000"))

        assertTrue(result.failed)
        assertTrue("KP-0000" in result.text, result.text)
    }
}
