package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.chatprotocol.toJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

private val log = LoggerFactory.getLogger(ChatController::class.java)

/** The only error text a client sees; the cause is logged, never sent. */
private const val GENERIC_ERROR = "The claims desk is unavailable."

/**
 * The chat endpoint: one chat request in, the reply as server-sent events out, one JSON [ChatEvent] per event.
 * Every stream ends with exactly one terminal event: [ChatEvent.Done] on success, [ChatEvent.Error] on failure. A JVM
 * `Error` is not a failed reply: it is rethrown and breaks the stream.
 */
@RestController
class ChatController(private val claimantChat: ClaimantChat) {
    /** Streams the reply to [request]; the persona picks the agent. */
    @PostMapping("/chat", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@RequestBody request: ChatRequest): Flow<ServerSentEvent<String>> =
        when (request.persona) {
            Persona.CLAIMANT -> claimantReply(request.message)
            Persona.ADJUSTER -> flowOf(ChatEvent.Error("Persona ADJUSTER is not available yet"))
        }
            // Pre-encoded JSON: a non-String payload would be written by the subclass serializer, without `type`.
            .map { ServerSentEvent.builder(it.toJson()).build() }

    private fun claimantReply(message: String): Flow<ChatEvent> =
        claimantChat.stream(message)
            .map<String, ChatEvent> { ChatEvent.TextDelta(it) }
            // Done only for a completed reply (a failed or cancelled stream has a cause). The order matters: after
            // `catch` the flow completes normally, so an `onCompletion` placed there would add Done after the Error.
            .onCompletion { cause -> if (cause == null) emit(ChatEvent.Done) }
            .catch { cause ->
                // Only exceptions are a failed reply; an Error is rethrown (`catch` rethrows cancellation itself).
                if (cause !is Exception) throw cause
                log.warn("Chat stream failed", cause)
                emit(ChatEvent.Error(GENERIC_ERROR))
            }
}
