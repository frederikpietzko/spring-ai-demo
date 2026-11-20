package dev.frederikpietzko.springaidemo.backend.chat

import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

private const val MAX_MATCHES = 5

/**
 * The recall tool `conversation_search`: finds earlier messages of the current conversation, also those compaction
 * removed from the prompt. Spring AI 2.1 ships no such tool, so it is a plain `@Tool` over the transcript.
 */
@Component
class ConversationSearch(private val conversations: Conversations) {
    /** The most recent messages containing [query], oldest first; the conversation comes from the tool context. */
    @Tool(
        name = "conversation_search",
        description = "Searches every earlier message of this conversation, also those no longer in your context, " +
            "for a word or phrase.",
    )
    fun search(@ToolParam(description = "Word or phrase") query: String, toolContext: ToolContext): List<String> {
        val conversationId = toolContext.context[CONVERSATION_ID] as? String ?: return []
        return conversations.transcript(conversationId).orEmpty()
            .filter { it.text.contains(query, ignoreCase = true) }
            .takeLast(MAX_MATCHES)
            .map { "${it.role.name.lowercase()}: ${it.text}" }
    }
}
