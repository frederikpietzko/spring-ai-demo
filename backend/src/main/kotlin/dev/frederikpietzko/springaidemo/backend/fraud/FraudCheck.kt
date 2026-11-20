package dev.frederikpietzko.springaidemo.backend.fraud

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.entity
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException

private val log = LoggerFactory.getLogger(FraudCheck::class.java)

/** Who calls the core tools, here and in the adjuster agent: sent in the tool context only, never in a message. */
val ADJUSTER_IDENTITY: Map<String, Any> = mapOf("actor" to "adjuster-agent")

/** A final reply that does not match the assessment's JSON schema is asked for once more, with the error appended. */
private const val ASSESSMENT_RETRIES = 1

/** The only core tool the fraud check may call: it reads, it never changes a claim or pays. */
private const val READ_ONLY_CORE_TOOL = "getClaim"

/**
 * Agentic RAG with structured output: one blocking call whose tool loop lets the model look up the claim and search
 * the bulletins as often as it needs, then answers a typed [FraudAssessment].
 */
@Service
class FraudCheck(
    builder: ChatClient.Builder,
    @Value("classpath:prompts/fraud-check-system.md") systemPrompt: Resource,
    @Qualifier("adjusterCoreTools") coreTools: ToolCallbackProvider,
    sightingBulletinSearch: SightingBulletinSearch,
) {
    /** Filtered on every request, so the MCP client still connects lazily and `issuePayout` is never offered. */
    private val readOnlyCoreTools = ToolCallbackProvider {
        coreTools.toolCallbacks.filter { it.toolDefinition.name() == READ_ONLY_CORE_TOOL }.toTypedArray()
    }

    private val chatClient = builder
        .defaultSystem(systemPrompt, Charsets.UTF_8)
        .defaultTools(readOnlyCoreTools, sightingBulletinSearch)
        .defaultAdvisors(
            // Inside the tool loop: it lets tool-call rounds through and checks only the final reply.
            StructuredOutputValidationAdvisor.builder()
                .outputType(FraudAssessment::class.java)
                .maxRepeatAttempts(ASSESSMENT_RETRIES)
                .build(),
        )
        .build()

    /** Assesses claim [claimNumber]; a reply that is still no assessment after the retry is `Unreadable`. */
    fun assess(claimNumber: Long): FraudCheckResult {
        val assessment = try {
            chatClient.prompt()
                .user("Check claim $claimNumber against the sighting bulletins.")
                .toolContext(ADJUSTER_IDENTITY)
                .call()
                .entity<FraudAssessment>()
        } catch (e: JacksonException) {
            // Malformed JSON, an unknown verdict or a value of the wrong type.
            log.warn("Unreadable fraud assessment", e)
            null
        }
        return assessment?.let { FraudCheckResult.Assessed(it) } ?: FraudCheckResult.Unreadable
    }
}
