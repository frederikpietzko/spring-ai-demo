package dev.frederikpietzko.springaidemo.cli.daemon

import dev.frederikpietzko.springaidemo.cli.Pacing
import dev.frederikpietzko.springaidemo.cli.SeedRunner
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration

/**
 * Runs the demo daemon for [stateDir] until it is idle for [idle] or asked to shut down: takes `daemon.lock` (and
 * returns at once when another daemon holds it), listens on a free loopback port, records [DaemonInfo] with [build]
 * in `daemon.json`, and paces unattended demos with [Pacing.DEMO]; on exit removes `daemon.json` and `daemon.lock`.
 */
fun runDaemon(stateDir: Path, build: String, idle: Duration, seeds: SeedRunner): DaemonExit {
    val files = DaemonFiles(stateDir)
    val lock = HeldLock.tryTake(files.daemonLock) ?: return DaemonExit.ALREADY_RUNNING
    val shutdown = CountDownLatch(1)
    lock.use {
        val daemon = DemoDaemon(
            seeds = seeds,
            build = build,
            pacing = Pacing.DEMO,
            idle = idle,
            onShutdown = shutdown::countDown,
        )
        daemon.use {
            files.writeInfo(DaemonInfo(ProcessHandle.current().pid(), daemon.port, build))
            shutdown.await()
            files.deleteInfo()
        }
        val _ = files.daemonLock.deleteIfExists()
    }
    return DaemonExit.SHUT_DOWN
}
