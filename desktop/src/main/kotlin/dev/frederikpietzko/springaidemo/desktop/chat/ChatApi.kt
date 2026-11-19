package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import kotlinx.coroutines.flow.Flow

/**
 * The backend's chat and conversation endpoints as seen by the desktop: a request or a decision in, a stream of chat
 * events out (failures arrive as a [ChatEvent.Error], never as exceptions), and the stored conversations.
 */
interface ChatApi {
    /** Sends [request] and emits the reply's events. */
    fun send(request: ChatRequest): Flow<ChatEvent>

    /** Sends the [decision] on a pending confirmation and emits the events of the continued reply. */
    fun decide(decision: ConfirmationDecision): Flow<ChatEvent>

    /** The stored conversations, newest first; empty when the backend cannot be read. */
    suspend fun conversations(): List<ConversationSummary>

    /** The transcript of conversation [id]; empty when the backend cannot be read. */
    suspend fun messages(id: String): List<ConversationMessage>
}
