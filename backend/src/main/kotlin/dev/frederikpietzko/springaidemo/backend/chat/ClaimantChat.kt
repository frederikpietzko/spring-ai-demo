package dev.frederikpietzko.springaidemo.backend.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.ai.session.SessionAttributes
import org.springframework.ai.session.advisor.SessionAdvisors
import org.springframework.ai.session.advisor.SessionMemoryAdvisor
import org.springframework.stereotype.Service

/** The claimant agent: answers one claimant message of a conversation as a stream of text chunks. */
@Service
class ClaimantChat(@Qualifier("claimantChatClient") private val claimantChatClient: ChatClient) {
    /**
     * Streams the model's reply to [message] in the conversation [conversationId]; Spring AI streams a `Flux`, bridged
     * here to a Kotlin `Flow`. The session advisors add the earlier turns and store this one. The typed text also
     * travels as metadata, because the retrieval advisor rewrites the user message before it is stored.
     */
    fun stream(conversationId: String, message: String): Flow<String> =
        claimantChatClient.prompt()
            .user { it.text(message).metadata(CLAIMANT_TEXT, message) }
            .advisors(SessionAdvisors.session(conversationId))
            // Applied only when the session is created: later turns do not change the title.
            .advisors {
                it.param(
                    SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
                    mapOf(SessionAttributes.AGENT_NAME to CLAIMANT_AGENT, TITLE to message.take(TITLE_LENGTH)),
                )
            }
            .toolContext(mapOf(CONVERSATION_ID to conversationId))
            .stream().content().asFlow()
}
