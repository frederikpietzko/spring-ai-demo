package dev.frederikpietzko.springaidemo.coremcp.testsupport

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.Implementation
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.env.Environment
import java.time.Duration

private val REQUEST_TIMEOUT = Duration.ofSeconds(10)

/**
 * The MCP SDK client the tests use: it talks Streamable HTTP to the running server on its random port (the default
 * endpoint `/mcp`), exactly as an agent would. It connects and initializes on first use, and disconnects when the
 * context starts closing, before the server stops, so the server never waits for the client's open stream.
 */
class McpTestClient(private val environment: Environment) : ApplicationListener<ContextClosedEvent> {
    private val connection = lazy {
        val port = environment.getRequiredProperty("local.server.port")
        McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port").build())
            .requestTimeout(REQUEST_TIMEOUT)
            .build()
            .also { it.initialize() }
    }
    private val client: McpSyncClient by connection

    /** Name and version the server reported in the initialize handshake. */
    fun serverInfo(): Implementation = client.serverInfo

    /** Calls the tool [name] with [arguments] (JSON-like values: strings, numbers, lists, maps). */
    fun call(name: String, arguments: Map<String, Any>): CallToolResult =
        client.callTool(CallToolRequest.builder(name).arguments(arguments).build())

    fun listTools(): List<Tool> = client.listTools().tools()

    fun listResourceTemplates(): List<ResourceTemplate> = client.listResourceTemplates().resourceTemplates()

    fun readResource(uri: String): ReadResourceResult = client.readResource(ReadResourceRequest.builder(uri).build())

    override fun onApplicationEvent(event: ContextClosedEvent) {
        if (connection.isInitialized()) client.close()
    }
}

/** The text of a tool result's single text content (the JSON or the error sentence). */
val CallToolResult.text: String
    get() = (content().single() as TextContent).text()

/** `true` only when the server marked the result as an error (`isError` absent counts as success). */
val CallToolResult.failed: Boolean
    get() = isError() == true
