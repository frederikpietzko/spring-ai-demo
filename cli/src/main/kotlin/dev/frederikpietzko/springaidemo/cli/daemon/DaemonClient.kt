package dev.frederikpietzko.springaidemo.cli.daemon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Finds (and, for the real one, starts) the demo daemon. */
fun interface DaemonConnector {
    /** A connection to a running daemon, or why there is none. */
    fun connect(): DaemonConnection
}

/** The outcome of [DaemonConnector.connect]. */
@MustUseReturnValues
sealed interface DaemonConnection {
    /** A daemon runs; [client] talks to it. */
    data class Connected(val client: DaemonClient) : DaemonConnection

    /** No daemon could be started; [message] says why (exit code 3). */
    data class Unavailable(val message: String) : DaemonConnection
}

/** The answer of a daemon request: the decoded value, or the daemon's refusal. */
@MustUseReturnValues
sealed interface DaemonAnswer<out T> {
    /** Status 200 with its [value]. */
    data class Ok<T>(val value: T) : DaemonAnswer<T>

    /** The daemon refused with [status] and [message]. */
    data class Refused(val status: Int, val message: String) : DaemonAnswer<Nothing>
}

private const val HTTP_OK = 200

/** The CLI side of the daemon's API at [baseUrl]. */
class DaemonClient(private val baseUrl: String) {
    private val http: HttpClient = HttpClient.newHttpClient()

    /** `GET /health`. */
    fun health(): DaemonAnswer<Health> = get("/health", Health.serializer())

    /** `POST /demo/start`. */
    fun start(request: StartRequest): DaemonAnswer<DemoStatus> =
        post("/demo/start", DaemonJson.encodeToString(StartRequest.serializer(), request), DemoStatus.serializer())

    /** `POST /demo/next`: answers when the line has finished. */
    fun next(): DaemonAnswer<NextResult> = post("/demo/next", "", NextResult.serializer())

    /** `GET /demo/status`. */
    fun status(): DaemonAnswer<DemoStatus> = get("/demo/status", DemoStatus.serializer())

    /** `POST /shutdown`: the daemon exits; a running demo is lost. */
    fun shutdown(): DaemonAnswer<Health> = post("/shutdown", "", Health.serializer())

    /** `POST /demo/stop`. */
    fun stop(): DaemonAnswer<DemoStatus> = post("/demo/stop", "", DemoStatus.serializer())

    private fun <T> get(path: String, serializer: KSerializer<T>): DaemonAnswer<T> =
        exchange(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(), serializer)

    private fun <T> post(path: String, body: String, serializer: KSerializer<T>): DaemonAnswer<T> = exchange(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        serializer,
    )

    private fun <T> exchange(request: HttpRequest, serializer: KSerializer<T>): DaemonAnswer<T> {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == HTTP_OK) {
            DaemonAnswer.Ok(DaemonJson.decodeFromString(serializer, response.body()))
        } else {
            val message = try {
                DaemonJson.decodeFromString(ErrorBody.serializer(), response.body()).error
            } catch (_: SerializationException) {
                response.body()
            }
            DaemonAnswer.Refused(response.statusCode(), message)
        }
    }
}
