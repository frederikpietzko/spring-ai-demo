package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.intake.ClaimIntake
import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.chatprotocol.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private val log = LoggerFactory.getLogger(ChatController::class.java)

/** The only error text a client sees; the cause is logged, never sent. */
private const val GENERIC_ERROR = "The claims desk is unavailable."

/**
 * The chat endpoint: one chat request in, the reply as server-sent events out, one JSON [ChatEvent] per event.
 * Every stream ends with exactly one terminal event: [ChatEvent.Done] on success, [ChatEvent.Error] on failure. A JVM
 * `Error` is not a failed reply: it is rethrown and breaks the stream.
 */
@RestController
class ChatController(
    private val claimantChat: ClaimantChat,
    private val conversations: Conversations,
    private val claimIntake: ClaimIntake,
) {
    /**
     * Streams the answer to [request]; the persona picks the agent. A request without a conversation id, or with a
     * blank one, gets a fresh conversation of its own. For the claimant, the action decides who answers: a message is
     * answered by the claimant agent, the claim actions by the [ClaimIntake], whatever the request's message says.
     */
    @PostMapping("/chat", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@RequestBody request: ChatRequest): Flow<ServerSentEvent<String>> =
        when (request.persona) {
            Persona.CLAIMANT -> {
                val conversationId =
                    request.conversationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                when (request.action) {
                    ChatAction.MESSAGE -> claimantReply(conversationId, request.message)
                    ChatAction.PREPARE_CLAIM -> prepareClaim(conversationId)
                    ChatAction.CONFIRM_CLAIM -> confirmClaim(conversationId)
                }
            }
            Persona.ADJUSTER -> flowOf(ChatEvent.Error("Persona ADJUSTER is not available yet"))
        }
            // Pre-encoded JSON: a non-String payload would be written by the subclass serializer, without `type`.
            .map { ServerSentEvent.builder(it.toJson()).build() }

    private fun claimantReply(conversationId: String, message: String): Flow<ChatEvent> =
        claimantChat.stream(conversationId, message)
            .map<String, ChatEvent> { ChatEvent.TextDelta(it) }
            .terminated()

    /**
     * Turns the stored conversation into a claim card or a follow-up question. The extraction is a blocking model
     * call, so it runs on the IO dispatcher: Spring MVC collects the flow on the request (or a Reactor) thread.
     */
    private fun prepareClaim(conversationId: String): Flow<ChatEvent> =
        flow {
            val history = conversations.transcript(conversationId).orEmpty().map { it.toMessage() }
            emit(claimIntake.prepare(conversationId, history).toEvent())
        }
            .flowOn(Dispatchers.IO)
            .terminated()

    /** Files the conversation's prepared claim (never one sent by the client) and answers with its claim number. */
    private fun confirmClaim(conversationId: String): Flow<ChatEvent> =
        flow { emit(claimIntake.confirm(conversationId).toEvent()) }.terminated()

    /**
     * Ends this reply with exactly one terminal event: [ChatEvent.Done] when it completed, the generic
     * [ChatEvent.Error] when it failed with an exception. A JVM `Error` is rethrown.
     */
    private fun Flow<ChatEvent>.terminated(): Flow<ChatEvent> =
        // Done only for a completed reply (a failed or cancelled stream has a cause). The order matters: after
        // `catch` the flow completes normally, so an `onCompletion` placed there would add Done after the Error.
        onCompletion { cause -> if (cause == null) emit(ChatEvent.Done) }
            .catch { cause ->
                // Only exceptions are a failed reply; an Error is rethrown (`catch` rethrows cancellation itself).
                // It is searched in the cause chain: advisors built on BaseAdvisor (the policy wording retrieval)
                // wrap every stream failure in an IllegalStateException.
                generateSequence(cause) { it.cause }.firstOrNull { it !is Exception }?.let { throw it }
                log.warn("Chat stream failed", cause)
                emit(ChatEvent.Error(GENERIC_ERROR))
            }
}
