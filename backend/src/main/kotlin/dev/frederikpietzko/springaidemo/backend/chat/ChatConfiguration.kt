package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springai.kotlin.build
import dev.frederikpietzko.springai.kotlin.windowCompaction
import dev.frederikpietzko.springaidemo.backend.guardrail.GuardrailAdvisor
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.decision.DecisionModel
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.ai.session.SessionService
import org.springframework.ai.session.compaction.TurnCountTrigger
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
     * The claimant agent: system prompt, advisors and the recall tool. The advisors run by order, not as listed:
     * audit (outermost), guardrail, session turn, retrieval (ADR-0006), the tool loop and, inside it, session memory.
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
    ): ChatClient = builder.build {
        system(claimantSystemPrompt)
        advisors {
            +AuditAdvisor(auditTrail)
            +GuardrailAdvisor(decisionModel)
            +policyWordingAdvisor
            // Synchronous and without a summarizer: no extra model call, done before the reply completes.
            session(sessionService) {
                compaction(TurnCountTrigger(MAX_TURNS), windowCompaction(lastTurns = KEPT_TURNS))
            }
        }
        tools(conversationSearch)
    }
}
