package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.Persona

/** One message of the conversation; an assistant message may carry an [error] under its (partial) text. */
data class ChatMessage(val author: Author, val text: String, val error: String? = null) {
    /** Who wrote the message. */
    enum class Author { USER, ASSISTANT }
}

/** What the chat screen shows: the conversation, whether an answer is streaming, and the selected persona. */
data class ChatState(
    val messages: List<ChatMessage> = [],
    val sending: Boolean = false,
    val persona: Persona = Persona.CLAIMANT,
)
