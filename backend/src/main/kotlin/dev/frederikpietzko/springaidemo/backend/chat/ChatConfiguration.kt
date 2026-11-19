package dev.frederikpietzko.springaidemo.backend.chat

import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource

/** The chat clients of the claims desk, one per agent. */
@Configuration(proxyBeanMethods = false)
class ChatConfiguration {
    /**
     * The claimant agent's chat client: the claimant instructions as its system prompt and the [AuditAdvisor]. The
     * prompt has no parameters, so it is not rendered as a template and may contain braces.
     */
    @Bean
    fun claimantChatClient(
        builder: ChatClient.Builder,
        @Value("classpath:prompts/claimant-system.md") claimantSystemPrompt: Resource,
        auditTrail: AuditTrail,
    ): ChatClient = builder
        .defaultSystem(claimantSystemPrompt, Charsets.UTF_8)
        .defaultAdvisors(AuditAdvisor(auditTrail))
        .build()
}
