package dev.frederikpietzko.springaidemo.backend.fraud

import dev.frederikpietzko.springaidemo.backend.springai.entity
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException

private val log = LoggerFactory.getLogger(FraudCheck::class.java)

/**
 * Who calls the core tools: the same fixed identity as the adjuster chat's, declared here because `fraud` must not
 * depend on `adjuster` (which depends on `fraud`). It travels in the tool context only, never in a message; the core
 * tools are the adjuster connection's, whose token proves the persona to the core (ADR-0009).
 */
private val ADJUSTER_IDENTITY: Map<String, Any> = mapOf("actor" to "adjuster-agent")

/**
 * How often a final reply that does not match the assessment's JSON schema (a missing `verdict`, say) is asked for
 * again, with the validation error appended to the user message. One retry: the error names the missing field.
 */
private const val ASSESSMENT_RETRIES = 1

/** The only core tool the fraud check may call: it reads, it never changes a claim or pays. */
private const val READ_ONLY_CORE_TOOL = "getClaim"

/**
 * Agentic RAG: checks a claim against the sighting bulletins in one blocking call whose tool loop lets the model look
 * up the claim and search the bulletins as often as it needs, then answers a typed [FraudAssessment]. Its chat client
 * is private, so the claimant chat's injection of `ChatClient` by type stays unambiguous.
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

    /**
     * The schema check sits inside the tool loop (its default order), so it sees each round, lets tool-call rounds
     * through and checks only the final reply.
     */
    private val chatClient = builder
        .defaultSystem(systemPrompt, Charsets.UTF_8)
        .defaultTools(readOnlyCoreTools, sightingBulletinSearch)
        .defaultAdvisors(
            StructuredOutputValidationAdvisor.builder()
                .outputType(FraudAssessment::class.java)
                .maxRepeatAttempts(ASSESSMENT_RETRIES)
                .build(),
        )
        .build()

    /**
     * Assesses claim [claimNumber]. A final reply that does not match the assessment's schema is asked for once more;
     * one that is still no fraud assessment is [FraudCheckResult.Unreadable]. A failing model call throws.
     */
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
