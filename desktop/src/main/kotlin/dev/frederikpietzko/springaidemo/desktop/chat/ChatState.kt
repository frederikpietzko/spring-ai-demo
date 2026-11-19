package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.Persona

/**
 * One message of the conversation; an assistant message may carry an [error] under its (partial) text, a [claimCard]
 * to confirm and an [analyticsTable] to read.
 */
data class ChatMessage(
    val author: Author,
    val text: String,
    val error: String? = null,
    val claimCard: ChatEvent.ClaimCard? = null,
    val analyticsTable: ChatEvent.AnalyticsTable? = null,
) {
    /** Who wrote the message. */
    enum class Author { USER, ASSISTANT }
}

/**
 * What the chat screen shows: the conversation and its id (sent with every request of it), whether an answer is
 * streaming, the selected persona, the tool call that waits for the user's approve or deny (if any) and the stored
 * conversations, newest first.
 */
data class ChatState(
    val conversationId: String,
    val messages: List<ChatMessage> = [],
    val sending: Boolean = false,
    val persona: Persona = Persona.CLAIMANT,
    val pendingConfirmation: ChatEvent.ConfirmationRequest? = null,
    val conversations: List<ConversationSummary> = [],
)
