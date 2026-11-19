package dev.frederikpietzko.springaidemo.backend.fraud

import dev.frederikpietzko.springaidemo.backend.springai.entity
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException

private val log = LoggerFactory.getLogger(FraudCheck::class.java)

/**
 * Who calls the core tools: the same fixed identity as the adjuster chat's, declared here because `fraud` must not
 * depend on `adjuster` (which depends on `fraud`). It travels in the tool context only, never in a message.
 */
private val ADJUSTER_IDENTITY: Map<String, Any> = mapOf("actor" to "adjuster-agent")

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
    @Qualifier("mcpToolCallbacks") coreTools: ToolCallbackProvider,
    sightingBulletinSearch: SightingBulletinSearch,
) {
    /** Filtered on every request, so the MCP client still connects lazily and `issuePayout` is never offered. */
    private val readOnlyCoreTools = ToolCallbackProvider {
        coreTools.toolCallbacks.filter { it.toolDefinition.name() == READ_ONLY_CORE_TOOL }.toTypedArray()
    }

    private val chatClient = builder
        .defaultSystem(systemPrompt, Charsets.UTF_8)
        .defaultTools(readOnlyCoreTools, sightingBulletinSearch)
        .build()

    /**
     * Assesses claim [claimNumber]. A reply that is no fraud assessment is [FraudCheckResult.Unreadable]; a failing
     * model call throws.
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
