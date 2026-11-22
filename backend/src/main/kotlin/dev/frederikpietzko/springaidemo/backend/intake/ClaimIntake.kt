package dev.frederikpietzko.springaidemo.backend.intake

import dev.frederikpietzko.springai.kotlin.build
import dev.frederikpietzko.springai.kotlin.prompt
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.entity
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(ClaimIntake::class.java)

/** The last user message of the extraction prompt; Spring AI appends the JSON format instructions to it. */
private const val INSTRUCTION = "Extract the claim report from the conversation above."

/** A report without a single fact, for a conversation in which the claimant has said nothing yet. */
private val NOTHING_REPORTED = ClaimReport(null, null, null, null, null, null)

/** The refusal of a confirmation without a prepared claim. */
const val NO_PREPARED_CLAIM = "there is no prepared claim to confirm"

/**
 * Structured output: turns a claimant conversation into a typed [ClaimReport] with one blocking call, keeps a complete
 * one as the conversation's prepared claim and files it on confirmation. Its chat client has no advisors, so the
 * extraction is not stored as a turn of the conversation.
 */
@Service
class ClaimIntake(
    builder: ChatClient.Builder,
    @Value("classpath:prompts/claim-intake-system.md") systemPrompt: Resource,
    private val claimFiling: ClaimFiling,
) {
    private val chatClient = builder.build { system(systemPrompt) }

    /** The prepared claim of each conversation: validated, shown as a card, not yet confirmed. Lost on restart. */
    private val preparedClaims = ConcurrentHashMap<String, ValidClaim>()

    /**
     * Extracts and validates a claim report from [history]. A complete report becomes the conversation's prepared
     * claim; any other outcome drops the earlier one. A failing model call throws.
     */
    fun prepare(conversationId: String, history: List<Message>): IntakeResult {
        // Whatever happens below, the card the claimant saw before is outdated.
        preparedClaims.remove(conversationId)
        val result = extractAndValidate(history)
        if (result is IntakeResult.Ready) preparedClaims[conversationId] = result.claim
        return result
    }

    /** Files the conversation's prepared claim and forgets it; a refused or failed filing keeps it prepared. */
    fun confirm(conversationId: String): FilingResult {
        val claim = preparedClaims.remove(conversationId) ?: return FilingResult.Rejected(NO_PREPARED_CLAIM)
        var filed = false
        try {
            return claimFiling.file(claim).also { filed = it is FilingResult.Filed }
        } finally {
            // Unless a prepare that ran meanwhile made a newer card.
            if (!filed) preparedClaims.putIfAbsent(conversationId, claim)
        }
    }

    private fun extractAndValidate(history: List<Message>): IntakeResult {
        // Nothing said yet: every detail is missing and the model is not asked.
        if (history.none { it is UserMessage }) return NOTHING_REPORTED.validate()
        val report = try {
            chatClient.prompt {
                system { param("today", LocalDate.now(ZoneOffset.UTC).toString()) }
                messages(history)
                user(INSTRUCTION)
            }.call().entity<ClaimReport>()
        } catch (e: JacksonException) {
            // Malformed JSON, an unknown enum constant or a value of the wrong type.
            log.warn("Unreadable claim report", e)
            null
        }
        return report?.validate() ?: IntakeResult.Unreadable
    }
}
