package dev.frederikpietzko.springaidemo.cli.fakes

import dev.frederikpietzko.springaidemo.cli.SeedRunner
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonLauncher
import dev.frederikpietzko.springaidemo.cli.daemon.LaunchResult
import dev.frederikpietzko.springaidemo.cli.daemon.runDaemon
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * A [DaemonLauncher] that runs the real daemon ([runDaemon]) in this process on its own thread, as the daemon of
 * [build] with the idle time [idle]; counts its launches. With [starts] false it launches nothing (a daemon that
 * never answers).
 */
class InProcessLauncher(
    private val build: String,
    private val idle: Duration,
    private val seeds: SeedRunner = FakeSeedRunner(),
    private val starts: Boolean = true,
) : DaemonLauncher {
    private val count = AtomicInteger()
    private val threads = CopyOnWriteArrayList<Thread>()

    /** How often a daemon was launched. */
    val launches: Int get() = count.get()

    override fun launch(stateDir: Path): LaunchResult {
        val _ = count.incrementAndGet()
        if (starts) threads += Thread.ofPlatform().daemon().start { val _ = runDaemon(stateDir, build, idle, seeds) }
        return LaunchResult.Launched
    }
}
