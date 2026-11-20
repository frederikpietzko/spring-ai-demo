package dev.frederikpietzko.springaidemo.cli.daemon

import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import kotlin.io.path.createDirectories

/** The outcome of starting a daemon process. */
@MustUseReturnValues
sealed interface LaunchResult {
    /** A process was started; it may still fail to come up. */
    data object Launched : LaunchResult

    /** No process was started; [message] says why. */
    data class Refused(val message: String) : LaunchResult
}

/** Starts the demo daemon for a state directory (port: the real one relaunches the executable detached). */
fun interface DaemonLauncher {
    /** Starts a daemon that uses [stateDir]. */
    fun launch(stateDir: Path): LaunchResult
}

/**
 * Starts `<executable> daemon` detached, with `KAIJU_STATE_DIR` set, its output appended to `daemon.log` and no
 * inherited input. Refuses when [executable] is unknown or a `java` launcher: on the JVM the CLI cannot relaunch
 * itself as one file.
 */
class NativeDaemonLauncher(private val executable: Path?) : DaemonLauncher {
    override fun launch(stateDir: Path): LaunchResult {
        val file = executable?.takeUnless(::isJavaLauncher)
            ?: return LaunchResult.Refused(
                "the demo daemon needs the kaiju executable; build it with ./gradlew :cli:installKaiju and run ./kaiju",
            )
        val files = DaemonFiles(stateDir.createDirectories())
        return try {
            val _ = ProcessBuilder(file.toString(), "daemon")
                .redirectInput(Redirect.from(Path.of("/dev/null").toFile()))
                .redirectOutput(Redirect.appendTo(files.log.toFile()))
                .redirectErrorStream(true)
                .apply { environment()[STATE_DIR_VARIABLE] = stateDir.toString() }
                .start()
            LaunchResult.Launched
        } catch (e: IOException) {
            LaunchResult.Refused("the demo daemon could not be started: ${e.message}")
        }
    }

    /** The executable this process runs from. */
    companion object {
        /** The path of the running executable, if the platform tells it. */
        fun currentExecutable(): Path? = ProcessHandle.current().info().command().map { Path.of(it) }.orElse(null)
    }
}
