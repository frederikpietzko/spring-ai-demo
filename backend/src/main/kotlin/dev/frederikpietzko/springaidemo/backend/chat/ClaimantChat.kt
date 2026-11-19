package dev.frederikpietzko.springaidemo.backend.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

/** The claimant agent: answers one claimant message as a stream of text chunks. */
@Service
class ClaimantChat(private val claimantChatClient: ChatClient) {
    /** Streams the model's reply to [message]; Spring AI streams a `Flux`, bridged here to a Kotlin `Flow`. */
    fun stream(message: String): Flow<String> =
        claimantChatClient.prompt().user(message).stream().content().asFlow()
}
