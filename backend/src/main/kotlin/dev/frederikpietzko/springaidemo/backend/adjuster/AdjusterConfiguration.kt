package dev.frederikpietzko.springaidemo.backend.adjuster

import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource

/** The adjuster agent's chat client. */
@Configuration(proxyBeanMethods = false)
class AdjusterConfiguration {
    /**
     * The adjuster instructions as the system prompt. The core tools of `core-mcp` are no default tools here:
     * `AdjusterChat` passes them with every request, because it wraps `payoutStats` per request to capture its result
     * as a table.
     */
    @Bean
    fun adjusterChatClient(
        builder: ChatClient.Builder,
        @Value("classpath:prompts/adjuster-system.md") adjusterSystemPrompt: Resource,
    ): ChatClient = builder
        .defaultSystem(adjusterSystemPrompt, Charsets.UTF_8)
        .build()
}
