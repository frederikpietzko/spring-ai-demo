package dev.frederikpietzko.springaidemo.cli.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.frederikpietzko.springaidemo.cli.ControlClient
import dev.frederikpietzko.springaidemo.cli.DemoRunner
import dev.frederikpietzko.springaidemo.cli.LineOutcome
import dev.frederikpietzko.springaidemo.cli.Pacing
import dev.frederikpietzko.springaidemo.cli.SeedRunner
import dev.frederikpietzko.springaidemo.cli.SeedSet
import dev.frederikpietzko.springaidemo.controlprotocol.ScriptResult
import dev.frederikpietzko.springaidemo.controlprotocol.parseScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How long the daemon waits idle before it exits, unless `KAIJU_DAEMON_IDLE` says otherwise. */
val DEFAULT_DAEMON_IDLE: Duration = 5.minutes

private const val IDLE_POLLS_PER_PERIOD = 4
private val MAX_IDLE_POLL: Duration = 1.seconds

/**
 * The demo daemon: owns the one demo that runs, nothing else. A JSON API over HTTP on the loopback address only
 * ([port] 0 picks a free one): `GET /health`, `POST /demo/start`, `POST /demo/next`, `GET /demo/status`,
 * `POST /demo/stop`. A request with an `Origin` header is refused with 403. [seeds] applies `seed` lines; [build] is
 * the identity of the executable it runs from. An unattended demo is paced by [pacing] through [pause].
 */
class DemoDaemon(
    private val seeds: SeedRunner,
    private val build: String,
    private val pacing: Pacing,
    private val pause: suspend (Duration) -> Unit = { delay(it) },
    private val idle: Duration = DEFAULT_DAEMON_IDLE,
    private val onShutdown: () -> Unit = {},
    port: Int = 0,
) : AutoCloseable {
    private val lastActivity = AtomicReference(TimeSource.Monotonic.markNow())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    @Volatile
    private var session: DemoSession? = null

    init {
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.executor = executor
        server.start()
        val _ = scope.launch { exitWhenIdle() }
    }

    /** The address the daemon listens on. */
    val address: InetAddress get() = server.address.address

    /** The port the daemon listens on. */
    val port: Int get() = server.address.port

    /** The base URL of the daemon's API. */
    val url: String get() = "http://127.0.0.1:$port"

    /** Stops the demo and the server. */
    override fun close() {
        session?.stop()
        server.stop(0)
        scope.cancel()
        val _ = executor.shutdownNow()
    }

    /**
     * Calls [onShutdown] once no demo is running or waiting for `next` and no request arrived for [idle], counted
     * from the last request or the end of the last demo.
     */
    private suspend fun exitWhenIdle() {
        val poll = minOf(idle / IDLE_POLLS_PER_PERIOD, MAX_IDLE_POLL)
        while (true) {
            delay(poll)
            if (session?.active != true && lastActivity.get().elapsedNow() >= idle) {
                onShutdown()
                return
            }
        }
    }

    private fun touch() = lastActivity.set(TimeSource.Monotonic.markNow())

    private fun handle(exchange: HttpExchange) {
        touch()
        if (exchange.requestHeaders.containsKey("Origin")) {
            exchange.error(HttpURLConnection.HTTP_FORBIDDEN, "requests from web pages are refused")
            return
        }
        when (exchange.requestMethod to exchange.requestURI.path) {
            "GET" to "/health" -> exchange.json(Health.serializer(), health())
            "POST" to "/shutdown" -> {
                exchange.json(Health.serializer(), health())
                onShutdown()
            }
            "POST" to "/demo/start" -> start(exchange)
            "POST" to "/demo/next" -> next(exchange)
            "GET" to "/demo/status" -> exchange.json(DemoStatus.serializer(), session?.status ?: DemoStatus())
            "POST" to "/demo/stop" -> {
                session?.stop()
                exchange.json(DemoStatus.serializer(), session?.status ?: DemoStatus())
            }
            else -> exchange.error(HttpURLConnection.HTTP_NOT_FOUND, "not found")
        }
    }

    private fun start(exchange: HttpExchange) {
        val request = try {
            DaemonJson.decodeFromString(StartRequest.serializer(), exchange.requestBody.readAllBytes().decodeToString())
        } catch (e: SerializationException) {
            exchange.error(HttpURLConnection.HTTP_BAD_REQUEST, "bad request: ${e.message}")
            return
        }
        val script = parseScript(request.lines.joinToString("\n"), SeedSet.names)
        if (script is ScriptResult.Invalid) {
            exchange.error(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "${request.name}, line ${script.lineNumber}: ${script.text}: ${script.message}",
            )
            return
        }
        val started = synchronized(this) {
            val running = session?.takeIf { it.active }
            if (running == null) {
                val lines = (script as ScriptResult.Valid).lines
                val runner = DemoRunner(ControlClient(request.controlUrl), seeds, pacing, pause)
                session = DemoSession(request.name, lines, request.step, runner, scope, onEnd = ::touch)
            }
            running
        }
        if (started != null) {
            exchange.error(
                HttpURLConnection.HTTP_CONFLICT,
                "a demo is running (${started.status.file}); stop it first with `kaiju demo stop`",
            )
        } else {
            exchange.json(DemoStatus.serializer(), checkNotNull(session).status)
        }
    }

    private fun health() = Health(build, ProcessHandle.current().pid())

    private fun next(exchange: HttpExchange) {
        val outcome = session?.let { runBlocking { it.next() } }
        when (outcome) {
            null -> exchange.error(HttpURLConnection.HTTP_CONFLICT, "nothing to run: no demo is paused")
            is LineOutcome.Ok -> exchange.json(NextResult.serializer(), NextResult(0, outcome.output))
            is LineOutcome.Failed ->
                exchange.json(NextResult.serializer(), NextResult(outcome.exitCode, outcome.message))
        }
    }
}

private fun <T> HttpExchange.json(serializer: KSerializer<T>, value: T, status: Int = HttpURLConnection.HTTP_OK) {
    val bytes = DaemonJson.encodeToString(serializer, value).encodeToByteArray()
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.write(bytes)
}

private fun HttpExchange.error(status: Int, message: String) = json(ErrorBody.serializer(), ErrorBody(message), status)
