package dev.frederikpietzko.springaidemo.controlprotocol

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.chatprotocol.TodoItem
import kotlinx.serialization.Serializable

/**
 * What the chat screen shows, as the `state` command answers it in JSON: the persona, the conversation id, whether an
 * answer streams, the text in the input, the control being highlighted (its label, or `null`), the messages,
 * the pending confirmation, the stored conversations, the todo items and which controls are [enabled]. Cards,
 * confirmation, todos and conversations are the `chat-protocol` types.
 */
@Serializable
data class StateSnapshot(
    val persona: Persona,
    val conversationId: String,
    val sending: Boolean,
    val input: String,
    val pressed: String?,
    val messages: List<MessageSnapshot>,
    val pendingConfirmation: ChatEvent.ConfirmationRequest?,
    val conversations: List<ConversationSummary>,
    val todos: List<TodoItem>,
    val enabled: EnabledControls,
)

/** One message of a [StateSnapshot]: `USER` or `ASSISTANT`, its text and, if any, its error, claim card and table. */
@Serializable
data class MessageSnapshot(
    val author: String,
    val text: String,
    val error: String?,
    val claimCard: ChatEvent.ClaimCard?,
    val analyticsTable: ChatEvent.AnalyticsTable?,
)

/** Which controls are usable, by the same rules that make a command succeed or be refused. */
@Serializable
data class EnabledControls(
    val send: Boolean,
    val prepareClaim: Boolean,
    val confirmClaim: Boolean,
    val approve: Boolean,
    val deny: Boolean,
    val newConversation: Boolean,
    val selectPersona: Boolean,
    val openConversation: Boolean,
)
