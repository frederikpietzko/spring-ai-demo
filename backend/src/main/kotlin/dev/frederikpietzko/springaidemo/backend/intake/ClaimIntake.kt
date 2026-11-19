package dev.frederikpietzko.springaidemo.backend.intake

import dev.frederikpietzko.springaidemo.backend.springai.entity
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
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
 * Turns a claimant conversation into a claim report with one blocking structured-output call, keeps a complete one as
 * the conversation's prepared claim and files it on confirmation. Its chat client is private and has no advisors: the
 * session advisors of the claimant chat would store the extraction as a turn, and a second `ChatClient` bean would make
 * the claimant chat's injection by type ambiguous.
 */
@Service
class ClaimIntake(
    builder: ChatClient.Builder,
    @Value("classpath:prompts/claim-intake-system.md") systemPrompt: Resource,
    private val claimFiling: ClaimFiling,
) {
    private val chatClient = builder.defaultSystem(systemPrompt, Charsets.UTF_8).build()

    /** The prepared claim of each conversation: validated, shown as a card, not yet confirmed. Lost on restart. */
    private val preparedClaims = ConcurrentHashMap<String, ValidClaim>()

    /**
     * Extracts a claim report from [history], the conversation [conversationId] as plain user and assistant messages
     * in order, and validates it; a complete report becomes the conversation's prepared claim, replacing an earlier
     * one, which any other outcome drops. A reply that is no claim report is [IntakeResult.Unreadable]; a failing model
     * call throws. Without a user message there is nothing to extract: every detail is missing and the model is not
     * asked.
     */
    fun prepare(conversationId: String, history: List<Message>): IntakeResult {
        // Whatever happens below, the card the claimant saw before is outdated.
        preparedClaims.remove(conversationId)
        val result = extractAndValidate(history)
        if (result is IntakeResult.Ready) preparedClaims[conversationId] = result.claim
        return result
    }

    /**
     * Files the prepared claim of conversation [conversationId] and forgets it. Nothing prepared is rejected; a claim
     * the filing refuses stays prepared, and so does one whose filing fails (the core system is unreachable), which
     * then throws.
     */
    fun confirm(conversationId: String): FilingResult {
        val claim = preparedClaims.remove(conversationId) ?: return FilingResult.Rejected(NO_PREPARED_CLAIM)
        var filed = false
        try {
            return claimFiling.file(claim).also { filed = it is FilingResult.Filed }
        } finally {
            // Refused or failed: the claim stays prepared, unless a prepare that ran meanwhile made a newer card.
            if (!filed) preparedClaims.putIfAbsent(conversationId, claim)
        }
    }

    private fun extractAndValidate(history: List<Message>): IntakeResult {
        if (history.none { it is UserMessage }) return NOTHING_REPORTED.validate()
        val report = try {
            extract(history)
        } catch (e: JacksonException) {
            // Malformed JSON, an unknown enum constant or a value of the wrong type.
            log.warn("Unreadable claim report", e)
            null
        }
        return report?.validate() ?: IntakeResult.Unreadable
    }

    /** The one blocking structured-output call; `null` when the model produced no generation at all. */
    private fun extract(history: List<Message>): ClaimReport? =
        chatClient.prompt()
            .system { it.param("today", LocalDate.now(ZoneOffset.UTC).toString()) }
            .messages(history)
            .user(INSTRUCTION)
            .call()
            .entity<ClaimReport>()
}
