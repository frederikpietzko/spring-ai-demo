package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture.Companion.PAYOUT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlServerTest {
    private val fixture = ControlFixture()
    private val server = ControlServer(fixture.control, port = 0)
    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun close() {
        server.close()
        client.close()
        fixture.close()
    }

    @Test
    suspend fun `a command line is run and answered with 200 and its body`() {
        val response = post("wait-idle")

        assertEquals(200 to "idle", response.statusCode() to response.body())
    }

    @Test
    suspend fun `state is answered as JSON`() {
        val response = post("state")

        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElse(""))
        assertTrue(response.body().startsWith("{"), response.body())
    }

    @Test
    suspend fun `each outcome has its status code`() {
        val invalid = post("dance").statusCode()
        val refused = post("approve").statusCode()
        assertEquals(200, post("send Hi").statusCode())
        val timedOut = post("wait-idle 50ms").statusCode()
        fixture.events.send(ChatEvent.Error("The claims desk is unavailable."))
        val failed = post("wait-idle")

        assertEquals([400, 409, 408, 422], [invalid, refused, timedOut, failed.statusCode()])
        assertEquals("error: The claims desk is unavailable.", failed.body())
    }

    @Test
    suspend fun `any other method is refused with 405 and runs nothing`() {
        val response = send(HttpRequest.newBuilder(commandUri()).GET().build())

        assertEquals(405, response.statusCode())
        assertEquals([], fixture.api.requests)
    }

    @Test
    suspend fun `another path is refused with 404`() {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/other"))
            .POST(HttpRequest.BodyPublishers.ofString("state"))
            .build()

        assertEquals(404, send(request).statusCode())
    }

    @Test
    suspend fun `a request from a web page is refused with 403 and runs nothing`() {
        fixture.events.send(PAYOUT)
        fixture.viewModel.send("Pay 4711")
        val request = HttpRequest.newBuilder(commandUri())
            .header("Origin", "http://example.test")
            .POST(HttpRequest.BodyPublishers.ofString("approve"))
            .build()

        val response = send(request)

        assertEquals(403, response.statusCode())
        assertEquals([], fixture.api.decisions)
    }

    @Test
    fun `the endpoint listens on the loopback address only`() {
        assertTrue(server.address.isLoopbackAddress, server.address.toString())
    }

    @Test
    suspend fun `state is answered from a second connection while wait-idle waits`() = coroutineScope {
        assertEquals(200, post("send Hi").statusCode())
        val wait = async { post("wait-idle") }

        val state = post("state")

        assertTrue(""""sending":true""" in state.body(), state.body())
        assertTrue(wait.isActive)
        fixture.events.send(ChatEvent.Done)
        assertEquals("idle", wait.await().body())
    }

    @Test
    fun `no port is chosen without DESKTOP_CONTROL_PORT`() {
        assertNull(controlPort { null })
    }

    @Test
    fun `DESKTOP_CONTROL_PORT names the port`() {
        assertEquals(8765, controlPort { name -> if (name == "DESKTOP_CONTROL_PORT") "8765" else null })
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "", "0", "65536", "-1", "80 80"])
    fun `a value that is not a port chooses no port`(value: String) {
        assertNull(controlPort { value })
    }

    private suspend fun post(line: String): HttpResponse<String> =
        send(HttpRequest.newBuilder(commandUri()).POST(HttpRequest.BodyPublishers.ofString(line)).build())

    private suspend fun send(request: HttpRequest): HttpResponse<String> =
        withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }

    private fun commandUri() = URI("http://127.0.0.1:${server.port}/command")
}
