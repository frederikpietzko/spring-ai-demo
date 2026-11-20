package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.chatprotocol.isTerminal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Shown when the stream ends without a terminal event, which the protocol only allows for a dropped connection. */
private const val CONNECTION_CLOSED = "The connection closed before the answer was complete."

/** What separates the paused text of a reply from its continuation after a decision. */
private const val PARAGRAPH = "\n\n"

/**
 * The chat screen's state and actions, independent of Compose so it can be tested with a fake [ChatApi]. Every
 * conversation gets its own id from [newId].
 */
class ChatViewModel(private val api: ChatApi, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val mutableState = MutableStateFlow(ChatState(conversationId = newId()))

    /** The current screen state. */
    val state: StateFlow<ChatState> = mutableState.asStateFlow()

    /**
     * Sends [text] and streams the reply into the conversation until a terminal event arrives. A confirmation request
     * is kept as pending; an error event, or a stream that ends without a terminal event, is shown under the (partial)
     * reply; `sending` is reset in any case. Then the conversation list is refreshed, after the input is usable again
     * (not when cancelled). A blank [text], or a call while an answer is still streaming or a confirmation is pending,
     * is ignored.
     */
    suspend fun send(text: String) {
        if (text.isBlank()) return
        exchange(userLine = text, message = text, action = ChatAction.MESSAGE)
    }

    /** Replaces the text in the message input. */
    fun setInput(text: String) {
        mutableState.update { it.copy(input = text) }
    }

    /**
     * Sends the text in the message input like [send] and empties the input; what Send and Enter do. A blank input, or
     * a call while Send is not usable ([ChatState.canSend]), is ignored and keeps the input.
     */
    suspend fun submitInput() {
        val text = state.value.input
        if (text.isBlank() || !state.value.canSend) return
        mutableState.update { it.copy(input = "") }
        send(text)
    }

    /**
     * Asks the backend to turn the conversation into a claim card, shown as the user line "Prepare my claim"; the
     * answer is streamed like a reply to [send]. Ignored while an answer is streaming or a confirmation is pending.
     */
    suspend fun prepareClaim() {
        exchange(userLine = "Prepare my claim", message = "", action = ChatAction.PREPARE_CLAIM)
    }

    /**
     * Asks the backend to file the conversation's prepared claim, shown as the user line "Confirm claim"; the answer is
     * streamed like a reply to [send]. Ignored while an answer is streaming or a confirmation is pending.
     */
    suspend fun confirmClaim() {
        exchange(userLine = "Confirm claim", message = "", action = ChatAction.CONFIRM_CLAIM)
    }

    /** Adds [userLine], sends [message] with [action] in the current conversation and streams the answer. */
    private suspend fun exchange(userLine: String, message: String, action: ChatAction) {
        if (state.value.sending || state.value.pendingConfirmation != null) return
        val question: List<ChatMessage> =
            [ChatMessage(ChatMessage.Author.USER, userLine), ChatMessage(ChatMessage.Author.ASSISTANT, "")]
        mutableState.update { it.copy(messages = it.messages + question, sending = true) }
        val request = ChatRequest(state.value.persona, message, state.value.conversationId, action)
        mutableState.streamReply(api.send(request))
        // After streamReply's finally: a GET against a dead backend can take a while and must not block the input.
        refreshConversations()
    }

    /**
     * Approves or denies the pending confirmation: sends one decision, clears the confirmation and appends the
     * continued reply to the same assistant message, after a paragraph break when the message already has text.
     * Ignored when nothing is pending.
     */
    suspend fun decide(approved: Boolean) {
        val pending = state.value.pendingConfirmation ?: return
        mutableState.update { it.copy(pendingConfirmation = null, sending = true) }
        val decision = ConfirmationDecision(pending.confirmationId, approved)
        mutableState.streamReply(api.decide(decision), continuation = true)
        refreshConversations()
    }

    /**
     * Selects the persona: starts a new conversation (a conversation belongs to one agent), clears the todo list and
     * lists the persona's conversations. Ignored while an answer is streaming or a confirmation is pending.
     */
    suspend fun selectPersona(persona: Persona) {
        if (state.value.sending || state.value.pendingConfirmation != null) return
        mutableState.update { it.copy(persona = persona, conversationId = newId(), messages = [], todos = []) }
        refreshConversations()
    }

    /** Replaces the conversation list with the backend's; an unreachable backend gives an empty list. */
    suspend fun refreshConversations() {
        val conversations = api.conversations(state.value.persona)
        mutableState.update { it.copy(conversations = conversations) }
    }

    /**
     * Shows the transcript of [conversation] and continues it: later messages carry its id. A transcript that cannot
     * be loaded shows no messages. The todo list is cleared: it belonged to the conversation shown before. Ignored
     * while an answer is streaming or a confirmation is pending.
     */
    suspend fun open(conversation: ConversationSummary) {
        if (state.value.sending || state.value.pendingConfirmation != null) return
        val messages = api.messages(conversation.id).map { it.toChatMessage() }
        mutableState.update {
            if (it.sending) {
                it
            } else {
                it.copy(conversationId = conversation.id, messages = messages, todos = [])
            }
        }
    }

    /**
     * Clears the conversation and its todo list and starts a new one with a new id; ignored while an answer is
     * streaming or a confirmation is pending.
     */
    fun newConversation() {
        if (state.value.sending || state.value.pendingConfirmation != null) return
        mutableState.update { it.copy(conversationId = newId(), messages = [], todos = []) }
    }
}

/**
 * Appends [events] to the last (assistant) message until a terminal event arrives. A todo update replaces the todo
 * list; a confirmation request is kept as pending; an error event, or a stream that ends without a terminal event,
 * is shown under the (partial) reply; `sending` is reset in any case. A [continuation] starts its text after a
 * paragraph break. The caller refreshes the conversation list afterwards.
 */
private suspend fun MutableStateFlow<ChatState>.streamReply(events: Flow<ChatEvent>, continuation: Boolean = false) {
    try {
        var terminated = false
        var firstText = continuation
        events
            // The stream ends with exactly one terminal event; stop collecting there.
            .transformWhile { event ->
                emit(event)
                !event.isTerminal
            }
            .collect { event ->
                when (event) {
                    is ChatEvent.TextDelta -> {
                        val paragraph = firstText
                        firstText = false
                        updateReply { it.copy(text = it.text.continuedWith(event.text, paragraph)) }
                    }
                    is ChatEvent.ClaimCard -> updateReply { it.copy(claimCard = event) }
                    is ChatEvent.AnalyticsTable -> updateReply { it.copy(analyticsTable = event) }
                    is ChatEvent.TodoUpdate -> update { it.copy(todos = event.todos) }
                    ChatEvent.Done -> terminated = true
                    is ChatEvent.ConfirmationRequest -> {
                        terminated = true
                        update { it.copy(pendingConfirmation = event) }
                    }
                    is ChatEvent.Error -> {
                        terminated = true
                        updateReply { it.copy(error = event.message) }
                    }
                }
            }
        if (!terminated) updateReply { it.copy(error = CONNECTION_CLOSED) }
    } finally {
        update { it.copy(sending = false) }
    }
}

private fun MutableStateFlow<ChatState>.updateReply(change: (ChatMessage) -> ChatMessage) {
    update { it.copy(messages = it.messages.dropLast(1) + change(it.messages.last())) }
}

private fun ConversationMessage.toChatMessage(): ChatMessage = when (role) {
    MessageRole.USER -> ChatMessage(ChatMessage.Author.USER, text)
    MessageRole.ASSISTANT -> ChatMessage(ChatMessage.Author.ASSISTANT, text)
}

/** Appends [delta]; after a [paragraph] break when there is text already (its trailing spaces dropped). */
private fun String.continuedWith(delta: String, paragraph: Boolean): String =
    if (paragraph && isNotEmpty()) trimEnd() + PARAGRAPH + delta else this + delta
