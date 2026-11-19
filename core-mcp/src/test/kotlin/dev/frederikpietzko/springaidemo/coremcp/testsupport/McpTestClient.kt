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
 * endpoint `/mcp`), exactly as an agent would, and sends the access token [fetchToken] returns as its bearer token
 * (fetched once, on first use). It connects and initializes on first use, and disconnects when the context starts
 * closing, before the server stops, so the server never waits for the client's open stream. A client a test builds
 * itself is closed by that test.
 */
class McpTestClient(private val environment: Environment, fetchToken: () -> String) :
    ApplicationListener<ContextClosedEvent>, AutoCloseable {
    /** The bearer token this client sends with every request. */
    val accessToken: String by lazy(fetchToken)

    private val connection = lazy {
        val port = environment.getRequiredProperty("local.server.port")
        val transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port")
            .addHttpRequestCustomizer { request, _, _, _, _ -> request.header("Authorization", "Bearer $accessToken") }
            .build()
        McpClient.sync(transport)
            .requestTimeout(REQUEST_TIMEOUT)
            .build()
            .also { it.initialize() }
    }
    private val client: McpSyncClient by connection

    /** Name and version the server reported in the initialize handshake. */
    fun serverInfo(): Implementation = client.serverInfo

    /**
     * Calls the tool [name] with [arguments] (JSON-like values: strings, numbers, lists, maps) and [meta] as the
     * request `_meta` (where the backend puts the claimant id).
     */
    fun call(name: String, arguments: Map<String, Any>, meta: Map<String, Any> = emptyMap()): CallToolResult =
        client.callTool(CallToolRequest.builder(name).arguments(arguments).meta(meta).build())

    fun listTools(): List<Tool> = client.listTools().tools()

    fun listResourceTemplates(): List<ResourceTemplate> = client.listResourceTemplates().resourceTemplates()

    /** Reads the resource [uri] with [meta] as the request `_meta`. */
    fun readResource(uri: String, meta: Map<String, Any> = emptyMap()): ReadResourceResult =
        client.readResource(ReadResourceRequest.builder(uri).meta(meta).build())

    override fun onApplicationEvent(event: ContextClosedEvent) = close()

    override fun close() {
        if (connection.isInitialized()) client.close()
    }
}

/** A claimant client whose token holds only [scopes] (a test closes it, `use { ... }`). */
fun claimantClientWithScopes(environment: Environment, authServer: AuthServerProcess, scopes: String): McpTestClient =
    McpTestClient(environment) { authServer.token(CLAIMANT_AGENT, scopes) }

/** The text of a tool result's single text content (the JSON or the error sentence). */
val CallToolResult.text: String
    get() = (content().single() as TextContent).text()

/** `true` only when the server marked the result as an error (`isError` absent counts as success). */
val CallToolResult.failed: Boolean
    get() = isError() == true
