package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.session.SessionAttributes
import org.springframework.ai.session.SessionService
import org.springframework.stereotype.Service

/** The `agentName` session attribute of claimant conversations; other agents get their own value and list. */
const val CLAIMANT_AGENT = "claimant"

/**
 * User-message metadata key holding the text the claimant typed. The retrieval advisor rewrites the user message
 * before it is stored, so the typed text travels next to it.
 */
const val CLAIMANT_TEXT = "claimantText"

/** Tool-context key of the conversation a request belongs to. */
const val CONVERSATION_ID = "conversationId"

/** Session-metadata key of a conversation's title. */
const val TITLE = "title"

/** A title is the beginning of the first message, at most this many characters. */
const val TITLE_LENGTH = 60

/** The claimant conversations kept by Spring AI Session Memory, as the desktop reads them. */
@Service
class Conversations(private val sessionService: SessionService) {
    /** The claimant conversations, newest first; other agents' sessions are not included. */
    fun list(): List<ConversationSummary> =
        sessionService.findByMetadata(SessionAttributes.AGENT_NAME, CLAIMANT_AGENT)
            .sortedByDescending { it.createdAt() }
            .map {
                ConversationSummary(
                    id = it.id(),
                    title = it.metadata()[TITLE]?.toString().orEmpty(),
                    startedAt = it.createdAt().toString(),
                )
            }

    /**
     * What the claimant typed and what the assistant answered in conversation [id], in order, also the turns that
     * compaction removed from the prompt; tool calls and tool results are left out. `null` for an unknown id.
     */
    fun transcript(id: String): List<ConversationMessage>? {
        if (sessionService.findById(id) == null) return null
        return sessionService.getMessages(id).mapNotNull { message ->
            when (message.messageType) {
                MessageType.USER -> ConversationMessage(MessageRole.USER, message.typedText())
                // An assistant message that only asks for tools has no text.
                MessageType.ASSISTANT -> message.text?.takeIf { it.isNotBlank() }
                    ?.let { ConversationMessage(MessageRole.ASSISTANT, it) }
                MessageType.SYSTEM, MessageType.TOOL -> null
            }
        }
    }

    /** The stored user message is the retrieval template; the claimant's own words are in its metadata. */
    private fun Message.typedText(): String = metadata[CLAIMANT_TEXT] as? String ?: text.orEmpty()
}
