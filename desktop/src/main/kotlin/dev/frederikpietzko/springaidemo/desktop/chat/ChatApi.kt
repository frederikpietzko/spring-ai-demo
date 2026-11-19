package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import kotlinx.coroutines.flow.Flow

/** The backend's chat and conversation endpoints as seen by the desktop. */
interface ChatApi {
    /** Sends [request] and emits the reply's events; failures arrive as a [ChatEvent.Error], never as exceptions. */
    fun send(request: ChatRequest): Flow<ChatEvent>

    /** The stored conversations, newest first; empty when the backend cannot be read. */
    suspend fun conversations(): List<ConversationSummary>

    /** The transcript of conversation [id]; empty when the backend cannot be read. */
    suspend fun messages(id: String): List<ConversationMessage>
}
