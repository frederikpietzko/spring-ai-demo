package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
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
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The committed MCP client wiring, outside the shared integration context (which switches the MCP client off). The
 * real client, transport, tool-callback and tool-calling auto-configurations run with the committed YAML and
 * [McpClientConfiguration]; nothing connects, because the client starts uninitialised. The test JVM has
 * `CORE_MCP_URL` removed, so the default URL is the one under test.
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
            .withUserConfiguration(McpClientConfiguration::class.java)

    @Test
    fun `the committed configuration points the connection core at the core system`() {
        runner.run { context ->
            val core = context.getBean(McpStreamableHttpClientProperties::class.java).connections["core"]

            assertEquals("http://localhost:8081", core?.url())
            assertEquals(1, context.getBeansOfType(SyncMcpToolCallbackProvider::class.java).size)
        }
    }

    @Test
    fun `the context starts without the core system`() {
        runner.run { context ->
            assertNull(context.startupFailure)
        }
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
