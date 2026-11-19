package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Read access to the stored conversations: the list and the transcripts for the desktop's conversation column. */
@RestController
class ConversationController(private val conversations: Conversations) {
    /** The claimant conversations, newest first. */
    @GetMapping("/conversations")
    fun list(): List<ConversationSummary> = conversations.list()

    /** The transcript of conversation [id]; 404 for an unknown id. */
    @GetMapping("/conversations/{id}/messages")
    fun messages(@PathVariable id: String): List<ConversationMessage> =
        conversations.transcript(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
}
