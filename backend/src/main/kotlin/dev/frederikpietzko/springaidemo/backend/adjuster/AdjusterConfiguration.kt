package dev.frederikpietzko.springaidemo.backend.adjuster

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource

/** The adjuster agent's chat client. */
@Configuration(proxyBeanMethods = false)
class AdjusterConfiguration {
    /**
     * The adjuster instructions as the system prompt and the core tools of `core-mcp`. MCP tools are not registered
     * on a chat client automatically, so they are added here explicitly; the provider lists them on every request,
     * which is when the MCP client connects for the first time.
     */
    @Bean
    fun adjusterChatClient(
        builder: ChatClient.Builder,
        @Value("classpath:prompts/adjuster-system.md") adjusterSystemPrompt: Resource,
        @Qualifier("adjusterCoreTools") coreTools: ToolCallbackProvider,
    ): ChatClient = builder
        .defaultSystem(adjusterSystemPrompt, Charsets.UTF_8)
        .defaultTools(coreTools)
        .build()
}
