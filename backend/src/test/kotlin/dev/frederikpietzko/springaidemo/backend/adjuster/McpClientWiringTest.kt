package dev.frederikpietzko.springaidemo.backend.adjuster

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.frederikpietzko.springaidemo.backend.intake.ClaimantCore
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.mcp.McpConnectionInfo
import org.springframework.ai.mcp.McpToolNamePrefixGenerator
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration
import org.springframework.ai.model.tool.ToolCallConfirmationRequiredException
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLAIMANT = "claimant-agent"
private const val ADJUSTER = "adjuster-agent"
private val CLAIMANT_SCOPES: Set<String> = ["policies:read", "claims:read", "claims:write"]
private val ADJUSTER_SCOPES: Set<String> = CLAIMANT_SCOPES + setOf("payout:write", "analytics:read")

/**
 * The committed MCP client wiring, outside the shared integration context (which switches the MCP client off). The
 * real client, transport, tool-callback and tool-calling auto-configurations run with the committed YAML,
 * [McpClientConfiguration] and [McpClientOAuthConfiguration]; nothing connects, because the client starts
 * uninitialised. The test JVM has `CORE_MCP_URL`, `AUTH_SERVER_URL` and the agent secrets removed, so the committed
 * defaults are the ones under test; the token tests point both at a [StubAuthServerAndCore].
 */
class McpClientWiringTest {
    private val runner =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(
                AutoConfigurations.of(
                    McpClientAutoConfiguration::class.java,
                    McpToolCallbackAutoConfiguration::class.java,
                    StreamableHttpHttpClientTransportAutoConfiguration::class.java,
                    ToolCallingAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(McpClientConfiguration::class.java, McpClientOAuthConfiguration::class.java)

    @Test
    fun `the committed configuration points one connection per persona at the core system`() {
        runner.run { context ->
            val connections = context.getBean(McpStreamableHttpClientProperties::class.java).connections

            assertEquals("http://localhost:8081", connections["core-adjuster"]?.url())
            assertEquals("http://localhost:8081", connections["core-claimant"]?.url())
            assertEquals(
                setOf("adjusterCoreTools", "claimantCoreTools"),
                context.getBeansOfType(SyncMcpToolCallbackProvider::class.java).keys,
            )
        }
    }

    @Test
    fun `the context starts without the core system and the auth-server`() {
        runner.run { context ->
            assertNull(context.startupFailure)
        }
    }

    @Test
    fun `each persona fetches its own token by client credentials`() {
        StubAuthServerAndCore().use { stub ->
            runner.withPropertyValues(*stub.urls).run { context ->
                val manager = context.getBean(AuthorizedClientServiceOAuth2AuthorizedClientManager::class.java)

                val claimantToken = manager.tokenOf(CLAIMANT)
                val adjusterToken = manager.tokenOf(ADJUSTER)

                assertEquals("t-$CLAIMANT", claimantToken)
                assertEquals("t-$ADJUSTER", adjusterToken)
                assertEquals(
                    [
                        TokenRequest(CLAIMANT, "client_credentials", CLAIMANT_SCOPES),
                        TokenRequest(ADJUSTER, "client_credentials", ADJUSTER_SCOPES),
                    ],
                    stub.tokenRequests.toList(),
                )
            }
        }
    }

    @Test
    fun `each connection sends its persona's token to the core system`() {
        StubAuthServerAndCore().use { stub ->
            runner.withPropertyValues(*stub.urls).run { context ->
                assertFails { context.getBean("adjusterCoreTools", ToolCallbackProvider::class.java).toolCallbacks }
                val adjusterRequests = stub.coreAuthorizations.toList()
                assertFails { context.getBean("claimantCoreTools", ToolCallbackProvider::class.java).toolCallbacks }
                val claimantRequests = stub.coreAuthorizations.drop(adjusterRequests.size)

                assertEquals("Bearer t-$ADJUSTER", adjusterRequests.firstOrNull())
                assertEquals("Bearer t-$CLAIMANT", claimantRequests.firstOrNull())
            }
        }
    }

    @Test
    fun `claims are filed over the claimant connection with the claimant's token`() {
        StubAuthServerAndCore().use { stub ->
            runner.withPropertyValues(*stub.urls).run { context ->
                val core = context.getBean(ClaimantCore::class.java)

                assertFails { core.callTool(McpSchema.CallToolRequest.builder("fileClaim").build()) }

                assertEquals("Bearer t-$CLAIMANT", stub.coreAuthorizations.firstOrNull())
            }
        }
    }

    @Test
    fun `only the identity keys of the tool context reach the core system`() {
        val context = ToolContext(mapOf("claimantId" to 1, "actor" to CLAIMANT, "conversationId" to "c-1"))

        val meta = identityMeta(context)

        assertEquals(mapOf<String, Any>("claimantId" to 1, "actor" to CLAIMANT), meta)
    }

    @Test
    fun `a tool context without identity keys sends no meta`() {
        val context = ToolContext(mapOf("conversationId" to "c-1"))

        val meta = identityMeta(context)

        assertEquals(emptyMap(), meta)
    }

    @Test
    fun `published tool names are not rewritten, not even for a second connection`() {
        runner.run { context ->
            val generator = context.getBean(McpToolNamePrefixGenerator::class.java)
            val issuePayout = McpSchema.Tool.builder("issuePayout", mapOf("type" to "object")).build()

            val first = generator.prefixedToolName(connection("spring-ai-mcp-client - core"), issuePayout)
            val second = generator.prefixedToolName(connection("spring-ai-mcp-client - core-two"), issuePayout)

            assertEquals("issuePayout", first)
            assertEquals("issuePayout", second)
        }
    }

    @Test
    fun `the tool calling manager needs a confirmation for issuePayout`() {
        runner.run { context ->
            val manager = context.getBean(ToolCallingManager::class.java)

            assertFailsWith<ToolCallConfirmationRequiredException> {
                manager.executeToolCalls(coreToolsPrompt(FakeCoreTools()), replyCalling("issuePayout"))
            }
        }
    }

    @Test
    fun `the tool calling manager runs getClaim without a confirmation`() {
        runner.run { context ->
            val manager = context.getBean(ToolCallingManager::class.java)
            val tools = FakeCoreTools()

            val result = manager.executeToolCalls(coreToolsPrompt(tools), replyCalling("getClaim"))

            assertEquals(["getClaim"], tools.calls.map { it.tool })
            val toolResponse = assertNotNull(result.conversationHistory().last() as? ToolResponseMessage)
            assertTrue(toolResponse.responses.single().responseData.contains("4711"), toolResponse.toString())
        }
    }

    private fun AuthorizedClientServiceOAuth2AuthorizedClientManager.tokenOf(registrationId: String): String? =
        authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId).principal("test").build())
            ?.accessToken
            ?.tokenValue

    private fun connection(clientName: String): McpConnectionInfo =
        McpConnectionInfo.builder()
            .clientCapabilities(McpSchema.ClientCapabilities.builder().build())
            .clientInfo(McpSchema.Implementation.builder(clientName, "1.0.0").build())
            .build()

    /** A prompt whose options carry both core tools of [tools] and a tool context, as the adjuster sends it. */
    private fun coreToolsPrompt(tools: FakeCoreTools): Prompt {
        val conversation: List<Message> = [UserMessage("Claim 4711")]
        return Prompt(
            conversation,
            ToolCallingChatOptions.builder()
                .toolCallbacks(*ToolCallbacks.from(tools))
                .toolContext("actor", "adjuster-agent")
                .build(),
        )
    }

    private fun replyCalling(tool: String): ChatResponse {
        val call = AssistantMessage.ToolCall("call-1", "function", tool, """{"claimNumber":4711}""")
        return ChatResponse([Generation(AssistantMessage.builder().toolCalls([call]).build())])
    }
}

/** What the stub token endpoint received: the client id of the Basic credentials, the grant and the scopes. */
private data class TokenRequest(val clientId: String, val grantType: String?, val scopes: Set<String>)

/**
 * A JDK HTTP server that stands in for the auth-server and the core system on one loopback port. `/oauth2/token`
 * issues the token `t-<client id>` and records the request; `/mcp` records the `Authorization` header and answers 401.
 */
private class StubAuthServerAndCore : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val tokenRequests = CopyOnWriteArrayList<TokenRequest>()
    val coreAuthorizations = CopyOnWriteArrayList<String>()

    /** The properties that point the backend at this stub. */
    val urls: Array<String>
        get() {
            val url = "http://127.0.0.1:${server.address.port}"
            return arrayOf("AUTH_SERVER_URL=$url", "CORE_MCP_URL=$url")
        }

    init {
        server.createContext("/oauth2/token", ::token)
        server.createContext("/mcp") { exchange ->
            coreAuthorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
    }

    private fun token(exchange: HttpExchange) {
        val basic = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Basic ")
        val clientId = String(Base64.getDecoder().decode(basic)).substringBefore(":")
        val form = exchange.requestBody.readAllBytes().decodeToString().split("&").associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8)
        }
        val scopes = form["scope"].orEmpty().split(" ").filter { it.isNotEmpty() }.toSet()
        tokenRequests += TokenRequest(clientId, form["grant_type"], scopes)
        val body = JsonMapper.shared().writeValueAsBytes(
            mapOf("access_token" to "t-$clientId", "token_type" to "Bearer", "expires_in" to 300),
        )
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    override fun close() = server.stop(0)
}
