package dev.frederikpietzko.springaidemo.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonConnector
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonFiles
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonLauncher
import dev.frederikpietzko.springaidemo.cli.daemon.LocalDaemonConnector
import dev.frederikpietzko.springaidemo.cli.daemon.NativeDaemonLauncher
import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the `kaiju` command line with [args], reading variables from [env] and writing to [console]; answers the exit
 * code: 0 success, 1 refused or wrong usage, 2 desktop app not reachable, 3 the demo daemon could not start.
 */
fun kaiju(
    args: List<String>,
    env: Map<String, String>,
    console: Console,
    services: KaijuServices = KaijuServices(),
): Int {
    val command = kaijuCommand(env, console, services)
    return try {
        command.parse(args)
        0
    } catch (e: CliktError) {
        command.echoFormattedHelp(e)
        e.statusCode
    }
}

/** The `kaiju` command tree for [env], [console] and [services]. */
fun kaijuCommand(
    env: Map<String, String>,
    console: Console,
    services: KaijuServices = KaijuServices(),
): CoreCliktCommand {
    val cli = Cli(env, console, services)
    return Root()
        .context {
            readEnvvar = { env[it] }
            echoMessage = { _, message, newline, err ->
                val text = message.toString() + if (newline) "\n" else ""
                if (err) console.err(text) else console.out(text)
            }
        }
        .subcommands(
            directCommands(cli) + listOf(StateCommand(cli), SeedCommand(cli), demoCommand(cli), DaemonCommand(cli)),
        )
}

/** The ports to the world outside the process that tests replace with fakes. */
class KaijuServices(
    /** Applies seed sets; `null` for the compose database (see [locateComposeFile]). */
    val seeds: SeedRunner? = null,
    /** The executable this process runs from (`null` when unknown). */
    val executable: Path? = NativeDaemonLauncher.currentExecutable(),
    /** Starts a daemon process. */
    val launcher: DaemonLauncher = NativeDaemonLauncher(executable),
    /** The build identity of this executable, compared with the running daemon's. */
    val build: () -> String = { buildIdentity(executable) },
    /** How long a started daemon may take to answer. */
    val daemonStartTimeout: Duration = DEFAULT_DAEMON_START_TIMEOUT,
    /** Finds or starts the demo daemon; `null` for the daemon of the state directory. */
    val daemon: DaemonConnector? = null,
    /** The pacing of `demo run`. */
    val pacing: Pacing = Pacing.DEMO,
    /** How `demo run` pauses between lines. */
    val pause: suspend (Duration) -> Unit = { delay(it) },
)

/** How long a started daemon may take to answer before the command exits with 3. */
val DEFAULT_DAEMON_START_TIMEOUT: Duration = 10.seconds

/** What every command needs: the environment, the console, the [services] and the control endpoint. */
class Cli(val env: Map<String, String>, val console: Console, val services: KaijuServices) {
    /** The client of the desktop app's control endpoint. */
    val control: ControlClient by lazy { ControlClient.from(env) }

    /** Applies seed sets. */
    val seeds: SeedRunner by lazy {
        services.seeds ?: ComposeSeedRunner(locateComposeFile(env, services.executable))
    }

    /** The state directory with the daemon's files. */
    val files: DaemonFiles by lazy { DaemonFiles.locate(env, services.executable) }

    /** Finds or starts the demo daemon. */
    val daemon: DaemonConnector by lazy {
        services.daemon ?: LocalDaemonConnector(files, services.build(), services.launcher, services.daemonStartTimeout)
    }
}

private class Root : CoreCliktCommand(name = "kaiju") {
    override val printHelpOnEmptyArgs: Boolean = true

    override fun help(context: Context): String =
        "Drives the Kaiju Insurance desktop app: press its controls, wait for answers, read the screen, run demo " +
            "scripts and seed the demo data. The desktop app must run with DESKTOP_CONTROL_PORT set; the address " +
            "is KAIJU_CONTROL_URL (default $DEFAULT_CONTROL_URL)."

    override fun run() = Unit
}
