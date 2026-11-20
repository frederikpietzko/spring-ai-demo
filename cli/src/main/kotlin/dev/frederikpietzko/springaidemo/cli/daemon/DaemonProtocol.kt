package dev.frederikpietzko.springaidemo.cli.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON of the daemon's API: defaults are written, unknown fields are ignored. */
val DaemonJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/** `GET /health`: the daemon's build identity and process id. */
@Serializable
data class Health(val build: String, val pid: Long)

/** `POST /demo/start`: the script file's [name], all its [lines], whether to [step], the control endpoint address. */
@Serializable
data class StartRequest(val name: String, val lines: List<String>, val step: Boolean, val controlUrl: String)

/** A demo's progress: the [state] names one of [DemoState]; [line] and [text] are the current line. */
@Serializable
data class DemoStatus(
    val file: String? = null,
    val line: Int? = null,
    val text: String? = null,
    val state: String = DemoState.NONE.label,
    val error: String? = null,
)

/** `POST /demo/next`: the outcome of the line that ran, with the exit code a direct command would give. */
@Serializable
data class NextResult(val exitCode: Int, val output: String)

/** An error answer: why the request was refused. */
@Serializable
data class ErrorBody(val error: String)

/** The states of a demo. */
enum class DemoState(val label: String) {
    /** No demo was started (or the daemon was replaced). */
    NONE("none"),

    /** The demo runs its lines. */
    RUNNING("running"),

    /** A step-mode demo waits for `next` before its current line. */
    WAITING_STEP("waiting-step"),

    /** A line failed; the error is kept. */
    FAILED("failed"),

    /** Every line succeeded. */
    DONE("done"),

    /** The demo was stopped. */
    STOPPED("stopped"),
}
