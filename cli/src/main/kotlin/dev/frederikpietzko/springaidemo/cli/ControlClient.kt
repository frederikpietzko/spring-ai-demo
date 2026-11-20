package dev.frederikpietzko.springaidemo.cli

import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The environment variable with the address of the desktop app's control endpoint. */
const val CONTROL_URL_VARIABLE = "KAIJU_CONTROL_URL"

/** The control endpoint's address when [CONTROL_URL_VARIABLE] is not set. */
const val DEFAULT_CONTROL_URL = "http://127.0.0.1:8765"

/** Exit code of a command the endpoint refused (or a wait that failed), and of wrong usage. */
const val EXIT_REFUSED = 1

/** Exit code when no control endpoint answers. */
const val EXIT_UNREACHABLE = 2

private const val HTTP_OK = 200
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)

/** The outcome of one command line sent to the control endpoint. */
@MustUseReturnValues
sealed interface ControlResult {
    /** The text to print: the body, or the reason. */
    val message: String

    /** The exit code a direct command gives for this outcome. */
    val exitCode: Int

    /** Status 200 with its [message] body. */
    data class Ok(override val message: String) : ControlResult {
        override val exitCode: Int get() = 0
    }

    /** Any other status (400, 409, 422, 408): the endpoint's [message] explains it. */
    data class Refused(val status: Int, override val message: String) : ControlResult {
        override val exitCode: Int get() = EXIT_REFUSED
    }

    /** No endpoint answered at the address. */
    data class Unreachable(override val message: String) : ControlResult {
        override val exitCode: Int get() = EXIT_UNREACHABLE
    }
}

/** Sends command lines to the desktop app's control endpoint at [baseUrl] (`POST /command`). */
class ControlClient(val baseUrl: String) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    /** Sends [line] and waits for its answer, which for `wait-idle` can take minutes; cancellable while it waits. */
    suspend fun send(line: String): ControlResult {
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/command"))
            .POST(HttpRequest.BodyPublishers.ofString(line))
            .header("Content-Type", "text/plain; charset=utf-8")
            .build()
        return try {
            val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            val body = response.body().orEmpty()
            if (response.statusCode() == HTTP_OK) {
                ControlResult.Ok(body)
            } else {
                ControlResult.Refused(response.statusCode(), body.ifEmpty { "status ${response.statusCode()}" })
            }
        } catch (_: IOException) {
            ControlResult.Unreachable(
                "the desktop app does not answer at $baseUrl; start it with the control endpoint open, " +
                    "for example DESKTOP_CONTROL_PORT=8765 ./gradlew :desktop:run",
            )
        }
    }

    /** Constants of the control client. */
    companion object {
        /** The client for the address in [env] ([CONTROL_URL_VARIABLE]), or the default address. */
        fun from(env: Map<String, String>): ControlClient =
            ControlClient(env[CONTROL_URL_VARIABLE]?.takeIf { it.isNotBlank() } ?: DEFAULT_CONTROL_URL)
    }
}
