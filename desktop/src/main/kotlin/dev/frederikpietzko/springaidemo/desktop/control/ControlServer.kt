package dev.frederikpietzko.springaidemo.desktop.control

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The environment variable that opens the control endpoint on its port. */
const val CONTROL_PORT_VARIABLE = "DESKTOP_CONTROL_PORT"

private const val MAX_PORT = 65_535
private const val COMMAND_PATH = "/command"
private const val PLAIN_TEXT = "text/plain; charset=utf-8"
private const val JSON = "application/json; charset=utf-8"

/** HTTP status 408, which [HttpURLConnection] names only as a client timeout. */
private const val REQUEST_TIMEOUT = HttpURLConnection.HTTP_CLIENT_TIMEOUT

/** HTTP status 422, which [HttpURLConnection] does not name. */
private const val UNPROCESSABLE = 422

/** The port of the control endpoint from `DESKTOP_CONTROL_PORT` read through [getenv]; `null` when unset or invalid. */
fun controlPort(getenv: (String) -> String?): Int? =
    getenv(CONTROL_PORT_VARIABLE)?.toIntOrNull()?.takeIf { it in 1..MAX_PORT }

/**
 * The control endpoint: `POST /command` with one command line as the body, run by [control], on the loopback address
 * only ([port] 0 picks a free one). A request with an `Origin` header (a web page) is refused with 403, another method
 * with 405, another path with 404. Every request runs on its own virtual thread, so `state` answers during a wait.
 */
class ControlServer(private val control: DesktopControl, port: Int) : AutoCloseable {
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    init {
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.executor = executor
        server.start()
    }

    /** The address the endpoint listens on. */
    val address: InetAddress get() = server.address.address

    /** The port the endpoint listens on. */
    val port: Int get() = server.address.port

    /** Stops listening and abandons running commands (a wait may run for minutes). */
    override fun close() {
        server.stop(0)
        val _ = executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        when {
            exchange.requestHeaders.containsKey("Origin") ->
                exchange.answer(HttpURLConnection.HTTP_FORBIDDEN, "requests from web pages are refused")
            exchange.requestURI.path != COMMAND_PATH ->
                exchange.answer(HttpURLConnection.HTTP_NOT_FOUND, "not found: use POST $COMMAND_PATH")
            exchange.requestMethod != "POST" -> {
                exchange.responseHeaders.set("Allow", "POST")
                exchange.answer(HttpURLConnection.HTTP_BAD_METHOD, "use POST $COMMAND_PATH")
            }
            else -> {
                val line = exchange.requestBody.readAllBytes().decodeToString().trimEnd('\r', '\n')
                val reply = runBlocking { control.execute(line) }
                val contentType = if (reply is ControlReply.Ok && reply.json) JSON else PLAIN_TEXT
                exchange.answer(reply.status, reply.body, contentType)
            }
        }
    }
}

/** Starts the control endpoint for [control] when `DESKTOP_CONTROL_PORT` is a valid port; `null` otherwise. */
fun startControlServer(getenv: (String) -> String?, control: DesktopControl): ControlServer? {
    val port = controlPort(getenv) ?: return null
    return try {
        ControlServer(control, port).also {
            System.err.println("Desktop control endpoint on http://127.0.0.1:${it.port}$COMMAND_PATH")
        }
    } catch (e: IOException) {
        System.err.println("Desktop control endpoint not started on port $port: ${e.message}")
        null
    }
}

private val ControlReply.status: Int
    get() = when (this) {
        is ControlReply.Ok -> HttpURLConnection.HTTP_OK
        is ControlReply.Invalid -> HttpURLConnection.HTTP_BAD_REQUEST
        is ControlReply.Refused -> HttpURLConnection.HTTP_CONFLICT
        ControlReply.TimedOut -> REQUEST_TIMEOUT
        is ControlReply.Failed -> UNPROCESSABLE
    }

private fun HttpExchange.answer(status: Int, body: String, contentType: String = PLAIN_TEXT) {
    val bytes = body.encodeToByteArray()
    responseHeaders.set("Content-Type", contentType)
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.write(bytes)
}
