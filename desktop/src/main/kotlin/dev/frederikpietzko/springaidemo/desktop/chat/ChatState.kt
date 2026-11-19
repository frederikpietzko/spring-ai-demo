package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.Persona

/**
 * One message of the conversation; an assistant message may carry an [error] under its (partial) text and a
 * [claimCard] to confirm.
 */
data class ChatMessage(
    val author: Author,
    val text: String,
    val error: String? = null,
    val claimCard: ChatEvent.ClaimCard? = null,
) {
    /** Who wrote the message. */
    enum class Author { USER, ASSISTANT }
}

/**
 * What the chat screen shows: the conversation and its id (sent with every request of it), whether an answer is
 * streaming, the selected persona and the stored conversations, newest first.
 */
data class ChatState(
    val conversationId: String,
    val messages: List<ChatMessage> = [],
    val sending: Boolean = false,
    val persona: Persona = Persona.CLAIMANT,
    val conversations: List<ConversationSummary> = [],
)
