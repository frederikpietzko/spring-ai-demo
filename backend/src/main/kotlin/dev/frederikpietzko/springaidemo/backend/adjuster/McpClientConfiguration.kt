package dev.frederikpietzko.springaidemo.backend.adjuster

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

/**
 * What the core system receives of a tool context as MCP `_meta`: the identity keys only. Everything else, such as the
 * claimant's conversation id, stays in the backend (the default converter would forward every entry).
 */
fun identityMeta(toolContext: ToolContext?): Map<String, Any> =
    toolContext?.context.orEmpty().filterKeys { it in IDENTITY_KEYS }

/**
 * Wiring of the MCP client that connects the agents to the core system (`core-mcp`): one connection and one tool
 * provider per persona (ADR-0009), so each persona's tools reach the core with that persona's token. The provider
 * beans exist only while the MCP client is on (the shared test context switches it off and fakes the tools).
 */
@Configuration(proxyBeanMethods = false)
class McpClientConfiguration {
    /**
     * Core tool names stay exactly as published: the confirmation property (`spring.ai.tools.confirmation.tools`)
     * matches by name. The default generator would rename a second `issuePayout` to `alt_1_issuePayout`, which the
     * property no longer gates; with this one a duplicate name fails instead.
     */
    @Bean
    fun mcpToolNamePrefixGenerator(): McpToolNamePrefixGenerator = McpToolNamePrefixGenerator.noPrefix()

    /** The core tools as the adjuster agent sees them (connection `core-adjuster`). */
    @Bean
    @ConditionalOnBooleanProperty("spring.ai.mcp.client.enabled", matchIfMissing = true)
    fun adjusterCoreTools(
        @Qualifier("mcpSyncClients") clients: List<McpSyncClient>,
        toolNames: McpToolNamePrefixGenerator,
    ): SyncMcpToolCallbackProvider = personaTools(clients, "core-adjuster", toolNames)

    /** The core tools as the claimant agent sees them (connection `core-claimant`); no chat client uses them yet. */
    @Bean
    @ConditionalOnBooleanProperty("spring.ai.mcp.client.enabled", matchIfMissing = true)
    fun claimantCoreTools(
        @Qualifier("mcpSyncClients") clients: List<McpSyncClient>,
        toolNames: McpToolNamePrefixGenerator,
    ): SyncMcpToolCallbackProvider = personaTools(clients, "core-claimant", toolNames)

    /** The tools of the one client of [connection] (the auto-configuration puts the connection name in the title). */
    private fun personaTools(
        clients: List<McpSyncClient>,
        connection: String,
        toolNames: McpToolNamePrefixGenerator,
    ): SyncMcpToolCallbackProvider =
        SyncMcpToolCallbackProvider.builder()
            .mcpClients(clients.single { it.clientInfo.title() == connection })
            .toolNamePrefixGenerator(toolNames)
            .toolContextToMcpMetaConverter(::identityMeta)
            .build()
}
