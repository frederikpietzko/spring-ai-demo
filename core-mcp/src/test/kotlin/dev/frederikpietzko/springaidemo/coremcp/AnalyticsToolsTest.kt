package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The analytics tools over MCP: nested filter arguments, JSON results and tool errors (rules: the service test). */
@CoreMcpIntegrationTest
class AnalyticsToolsTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Test
    fun `countClaims counts by district and class`() {
        val result = mcp.call("countClaims", mapOf("filter" to mapOf("district" to "HARBOUR", "kaijuClass" to 4)))

        assertFalse(result.failed, result.text)
        assertEquals("""{"count":4}""", result.text)
    }

    @Test
    fun `countClaims takes a period whose days are both inclusive`() {
        val filter = mapOf("district" to "HARBOUR", "kaijuClass" to 4, "from" to "2026-11-01")

        val upToTheNinth = mcp.call("countClaims", mapOf("filter" to filter + ("to" to "2026-11-09")))
        val upToTheEighth = mcp.call("countClaims", mapOf("filter" to filter + ("to" to "2026-11-08")))

        assertEquals("""{"count":3}""", upToTheNinth.text)
        assertEquals("""{"count":2}""", upToTheEighth.text)
    }

    @Test
    fun `countClaims filters by status and period`() {
        val filter = mapOf("district" to "HILLTOP", "status" to "PAID")

        val allTime = mcp.call("countClaims", mapOf("filter" to filter))
        val fromNovember = mcp.call("countClaims", mapOf("filter" to filter + ("from" to "2026-11-01")))

        assertEquals("""{"count":2}""", allTime.text)
        assertEquals("""{"count":0}""", fromNovember.text)
    }

    @ParameterizedTest
    @MethodSource("invalidCountFilters")
    fun `an invalid countClaims filter is a tool error naming the value`(filter: Map<String, Any>, value: String) {
        val result = mcp.call("countClaims", mapOf("filter" to filter))

        assertTrue(result.failed, result.text)
        assertTrue(value in result.text, result.text)
    }

    @Test
    fun `payoutStats without groupBy answers one ALL group and groupBy null`() {
        val result = mcp.call("payoutStats", mapOf("filter" to mapOf("district" to "HARBOUR", "kaijuClass" to 4)))

        assertFalse(result.failed, result.text)
        assertEquals(
            """{"groupBy":null,"groups":[{"group":"ALL","payouts":2,""" +
                """"totalPaidMinor":980000,"averagePaidMinor":490000}]}""",
            result.text,
        )
    }

    @Test
    fun `payoutStats groups by kaiju class`() {
        val arguments = mapOf("filter" to mapOf("district" to "HARBOUR"), "groupBy" to "KAIJU_CLASS")

        val result = mcp.call("payoutStats", arguments)

        assertFalse(result.failed, result.text)
        val stats = json.readTree(result.text)
        assertEquals("KAIJU_CLASS", stats["groupBy"].stringValue())
        assertEquals(
            ["2 1 140000 140000", "4 2 980000 490000"],
            stats["groups"].values().map {
                "${it["group"].stringValue()} ${it["payouts"].longValue()} " +
                    "${it["totalPaidMinor"].longValue()} ${it["averagePaidMinor"].longValue()}"
            },
        )
    }

    @Test
    fun `payoutStats with nothing paid answers no group`() {
        val result = mcp.call("payoutStats", mapOf("filter" to mapOf("district" to "OLD_TOWN", "status" to "REJECTED")))

        assertFalse(result.failed, result.text)
        assertEquals([], json.readTree(result.text)["groups"].values().toList())
    }

    @Test
    fun `an invalid payoutStats filter is a tool error naming the value`() {
        val result = mcp.call("payoutStats", mapOf("filter" to mapOf("to" to "31.11.2026")))

        assertTrue(result.failed, result.text)
        assertTrue("31.11.2026" in result.text, result.text)
    }

    @Test
    fun `the payoutStats schema has a required filter of optional typed fields and an optional groupBy`() {
        val schema = schemaOf("payoutStats")

        val filter = schema["properties"]["filter"]
        assertEquals(["filter"], schema["required"].values().map { it.stringValue() })
        val fields: Set<String> = ["district", "kaijuClass", "status", "from", "to"]
        assertEquals(fields, filter["properties"].propertyNames().toSet())
        assertEquals([], filter["required"]?.values()?.toList().orEmpty())
        assertEquals(
            ["HARBOUR", "OLD_TOWN", "NEON_MARKET", "RIVERSIDE", "HILLTOP"],
            filter["properties"]["district"].enumNames(),
        )
        assertEquals(
            ["DRAFT", "FILED", "UNDER_REVIEW", "APPROVED", "REJECTED", "PAID"],
            filter["properties"]["status"].enumNames(),
        )
        assertEquals(
            ["DISTRICT", "KAIJU_CLASS"],
            schema["properties"]["groupBy"].enumNames(),
        )
    }

    @Test
    fun `both analytics tools tell the model to send an empty filter for all claims`() {
        val descriptions = ["countClaims", "payoutStats"].map { schemaOf(it)["properties"]["filter"]["description"] }

        assertTrue(descriptions.all { "send {} for all claims" in it.stringValue() }, "$descriptions")
    }

    /** The enum names of a property; a nullable field has them in the non-null branch of its `anyOf`. */
    private fun JsonNode.enumNames(): List<String> {
        val names = get("enum") ?: get("anyOf").values().single { it.has("enum") }["enum"]
        return names.values().map { it.stringValue() }
    }

    private fun schemaOf(toolName: String): JsonNode =
        json.valueToTree(mcp.listTools().single { it.name() == toolName }.inputSchema())

    companion object {
        @JvmStatic
        fun invalidCountFilters(): List<Arguments> = [
            Arguments.of(mapOf("from" to "last week"), "last week"),
            Arguments.of(mapOf("from" to "2026-11-10", "to" to "2026-11-01"), "2026-11-10"),
            Arguments.of(mapOf("kaijuClass" to 9), "9"),
        ]
    }
}
