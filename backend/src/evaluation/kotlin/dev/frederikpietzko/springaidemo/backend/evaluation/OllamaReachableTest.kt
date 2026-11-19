package dev.frederikpietzko.springaidemo.backend.evaluation

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Ollama skip condition, against a JDK HTTP server instead of Ollama. Plain JUnit: always runs, needs no key. */
class OllamaReachableTest {
    @Test
    fun `enables the evaluation when Ollama answers`() {
        val ollama = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        ollama.createContext("/api/tags") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        ollama.start()
        try {
            val result = OllamaReachable().reachability("http://localhost:${ollama.address.port}")

            assertFalse(result.isDisabled, "expected enabled, got: ${result.reason.orElse("")}")
        } finally {
            ollama.stop(0)
        }
    }

    @Test
    fun `disables the evaluation and names the URL when nothing listens`() {
        val url = "http://localhost:1"

        val result = OllamaReachable().reachability(url)

        val reason = result.reason.orElse(null).orEmpty()
        assertTrue(result.isDisabled, "expected disabled")
        assertTrue(reason.contains(url), "reason should name $url: $reason")
    }
}
