package dev.frederikpietzko.springaidemo.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonAnswer
import dev.frederikpietzko.springaidemo.cli.daemon.DEFAULT_DAEMON_IDLE
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonClient
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonConnection
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonExit
import dev.frederikpietzko.springaidemo.cli.daemon.runDaemon
import dev.frederikpietzko.springaidemo.controlprotocol.parseDuration
import java.io.IOException
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonJson
import dev.frederikpietzko.springaidemo.cli.daemon.DemoState
import dev.frederikpietzko.springaidemo.cli.daemon.DemoStatus
import dev.frederikpietzko.springaidemo.cli.daemon.StartRequest
import dev.frederikpietzko.springaidemo.controlprotocol.ScriptLine
import dev.frederikpietzko.springaidemo.controlprotocol.ScriptResult
import dev.frederikpietzko.springaidemo.controlprotocol.parseScript
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** `kaiju demo`: the demo script commands. */
fun demoCommand(cli: Cli): CoreCliktCommand = DemoGroup().subcommands(
    DemoStartCommand(cli),
    DemoNextCommand(cli),
    DemoStatusCommand(cli),
    DemoStopCommand(cli),
    DemoRunCommand(cli),
)

private class DemoGroup : CoreCliktCommand("demo") {
    override fun help(context: Context): String =
        "Runs demo scripts: one command per line, `#` comments, `seed [set]` lines. `start`, `next`, `status` and " +
            "`stop` work through a background daemon that starts itself; `run` runs a script in the foreground."

    override fun run() = Unit
}

/** A script file read and checked: its [file] path as given and its command [lines]. */
class LoadedScript(val file: String, val lines: List<ScriptLine>)

/**
 * Reads and checks the script [file] before anything runs; prints why and exits with 1 when it does not exist or a
 * line is invalid (naming the file and the first bad line).
 */
fun Cli.loadScript(file: String): LoadedScript {
    val path: Path = Path(file)
    if (!path.exists()) fail("$file does not exist")
    return when (val script = parseScript(path.readText(), SeedSet.names)) {
        is ScriptResult.Valid -> LoadedScript(file, script.lines)
        is ScriptResult.Invalid -> fail("$file, line ${script.lineNumber}: ${script.text}: ${script.message}")
    }
}

/** `<file>, line <n>: <text>: <message>`. */
fun LineFailure.describe(file: String): String = "$file, line ${line.number}: ${line.text}: ${outcome.message}"

private fun Cli.fail(message: String): Nothing {
    console.errLine(message)
    throw ProgramResult(EXIT_REFUSED)
}

private class DemoRunCommand(private val cli: Cli) : CoreCliktCommand("run") {
    private val file by argument("file", help = "the script file")

    override fun help(context: Context): String =
        "Checks the script, then runs it in this process without a daemon: prints each command and its output, " +
            "stops at the first failing line with that line's exit code (1 or 2)."

    override fun run() {
        val script = cli.loadScript(file)
        val runner = DemoRunner(cli.control, cli.seeds)
        val failure = runBlocking {
            runner.run(
                script.lines,
                beforeLine = { cli.console.outLine("> ${it.text}") },
                afterLine = { _, outcome ->
                    if (outcome is LineOutcome.Ok && outcome.output.isNotEmpty()) cli.console.outLine(outcome.output)
                },
            )
        } ?: return
        cli.console.errLine(failure.describe(script.file))
        throw ProgramResult(failure.outcome.exitCode)
    }
}

private class DemoStartCommand(private val cli: Cli) : CoreCliktCommand("start") {
    private val file by argument("file", help = "the script file")
    private val step by option("--step", help = "start paused; run one line per `kaiju demo next`").flag()

    override fun help(context: Context): String =
        "Checks the script and starts it in the background; returns at once. Refused while another demo runs."

    override fun run() {
        val script = cli.loadScript(file)
        val request = StartRequest(
            name = file,
            lines = Path(file).readText().lines(),
            step = step,
            controlUrl = cli.control.baseUrl,
        )
        val status = cli.daemonAnswer { start(request) }
        cli.console.outLine(
            if (step) "paused at line ${status.line}: ${status.text}" else "started ${script.file}",
        )
    }
}

private class DemoNextCommand(private val cli: Cli) : CoreCliktCommand("next") {
    override fun help(context: Context): String =
        "Runs the current line of a paused demo, waits until it has finished, prints its output and exits like " +
            "the direct command would (0, 1 or 2)."

    override fun run() {
        val result = cli.daemonAnswer { next() }
        if (result.exitCode == 0) {
            if (result.output.isNotEmpty()) cli.console.outLine(result.output)
        } else {
            cli.console.errLine(result.output)
            throw ProgramResult(result.exitCode)
        }
    }
}

private class DemoStatusCommand(private val cli: Cli) : CoreCliktCommand("status") {
    private val json by option("--json", help = "print one JSON object with file, line, state and error").flag()

    override fun help(context: Context): String =
        "Prints the demo's file, current line, state (none, running, waiting-step, failed, done, stopped) and error."

    override fun run() {
        val status = cli.daemonAnswer { status() }
        if (json) {
            cli.console.outLine(DaemonJson.encodeToString(DemoStatus.serializer(), status))
            return
        }
        if (status.state == DemoState.NONE.label) cli.console.outLine("no demo exists")
        status.file?.let { cli.console.outLine("file: $it") }
        status.line?.let { cli.console.outLine("line $it: ${status.text}") }
        cli.console.outLine("state: ${status.state}")
        status.error?.let { cli.console.outLine("error: $it") }
    }
}

private class DemoStopCommand(private val cli: Cli) : CoreCliktCommand("stop") {
    override fun help(context: Context): String = "Stops a running or paused demo, also during a wait."

    override fun run() {
        val status = cli.daemonAnswer { stop() }
        cli.console.outLine("state: ${status.state}")
    }
}

/** Exit code when the demo daemon could not be started or stopped answering. */
const val EXIT_DAEMON = 3

/** Runs [call] against the daemon (started if needed); a refusal exits with 1, no daemon with 3. */
private fun <T> Cli.daemonAnswer(call: DaemonClient.() -> DaemonAnswer<T>): T {
    val client = when (val connection = daemon.connect()) {
        is DaemonConnection.Connected -> connection.client
        is DaemonConnection.Unavailable -> failDaemon(connection.message)
    }
    val answer = try {
        client.call()
    } catch (e: IOException) {
        failDaemon("the demo daemon stopped answering (${e.message}); see its log ${files.log}")
    }
    return when (answer) {
        is DaemonAnswer.Ok -> answer.value
        is DaemonAnswer.Refused -> fail(answer.message)
    }
}

private fun Cli.failDaemon(message: String): Nothing {
    console.errLine(message)
    throw ProgramResult(EXIT_DAEMON)
}

/** The hidden `kaiju daemon`: runs the demo daemon in this process until it is idle; started by the CLI itself. */
class DaemonCommand(private val cli: Cli) : CoreCliktCommand("daemon") {
    override val hiddenFromHelp: Boolean = true

    override fun help(context: Context): String =
        "Runs the demo daemon in the foreground (started by the demo commands; not for direct use)."

    override fun run() {
        val idle = cli.env[IDLE_VARIABLE]?.let(::parseDuration) ?: DEFAULT_DAEMON_IDLE
        val exit = runDaemon(cli.files.dir, cli.services.build(), idle, cli.seeds)
        if (exit == DaemonExit.ALREADY_RUNNING) cli.console.errLine("a demo daemon already runs for ${cli.files.dir}")
    }
}

/** The environment variable with the daemon's idle time (a duration such as `5m`). */
const val IDLE_VARIABLE = "KAIJU_DAEMON_IDLE"
