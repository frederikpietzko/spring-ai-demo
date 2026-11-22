package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springai.kotlin.contentFlow
import dev.frederikpietzko.springai.kotlin.prompt
import kotlinx.coroutines.flow.Flow
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.session.SessionAttributes
import org.springframework.ai.session.advisor.SessionMemoryAdvisor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/** The claimant agent: answers one claimant message of a conversation as a stream of text chunks. */
@Service
class ClaimantChat(@Qualifier("claimantChatClient") private val claimantChatClient: ChatClient) {
    /**
     * Streams the reply to [message]: Spring AI's `Flux` as a Kotlin `Flow`. The session advisors add the earlier
     * turns and store this one; the typed text also travels as metadata, because retrieval rewrites the user message.
     */
    fun stream(conversationId: String, message: String): Flow<String> =
        claimantChatClient.prompt {
            user {
                text(message)
                metadata(CLAIMANT_TEXT, message)
            }
            advisors {
                session(conversationId)
                // Applied only when the session is created: later turns do not change the title.
                param(
                    SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
                    mapOf(SessionAttributes.AGENT_NAME to CLAIMANT_AGENT, TITLE to message.take(TITLE_LENGTH)),
                )
            }
            toolContext(mapOf(CONVERSATION_ID to conversationId))
        }.stream().contentFlow()
}
