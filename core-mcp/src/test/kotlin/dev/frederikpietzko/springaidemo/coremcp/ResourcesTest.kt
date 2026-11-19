package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The resources over MCP. An unknown id fails with an MCP error; its JSON-RPC code (-32602 from the annotation
 * callback) is deliberately not asserted, only the message (design D3).
 */
@CoreMcpIntegrationTest
class ResourcesTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var json: JsonMapper

    @Test
    fun `both resource templates are listed`() {
        val templates: List<String> = mcp.listResourceTemplates().map { it.uriTemplate() }

        assertTrue("policy://{policyNumber}/summary" in templates, "templates: $templates")
        assertTrue("claim://{claimNumber}" in templates, "templates: $templates")
    }

    @Test
    fun `a claim resource is the claim as JSON`() {
        val contents = mcp.readResource("claim://4711").contents().single() as TextResourceContents

        val claim = json.readTree(contents.text())
        assertEquals("application/json", contents.mimeType())
        assertEquals(4711, claim["claimNumber"].longValue())
        assertEquals("FILED", claim["status"].stringValue())
    }

    @Test
    fun `a policy summary is Markdown with number, holder and deductible`() {
        val contents = mcp.readResource("policy://KP-1001/summary").contents().single() as TextResourceContents

        val text: String = contents.text()
        assertEquals("text/markdown", contents.mimeType())
        assertTrue("KP-1001" in text, text)
        assertTrue("Mara Okafor" in text, text)
        assertTrue("50000" in text, text)
    }

    @ParameterizedTest
    @CsvSource("claim://999999, 999999", "claim://abc, abc", "policy://KP-0000/summary, KP-0000")
    fun `an unknown resource fails with an MCP error naming the value`(uri: String, value: String) {
        val failure = assertFailsWith<McpError> { mcp.readResource(uri) }

        val message = failure.message.orEmpty()
        assertTrue("not found" in message, message)
        assertTrue(value in message, message)
    }
}
