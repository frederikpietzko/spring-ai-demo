package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.Serializable

/**
 * One entry of the conversation list (`GET /conversations`), for example
 * `{"id":"c-1","title":"Kaiju stepped on my shed","startedAt":"2026-11-20T10:00:00Z"}`. The [title] is the beginning
 * of the claimant's first message, [startedAt] an ISO-8601 instant.
 */
@Serializable
data class ConversationSummary(val id: String, val title: String, val startedAt: String)

/** Who wrote a [ConversationMessage]. */
@Serializable
enum class MessageRole { USER, ASSISTANT }

/**
 * One message of a conversation transcript (`GET /conversations/{id}/messages`), for example
 * `{"role":"USER","text":"my garden gnome is gone"}`.
 */
@Serializable
data class ConversationMessage(val role: MessageRole, val text: String)
