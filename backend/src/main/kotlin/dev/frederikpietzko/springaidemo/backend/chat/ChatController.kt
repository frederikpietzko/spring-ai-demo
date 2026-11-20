package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.adjuster.AdjusterChat
import dev.frederikpietzko.springaidemo.backend.intake.ClaimIntake
import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.chatprotocol.isTerminal
import dev.frederikpietzko.springaidemo.chatprotocol.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private val log = LoggerFactory.getLogger(ChatController::class.java)

/** The error text of a failed reply; the cause is logged, never sent. */
private const val GENERIC_ERROR = "The claims desk is unavailable."

/**
 * The chat endpoints: one chat request, or one decision on a paused adjuster reply, in; the reply as server-sent
 * events out, one JSON [ChatEvent] per event. The persona picks the agent: the claimant agent or the adjuster agent.
 * Every stream ends with exactly one terminal event: [ChatEvent.Done] on success, [ChatEvent.Error] on failure, or the
 * agent's own [ChatEvent.ConfirmationRequest] (or error event) when the reply waits for a human decision (or nothing
 * waits for the decision). A JVM `Error` is not a failed reply: it is rethrown and breaks the stream.
 */
@RestController
class ChatController(
    private val claimantChat: ClaimantChat,
    private val adjusterChat: AdjusterChat,
    private val conversations: Conversations,
    private val claimIntake: ClaimIntake,
) {
    /**
     * Streams the answer to [request]; the persona picks the agent. A request without a conversation id, or with a
     * blank one, gets a fresh conversation of its own. For the claimant, the action decides who answers: a message is
     * answered by the claimant agent, the claim actions by the [ClaimIntake], whatever the request's message says. The
     * adjuster agent answers messages only; a claim action for the adjuster gets one error event and no model call.
     */
    @PostMapping("/chat", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@RequestBody request: ChatRequest): Flow<ServerSentEvent<String>> {
        val conversationId = request.conversationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        return when (request.persona) {
            Persona.CLAIMANT -> when (request.action) {
                ChatAction.MESSAGE -> claimantReply(conversationId, request.message)
                ChatAction.PREPARE_CLAIM -> prepareClaim(conversationId)
                ChatAction.CONFIRM_CLAIM -> confirmClaim(conversationId)
            }
            Persona.ADJUSTER ->
                if (request.action == ChatAction.MESSAGE) {
                    adjusterChat.stream(conversationId, request.message)
                } else {
                    flowOf(ChatEvent.Error("Persona ADJUSTER does not support the claim actions"))
                }
        }
            .asServerSentEvents()
    }

    /** Carries out the adjuster's [decision] on a paused reply and streams the continued reply. */
    @PostMapping(
        "/chat/decisions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun decide(@RequestBody decision: ConfirmationDecision): Flow<ServerSentEvent<String>> =
        adjusterChat.decide(decision).asServerSentEvents()

    private fun Flow<ChatEvent>.asServerSentEvents(): Flow<ServerSentEvent<String>> =
        withTerminalEvent()
            // Pre-encoded JSON: a non-String payload would be written by the subclass serializer, without `type`.
            .map { ServerSentEvent.builder(it.toJson()).build() }

    private fun claimantReply(conversationId: String, message: String): Flow<ChatEvent> =
        claimantChat.stream(conversationId, message)
            .map<String, ChatEvent> { ChatEvent.TextDelta(it) }

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

    /** Files the conversation's prepared claim (never one sent by the client) and answers with its claim number. */
    private fun confirmClaim(conversationId: String): Flow<ChatEvent> =
        flow { emit(claimIntake.confirm(conversationId).toEvent()) }

    /**
     * Done only for a completed reply that did not end itself: after any event that is not terminal (text, a claim
     * card, an analytics table, a todo update) or after nothing, never after a confirmation request or an error event
     * of the agent. A failed or cancelled stream throws out of `collect`, so it gets no Done; the `catch` below maps
     * the failure.
     */
    private fun Flow<ChatEvent>.withTerminalEvent(): Flow<ChatEvent> =
        flow {
            var last: ChatEvent? = null
            collect { event ->
                last = event
                emit(event)
            }
            if (last?.isTerminal != true) emit(ChatEvent.Done)
        }

            .catch { cause ->
                // Only exceptions are a failed reply; an Error is rethrown (`catch` rethrows cancellation itself).
                // It is searched in the cause chain: advisors built on BaseAdvisor (the policy wording retrieval)
                // wrap every stream failure in an IllegalStateException.
                generateSequence(cause) { it.cause }.firstOrNull { it !is Exception }?.let { throw it }
                log.warn("Chat stream failed", cause)
                emit(ChatEvent.Error(GENERIC_ERROR))
            }
}
