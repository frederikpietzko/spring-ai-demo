package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.intake.FilingResult
import dev.frederikpietzko.springaidemo.backend.intake.IntakeResult
import dev.frederikpietzko.springaidemo.backend.intake.ValidClaim
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ClaimCardItem
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import java.time.format.DateTimeFormatter

/** Asked when the model's reply could not be read as a claim report. */
private const val DESCRIBE_AGAIN = "I could not read a claim from our conversation. Please describe what happened, " +
    "what was damaged and your policy number, then press Prepare claim again."

/** A transcript entry as a plain text message of the extraction prompt: it can carry no tool call. */
fun ConversationMessage.toMessage(): Message = when (role) {
    MessageRole.USER -> UserMessage(text)
    MessageRole.ASSISTANT -> AssistantMessage(text)
}

/** The answer to a prepare request: the claim card, a follow-up naming the missing details, or a request to retell. */
fun IntakeResult.toEvent(): ChatEvent = when (this) {
    is IntakeResult.Ready -> claim.toCard()
    is IntakeResult.NeedsDetails -> ChatEvent.TextDelta(
        "To prepare your claim I still need: ${details.joinToString(", ")}. " +
            "Please tell me and press Prepare claim again.",
    )
    IntakeResult.Unreadable -> ChatEvent.TextDelta(DESCRIBE_AGAIN)
}

/** The answer to a confirm request: the new claim number, or why the claim was not filed. */
fun FilingResult.toEvent(): ChatEvent = when (this) {
    is FilingResult.Filed -> ChatEvent.TextDelta("Your claim has been filed. Your claim number is $claimNumber.")
    is FilingResult.Rejected -> ChatEvent.TextDelta("Your claim was not filed: $reason.")
}

/** The card the desktop shows; the time keeps zero seconds (`15:00:00Z`; `OffsetDateTime.toString()` drops them). */
private fun ValidClaim.toCard() = ChatEvent.ClaimCard(
    policyNumber = policyNumber,
    district = district.name,
    incidentAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(incidentAt),
    kaijuClass = kaijuClass,
    description = description,
    damageItems = damageItems.map { ClaimCardItem(it.category.name, it.description, it.estimatedAmountMinor) },
)
