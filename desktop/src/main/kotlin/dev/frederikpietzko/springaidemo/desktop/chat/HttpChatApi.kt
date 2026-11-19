package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.chatEventFromJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException

private const val NOT_REACHABLE = "Backend not reachable"
private const val ERROR_ANSWER = "The backend answered with an error"
private const val UNREADABLE_ANSWER = "The backend sent an unreadable answer"

/**
 * The chat endpoint over HTTP: posts the request and reads the server-sent events into a [Flow] with Ktor's `SSE`
 * plugin (CIO engine). Every failure that is an exception becomes one [ChatEvent.Error], so the screen only ever sees
 * events and the application runs without a backend; cancellation and JVM errors (`OutOfMemoryError`, ...) are
 * rethrown. Closing it closes the [client].
 */
class HttpChatApi(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient(CIO) { install(SSE) },
) : ChatApi, AutoCloseable {
    override fun send(request: ChatRequest): Flow<ChatEvent> =
        flow {
            client.sse(
                "$baseUrl/chat",
                request = {
                    method = HttpMethod.Post
                    contentType(ContentType.Application.Json)
                    setBody(ChatJson.encodeToString(ChatRequest.serializer(), request))
                },
            ) {
                incoming.collect { event -> event.data?.let { emit(chatEventFromJson(it)) } }
            }
        }.catch { cause ->
            cause.jvmError()?.let { throw it }
            if (cause is CancellationException) throw cause
            emit(ChatEvent.Error(describe(cause)))
        }

    override fun close() {
        client.close()
    }

    /** A JVM error, anything that is not an [Exception], in the cause chain: Ktor wraps those too. */
    private fun Throwable.jvmError(): Throwable? = generateSequence(this) { it.cause }.firstOrNull { it !is Exception }

    /**
     * Ktor wraps every failure in an [SSEClientException]: without a response the backend was not reachable, with one
     * it answered with a wrong status or content type. A decoding failure inside the stream is wrapped too.
     */
    private fun describe(cause: Throwable): String = when {
        generateSequence(cause) { it.cause }.any { it is SerializationException } -> UNREADABLE_ANSWER
        cause is SSEClientException && cause.response == null -> NOT_REACHABLE
        else -> ERROR_ANSWER
    }
}
