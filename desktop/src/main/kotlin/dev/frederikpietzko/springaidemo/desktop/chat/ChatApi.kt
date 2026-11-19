package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import kotlinx.coroutines.flow.Flow

/** The backend's chat endpoint as seen by the desktop: one request in, a stream of chat events out. */
interface ChatApi {
    /** Sends [request] and emits the reply's events; failures arrive as a [ChatEvent.Error], never as exceptions. */
    fun send(request: ChatRequest): Flow<ChatEvent>
}
