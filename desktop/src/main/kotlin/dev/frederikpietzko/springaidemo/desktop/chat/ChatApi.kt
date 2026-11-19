package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import kotlinx.coroutines.flow.Flow

/**
 * The backend's chat endpoints as seen by the desktop: a request or a decision in, a stream of chat events out.
 * Failures arrive as a [ChatEvent.Error], never as exceptions.
 */
interface ChatApi {
    /** Sends [request] and emits the reply's events. */
    fun send(request: ChatRequest): Flow<ChatEvent>

    /** Sends the [decision] on a pending confirmation and emits the events of the continued reply. */
    fun decide(decision: ConfirmationDecision): Flow<ChatEvent>
}
