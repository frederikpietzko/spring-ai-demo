package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.chatprotocol.isTerminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Shown when the stream ends without a terminal event, which the protocol only allows for a dropped connection. */
private const val CONNECTION_CLOSED = "The connection closed before the answer was complete."

/**
 * The chat screen's state and actions, independent of Compose so it can be tested with a fake [ChatApi]. Every
 * conversation gets its own id from [newId].
 */
class ChatViewModel(private val api: ChatApi, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val mutableState = MutableStateFlow(ChatState(conversationId = newId()))

    /** The current screen state. */
    val state: StateFlow<ChatState> = mutableState.asStateFlow()

    /**
     * Sends [text] and streams the reply into the conversation until a terminal event arrives. An error event, or a
     * stream that ends without a terminal event, is shown under the (partial) reply; `sending` is reset in any case.
     * Then the conversation list is refreshed, after the input is usable again (not when cancelled). A blank [text],
     * or a call while an answer is still streaming, is ignored.
     */
    suspend fun send(text: String) {
        if (text.isBlank()) return
        exchange(userLine = text, message = text, action = ChatAction.MESSAGE)
    }

    /**
     * Asks the backend to turn the conversation into a claim card, shown as the user line "Prepare my claim"; the
     * answer is streamed like a reply to [send]. Ignored while an answer is streaming.
     */
    suspend fun prepareClaim() {
        exchange(userLine = "Prepare my claim", message = "", action = ChatAction.PREPARE_CLAIM)
    }

    /**
     * Asks the backend to file the conversation's prepared claim, shown as the user line "Confirm claim"; the answer is
     * streamed like a reply to [send]. Ignored while an answer is streaming.
     */
    suspend fun confirmClaim() {
        exchange(userLine = "Confirm claim", message = "", action = ChatAction.CONFIRM_CLAIM)
    }

    /** Adds [userLine], sends [message] with [action] in the current conversation and streams the answer. */
    private suspend fun exchange(userLine: String, message: String, action: ChatAction) {
        if (state.value.sending) return
        val question: List<ChatMessage> =
            [ChatMessage(ChatMessage.Author.USER, userLine), ChatMessage(ChatMessage.Author.ASSISTANT, "")]
        mutableState.update { it.copy(messages = it.messages + question, sending = true) }
        try {
            var terminated = false
            api.send(ChatRequest(state.value.persona, message, state.value.conversationId, action))
                // The stream ends with exactly one terminal event; stop collecting there.
                .transformWhile { event ->
                    emit(event)
                    !event.isTerminal
                }
                .collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> updateReply { it.copy(text = it.text + event.text) }
                        is ChatEvent.ClaimCard -> updateReply { it.copy(claimCard = event) }
                        ChatEvent.Done -> terminated = true
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
        // Outside the finally: a GET against a dead backend can take a while and must not block the input.
        refreshConversations()
    }

    /** Replaces the conversation list with the backend's; an unreachable backend gives an empty list. */
    suspend fun refreshConversations() {
        val conversations = api.conversations()
        mutableState.update { it.copy(conversations = conversations) }
    }

    /**
     * Shows the transcript of [conversation] and continues it: later messages carry its id. A transcript that cannot
     * be loaded shows no messages. Ignored while an answer is streaming.
     */
    suspend fun open(conversation: ConversationSummary) {
        if (state.value.sending) return
        val messages = api.messages(conversation.id).map { it.toChatMessage() }
        mutableState.update {
            if (it.sending) it else it.copy(conversationId = conversation.id, messages = messages)
        }
    }

    /** Clears the conversation and starts a new one with a new id; ignored while an answer is streaming. */
    fun newConversation() {
        if (state.value.sending) return
        mutableState.update { it.copy(conversationId = newId(), messages = []) }
    }

    /** Selects the persona for the next message. */
    fun selectPersona(persona: Persona) {
        mutableState.update { it.copy(persona = persona) }
    }

    private fun ConversationMessage.toChatMessage(): ChatMessage = when (role) {
        MessageRole.USER -> ChatMessage(ChatMessage.Author.USER, text)
        MessageRole.ASSISTANT -> ChatMessage(ChatMessage.Author.ASSISTANT, text)
    }

    private fun updateReply(change: (ChatMessage) -> ChatMessage) {
        mutableState.update { it.copy(messages = it.messages.dropLast(1) + change(it.messages.last())) }
    }
}
