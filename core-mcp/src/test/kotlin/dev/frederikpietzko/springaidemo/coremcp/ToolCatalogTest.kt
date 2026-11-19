package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import io.modelcontextprotocol.spec.McpSchema.Tool
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val READ_TOOLS: Set<String> = ["findPolicy", "getClaim", "listClaimsForCustomer"]
private val WRITE_TOOLS: Set<String> = ["fileClaim", "updateClaimStatus", "issuePayout"]

/** The tool catalogue is the model-facing contract that the wave-2 agent builds on. */
@CoreMcpIntegrationTest
class ToolCatalogTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Test
    fun `exactly the six tools are listed, each with a description`() {
        val tools = mcp.listTools()

        assertEquals(READ_TOOLS + WRITE_TOOLS, tools.map { it.name() }.toSet())
        assertEquals(6, tools.size)
        assertEquals([], tools.filter { it.description().isNullOrBlank() }.map { it.name() })
    }

    @Test
    fun `only the read tools carry the read-only hint`() {
        val readOnly = mcp.listTools().filter { it.annotations()?.readOnlyHint() == true }.map { it.name() }

        assertEquals(READ_TOOLS, readOnly.toSet())
    }

    @Test
    fun `getClaim requires a claimNumber`() {
        val schema = schemaOf("getClaim")

        assertEquals(["claimNumber"], schema["required"].values().map { it.stringValue() })
    }

    @Test
    fun `updateClaimStatus takes a claimNumber and a status limited to the claim status names`() {
        val properties = schemaOf("updateClaimStatus")["properties"]

        assertTrue(properties.has("claimNumber"), "$properties")
        assertEquals(
            ["DRAFT", "FILED", "UNDER_REVIEW", "APPROVED", "REJECTED", "PAID"],
            properties["status"]["enum"].values().map { it.stringValue() },
        )
    }

    @Test
    fun `fileClaim documents the fields of its damage items`() {
        val damageItems = schemaOf("fileClaim")["properties"]["damageItems"]

        val itemFields = damageItems["items"]["properties"].propertyNames().toSet()
        assertEquals(["category", "description", "estimatedAmountMinor"], itemFields)
        assertTrue(damageItems["description"].stringValue().isNotBlank(), "$damageItems")
    }

    private fun schemaOf(toolName: String): JsonNode {
        val tool: Tool = mcp.listTools().single { it.name() == toolName }
        return json.valueToTree(tool.inputSchema())
    }
}
