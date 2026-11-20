package dev.frederikpietzko.springaidemo.cli.daemon

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val POLL_MILLIS = 25L

/**
 * Finds the daemon of [files]' state directory and starts one when there is none, when it is stale (no answer on
 * `/health`) or when it runs another [build] (which is shut down first). The check and the start happen under
 * `start.lock`, so commands starting at the same moment launch one daemon. A daemon that does not answer within
 * [startTimeout] makes the connection [DaemonConnection.Unavailable], naming the log file.
 */
class LocalDaemonConnector(
    private val files: DaemonFiles,
    private val build: String,
    private val launcher: DaemonLauncher,
    private val startTimeout: Duration,
) : DaemonConnector {
    override fun connect(): DaemonConnection = startGuard(files).withLock {
        val running = files.readInfo()?.let { info -> health(info)?.let { info to it } }
        when {
            running != null && running.second.build == build -> DaemonConnection.Connected(client(running.first))
            running != null -> {
                replace(running.first)
                start()
            }
            else -> start()
        }
    }

    private fun replace(info: DaemonInfo) {
        try {
            val _ = client(info).shutdown()
        } catch (_: IOException) {
            // Gone already.
        }
        val _ = waitUntil(startTimeout) { !files.daemonLock.exists() }
    }

    private fun start(): DaemonConnection {
        when (val launched = launcher.launch(files.dir)) {
            is LaunchResult.Refused -> return DaemonConnection.Unavailable(launched.message)
            LaunchResult.Launched -> Unit
        }
        val info = waitFor(startTimeout) {
            files.readInfo()?.takeIf { info -> health(info)?.build == build }
        }
        return info?.let { DaemonConnection.Connected(client(it)) } ?: DaemonConnection.Unavailable(
            "the demo daemon did not start within $startTimeout; see its log ${files.log}",
        )
    }

    private fun health(info: DaemonInfo): Health? = try {
        (client(info).health() as? DaemonAnswer.Ok)?.value
    } catch (_: IOException) {
        null
    }

    private fun client(info: DaemonInfo) = DaemonClient("http://127.0.0.1:${info.port}")

    private fun <T : Any> waitFor(timeout: Duration, probe: () -> T?): T? {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            probe()?.let { return it }
            if (deadline.hasPassedNow()) return null
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun waitUntil(timeout: Duration, condition: () -> Boolean): Boolean =
        waitFor<Boolean>(timeout) { true.takeIf { condition() } } ?: false

    private companion object {
        /** In-process guards per state directory: file locks do not exclude threads of one process. */
        val guards = ConcurrentHashMap<java.nio.file.Path, Any>()

        fun startGuard(files: DaemonFiles): StartGuard =
            StartGuard(files, guards.computeIfAbsent(files.dir.toAbsolutePath().normalize()) { Any() })
    }
}

/** `start.lock` plus an in-process monitor for one state directory. */
private class StartGuard(private val files: DaemonFiles, private val monitor: Any) {
    fun <T> withLock(block: () -> T): T = synchronized(monitor) {
        val lock = waitForStartLock()
        lock.use { block() }
    }

    private fun waitForStartLock(): HeldLock {
        while (true) {
            HeldLock.tryTake(files.startLock)?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
    }
}
