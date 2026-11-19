package dev.frederikpietzko.springaidemo.backend.adjuster

import org.springframework.ai.mcp.McpToolNamePrefixGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wiring of the MCP client that connects the adjuster to the core system (`core-mcp`). */
@Configuration(proxyBeanMethods = false)
class McpClientConfiguration {
    /**
     * Core tool names stay exactly as published: the confirmation property (`spring.ai.tools.confirmation.tools`)
     * matches by name. The default generator would rename a second `issuePayout` to `alt_1_issuePayout`, which the
     * property no longer gates; with this one a duplicate name fails instead.
     */
    @Bean
    fun mcpToolNamePrefixGenerator(): McpToolNamePrefixGenerator = McpToolNamePrefixGenerator.noPrefix()
}
