package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update

/** Shown when the stream ends without a terminal event, which the protocol only allows for a dropped connection. */
private const val CONNECTION_CLOSED = "The connection closed before the answer was complete."

/** The chat screen's state and actions, independent of Compose so it can be tested with a fake [ChatApi]. */
class ChatViewModel(private val api: ChatApi) {
    private val mutableState = MutableStateFlow(ChatState())

    /** The current screen state. */
    val state: StateFlow<ChatState> = mutableState.asStateFlow()

    /**
     * Sends [text] and streams the reply into the conversation until a terminal event arrives. A blank [text], or a
     * call while an answer is still streaming or a confirmation is pending, is ignored.
     */
    suspend fun send(text: String) {
        if (text.isBlank() || state.value.sending || state.value.pendingConfirmation != null) return
        val question: List<ChatMessage> =
            [ChatMessage(ChatMessage.Author.USER, text), ChatMessage(ChatMessage.Author.ASSISTANT, "")]
        mutableState.update { it.copy(messages = it.messages + question, sending = true) }
        streamReply(api.send(ChatRequest(state.value.persona, text)))
    }

    /**
     * Approves or denies the pending confirmation: sends one decision, clears the confirmation and appends the
     * continued reply to the same assistant message. Ignored when nothing is pending.
     */
    suspend fun decide(approved: Boolean) {
        val pending = state.value.pendingConfirmation ?: return
        mutableState.update { it.copy(pendingConfirmation = null, sending = true) }
        streamReply(api.decide(ConfirmationDecision(pending.confirmationId, approved)))
    }

    /** Selects the persona for the next message. */
    fun selectPersona(persona: Persona) {
        mutableState.update { it.copy(persona = persona) }
    }

    /**
     * Appends [events] to the last (assistant) message until a terminal event arrives. A confirmation request is kept
     * as pending; an error event, or a stream that ends without a terminal event, is shown under the (partial) reply;
     * `sending` is reset in any case.
     */
    private suspend fun streamReply(events: Flow<ChatEvent>) {
        try {
            var terminated = false
            events
                // The stream ends with exactly one terminal event; stop collecting there.
                .transformWhile { event ->
                    emit(event)
                    event is ChatEvent.TextDelta
                }
                .collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> updateReply { it.copy(text = it.text + event.text) }
                        ChatEvent.Done -> terminated = true
                        is ChatEvent.ConfirmationRequest -> {
                            terminated = true
                            mutableState.update { it.copy(pendingConfirmation = event) }
                        }
                        is ChatEvent.Error -> {
                            terminated = true
                            updateReply { it.copy(error = event.message) }
                        }
                    }
                }
            if (!terminated) updateReply { it.copy(error = CONNECTION_CLOSED) }
        } finally {
            mutableState.update { it.copy(sending = false) }
        }
    }

    private fun updateReply(change: (ChatMessage) -> ChatMessage) {
        mutableState.update { it.copy(messages = it.messages.dropLast(1) + change(it.messages.last())) }
    }
}
