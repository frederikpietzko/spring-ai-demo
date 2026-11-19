package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.chatEventFromJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.cancellation.CancellationException

private const val NOT_REACHABLE = "Backend not reachable"
private const val ERROR_ANSWER = "The backend answered with an error"
private const val UNREADABLE_ANSWER = "The backend sent an unreadable answer"

/**
 * The chat endpoint over HTTP: posts the request and reads the server-sent events into a [Flow] with Ktor's `SSE`
 * plugin (CIO engine). Every failure that is an exception becomes one [ChatEvent.Error], so the screen only ever sees
 * events and the application runs without a backend; cancellation and JVM errors (`OutOfMemoryError`, ...) are
 * rethrown. The conversation list and transcripts are plain `GET`s; every failure there gives an empty list. Closing
 * it closes the [client].
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

    override suspend fun conversations(): List<ConversationSummary> =
        getList("$baseUrl/conversations", ListSerializer(ConversationSummary.serializer()))

    override suspend fun messages(id: String): List<ConversationMessage> =
        getList(
            URLBuilder(baseUrl).appendPathSegments("conversations", id, "messages", encodeSlash = true).buildString(),
            ListSerializer(ConversationMessage.serializer()),
        )

    override fun close() {
        client.close()
    }

    /** Reads a JSON list; a failed request, a non-2xx status or an undecodable body give an empty list. */
    private suspend fun <T> getList(url: String, serializer: KSerializer<List<T>>): List<T> =
        runCatching {
            val response = client.get(url)
            check(response.status.isSuccess()) { "status ${response.status}" }
            ChatJson.decodeFromString(serializer, response.bodyAsText())
        }.getOrElse { cause ->
            cause.jvmError()?.let { throw it }
            if (cause is CancellationException) throw cause
            []
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
