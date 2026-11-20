package dev.frederikpietzko.springaidemo.cli.fakes

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A scripted answer of the [FakeControlEndpoint]. */
data class FakeAnswer(val status: Int, val body: String)

/**
 * A stand-in for the desktop app's control endpoint: a JDK `HttpServer` on an ephemeral loopback port that records
 * every received command line and answers it through [answer] (default: 200 `ok`). [answer] may block, to hold a
 * demo at a line.
 */
class FakeControlEndpoint(
    private val answer: (String) -> FakeAnswer = { FakeAnswer(200, "ok") },
) : AutoCloseable {
    private val received = CopyOnWriteArrayList<String>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/command") { exchange ->
            exchange.use {
                val line = it.requestBody.readAllBytes().decodeToString()
                received += line
                val (status, body) = answer(line)
                val bytes = body.encodeToByteArray()
                it.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) it.responseBody.write(bytes)
            }
        }
        executor = this@FakeControlEndpoint.executor
        start()
    }

    /** The base URL to put into `KAIJU_CONTROL_URL`. */
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** The command lines received so far, in order. */
    val lines: List<String> get() = received.toList()

    override fun close() {
        server.stop(0)
        val _ = executor.shutdownNow()
    }
}

/** An address on which nothing listens. */
fun unusedControlUrl(): String {
    val port = java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    return "http://127.0.0.1:$port"
}
