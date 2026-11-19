package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.guardrail.GuardrailAdvisor
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.decision.DecisionModel
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.ai.session.SessionService
import org.springframework.ai.session.advisor.SessionAdvisors
import org.springframework.ai.session.compaction.TurnCountTrigger
import org.springframework.ai.session.compaction.WindowCompactionStrategy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource

/** A conversation with more user turns than this is compacted after the turn that crossed the limit. */
const val MAX_TURNS = 10

/** Compaction keeps this many of the most recent turns in the prompt; the log keeps everything. */
const val KEPT_TURNS = 5

/** The chat clients of the claims desk, one per agent. */
@Configuration(proxyBeanMethods = false)
class ChatConfiguration {
    /**
     * The claimant agent's chat client: the claimant instructions as its system prompt, the [AuditAdvisor], the
     * prompt-injection [GuardrailAdvisor], the policy wording retrieval (ADR-0006), the session advisors (conversation
     * memory) and the recall tool. The prompt has no parameters, so it is not rendered as a template and may contain
     * braces. The advisors are sorted by order: audit (outermost, sees the typed text), the guardrail (before anything
     * stores or retrieves the message), session turn, retrieval, the tool-calling loop and, inside it, session memory.
     */
    @Bean
    @Suppress("LongParameterList") // A bean function takes what it wires.
    fun claimantChatClient(
        builder: ChatClient.Builder,
        @Value("classpath:prompts/claimant-system.md") claimantSystemPrompt: Resource,
        auditTrail: AuditTrail,
        policyWordingAdvisor: RetrievalAugmentationAdvisor,
        sessionService: SessionService,
        conversationSearch: ConversationSearch,
        decisionModel: DecisionModel,
    ): ChatClient = builder
        .defaultSystem(claimantSystemPrompt, Charsets.UTF_8)
        .defaultAdvisors(AuditAdvisor(auditTrail), GuardrailAdvisor(decisionModel), policyWordingAdvisor)
        .defaultAdvisors(
            SessionAdvisors.builder(sessionService)
                // Synchronous and without a summarizer: no extra model call, done before the reply completes.
                .compaction(
                    TurnCountTrigger(MAX_TURNS),
                    WindowCompactionStrategy.builder().lastTurns(KEPT_TURNS).build(),
                )
                .build(),
        )
        .defaultTools(conversationSearch)
        .build()
}
