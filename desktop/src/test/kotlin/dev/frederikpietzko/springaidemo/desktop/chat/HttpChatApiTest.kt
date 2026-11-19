package dev.frederikpietzko.springaidemo.desktop.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** The real Ktor client against a JDK HTTP server on a free port: Ktor's MockEngine does not support SSE. */
class HttpChatApiTest {
    private val request = ChatRequest(Persona.CLAIMANT, "Hi")

    @Test
    suspend fun `posts the request as JSON and decodes the streamed events`() {
        val seen = CopyOnWriteArrayList<String>()
        val events = withServer({ exchange ->
            seen.add("${exchange.requestMethod} ${exchange.requestURI.path}")
            seen.add(exchange.requestHeaders.getFirst("Content-Type").orEmpty())
            seen.add(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            exchange.respondWithEvents("""{"type":"text-delta","text":"Hel"}""", """{"type":"done"}""")
        }) { baseUrl -> sendThrough(baseUrl) }

        assertEquals([ChatEvent.TextDelta("Hel"), ChatEvent.Done], events)
        assertEquals("POST /chat", seen[0])
        assertEquals("application/json", seen[1].substringBefore(";"))
        assertEquals(ChatJson.encodeToString(ChatRequest.serializer(), request), seen[2])
    }

    @Test
    suspend fun `non-ASCII text in the event data arrives intact`() {
        val events = withServer({ exchange ->
            exchange.respondWithEvents("""{"type":"text-delta","text":"Größe 🦖"}""", """{"type":"done"}""")
        }) { baseUrl -> sendThrough(baseUrl) }

        assertEquals([ChatEvent.TextDelta("Größe 🦖"), ChatEvent.Done], events)
    }

    @Test
    suspend fun `an error status becomes one error event`() {
        val events = withServer({ exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }) { baseUrl -> sendThrough(baseUrl) }

        val error = assertIs<ChatEvent.Error>(events.single())
        assertNotEquals("Backend not reachable", error.message)
    }

    @Test
    suspend fun `an event the desktop cannot decode becomes one error event saying the answer is unreadable`() {
        val events = withServer({ it.respondWithEvents("""{"type":"todo-update"}""") }) { baseUrl ->
            sendThrough(baseUrl)
        }

        assertEquals([ChatEvent.Error("The backend sent an unreadable answer")], events)
    }

    @Test
    suspend fun `an unreachable backend becomes one error event saying so`() {
        val closedPort = withServer({ it.close() }) { baseUrl -> baseUrl }

        val events = sendThrough(closedPort)

        assertEquals([ChatEvent.Error("Backend not reachable")], events)
    }

    @Test
    suspend fun `a fatal JVM error is rethrown instead of becoming an error event`() {
        // Ktor wraps every failure of the call, JVM errors included, in its own exception.
        val failingClient = HttpClient(CIO) {
            install(SSE)
            install(createClientPlugin("fatal") { onRequest { _, _ -> throw StackOverflowError("simulated") } })
        }

        withServer({ it.respondWithEvents("""{"type":"done"}""") }) { baseUrl ->
            assertFailsWith<StackOverflowError> {
                HttpChatApi(baseUrl, failingClient).use { api -> api.send(request).toList() }
            }
        }
    }

    private suspend fun sendThrough(baseUrl: String): List<ChatEvent> =
        HttpChatApi(baseUrl).use { api -> api.send(request).toList() }

    private fun HttpExchange.respondWithEvents(vararg data: String) {
        responseHeaders.add("Content-Type", "text/event-stream")
        sendResponseHeaders(200, 0)
        responseBody.use { body -> data.forEach { body.write("data: $it\n\n".toByteArray(Charsets.UTF_8)) } }
    }

    /** Runs [block] against a JDK HTTP server on a free port that answers `/chat` with [handler]; always stops it. */
    private inline fun <T> withServer(noinline handler: (HttpExchange) -> Unit, block: (baseUrl: String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/chat", handler)
        server.start()
        try {
            return block("http://localhost:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
