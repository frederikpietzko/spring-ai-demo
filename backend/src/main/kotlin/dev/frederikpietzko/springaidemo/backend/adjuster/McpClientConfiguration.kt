package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.intake.ClaimantCore
import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.mcp.McpToolNamePrefixGenerator
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The tool-context keys that say who calls: the persona (`actor`) and, for the claimant, the customer id. */
private val IDENTITY_KEYS: Set<String> = ["actor", "claimantId"]

/** Only the identity keys of a tool context travel to the core as MCP `_meta`; the rest stays in the backend. */
fun identityMeta(toolContext: ToolContext?): Map<String, Any> =
    toolContext?.context.orEmpty().filterKeys { it in IDENTITY_KEYS }

/**
 * The MCP client side of the core system (`core-mcp`): one connection per persona (ADR-0009), each sending that
 * persona's token. The shared test context switches the MCP client off and fakes these beans.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("spring.ai.mcp.client.enabled", matchIfMissing = true)
class McpClientConfiguration {
    /** Tool names stay as published: `spring.ai.tools.confirmation.tools` matches `issuePayout` by name. */
    @Bean
    fun mcpToolNamePrefixGenerator(): McpToolNamePrefixGenerator = McpToolNamePrefixGenerator.noPrefix()

    /** The core tools as the adjuster agent sees them (connection `core-adjuster`). */
    @Bean
    fun adjusterCoreTools(
        @Qualifier("mcpSyncClients") clients: List<McpSyncClient>,
        toolNames: McpToolNamePrefixGenerator,
    ): SyncMcpToolCallbackProvider =
        SyncMcpToolCallbackProvider.builder()
            .mcpClients(clients.of("core-adjuster"))
            .toolNamePrefixGenerator(toolNames)
            .toolContextToMcpMetaConverter(::identityMeta)
            .build()

    /** The claimant's direct tool calls (connection `core-claimant`): the claim intake files claims through it. */
    @Bean
    fun claimantCore(@Qualifier("mcpSyncClients") clients: List<McpSyncClient>): ClaimantCore =
        ClaimantCore(clients.of("core-claimant")::callTool)

    /** The auto-configuration puts the connection name in the client's title. */
    private fun List<McpSyncClient>.of(connection: String): McpSyncClient =
        single { it.clientInfo.title() == connection }
}
