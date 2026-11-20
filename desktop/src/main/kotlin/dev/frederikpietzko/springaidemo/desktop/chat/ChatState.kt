package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.chatprotocol.TodoItem

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
 * streaming, the selected persona, the stored conversations (newest first), the tool call that waits for the user's
 * approve or deny, if any, the adjuster's latest todo list (empty when there is none) and the text in the message
 * input ([input]), which the user and the control endpoint type into alike. The `can...` properties are the rules for
 * which controls are usable; the screen and the control endpoint share them.
 */
data class ChatState(
    val conversationId: String,
    val messages: List<ChatMessage> = [],
    val sending: Boolean = false,
    val persona: Persona = Persona.CLAIMANT,
    val conversations: List<ConversationSummary> = [],
    val pendingConfirmation: ChatEvent.ConfirmationRequest? = null,
    val todos: List<TodoItem> = [],
    val input: String = "",
) {
    /** Whether a new exchange can start: nothing streams and no confirmation waits. Send uses it. */
    val canSend: Boolean get() = !sending && pendingConfirmation == null

    /** Whether "Prepare claim" is usable; the same rule as [canSend]. */
    val canPrepareClaim: Boolean get() = canSend

    /** Whether "New conversation" is usable; the same rule as [canSend]. */
    val canStartNewConversation: Boolean get() = canSend

    /** Whether the persona switch is usable; the same rule as [canSend]. */
    val canSelectPersona: Boolean get() = canSend

    /** Whether a stored conversation can be opened; the same rule as [canSend]. */
    val canOpenConversation: Boolean get() = canSend

    /** Whether "Confirm" is usable: only on the newest message, when it holds a claim card, and when [canSend]. */
    val canConfirmClaim: Boolean get() = canSend && messages.lastOrNull()?.claimCard != null

    /** Whether "Approve" and "Deny" are usable: a confirmation is pending and nothing streams. */
    val canDecide: Boolean get() = !sending && pendingConfirmation != null
}
