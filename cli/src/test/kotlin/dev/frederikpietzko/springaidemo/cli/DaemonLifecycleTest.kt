package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.cli.daemon.DaemonClient
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonInfo
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonJson
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonLauncher
import dev.frederikpietzko.springaidemo.cli.daemon.LaunchResult
import dev.frederikpietzko.springaidemo.cli.daemon.NativeDaemonLauncher
import dev.frederikpietzko.springaidemo.cli.fakes.FakeAnswer
import dev.frederikpietzko.springaidemo.cli.fakes.FakeControlEndpoint
import dev.frederikpietzko.springaidemo.cli.fakes.InProcessLauncher
import dev.frederikpietzko.springaidemo.cli.fakes.RecordingConsole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class DaemonLifecycleTest {
    private val endpoint = FakeControlEndpoint { line ->
        if (line == "state") FakeAnswer(200, """{"input":"Hel"}""") else FakeAnswer(200, "ok")
    }

    @TempDir
    private lateinit var stateDir: Path

    @TempDir
    private lateinit var scripts: Path

    private val launchers = mutableListOf<InProcessLauncher>()

    @AfterEach
    fun close() {
        // Shut down whatever daemon is left so threads do not leak into other tests.
        info()?.let { runCatchingIo { DaemonClient("http://127.0.0.1:${it.port}").shutdown() } }
        endpoint.close()
    }

    private data class Run(val exit: Int, val console: RecordingConsole)

    private fun launcher(build: String = "build-a", idle: Duration = 1.minutes, starts: Boolean = true) =
        InProcessLauncher(build, idle, starts = starts).also { launchers += it }

    private fun kaiju(
        vararg args: String,
        launcher: DaemonLauncher,
        build: String = "build-a",
        startTimeout: Duration = 5_000.milliseconds,
    ): Run {
        val console = RecordingConsole()
        val exit = kaiju(
            args.toList(),
            mapOf("KAIJU_CONTROL_URL" to endpoint.url, "KAIJU_STATE_DIR" to stateDir.toString()),
            console,
            KaijuServices(
                seeds = { SeedResult.Applied },
                launcher = launcher,
                build = { build },
                daemonStartTimeout = startTimeout,
            ),
        )
        return Run(exit, console)
    }

    private fun info(): DaemonInfo? = stateDir.resolve("daemon.json").takeIf { it.exists() }
        ?.let { DaemonJson.decodeFromString(DaemonInfo.serializer(), it.readText()) }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private fun answers(port: Int): Boolean = runCatchingIo { DaemonClient("http://127.0.0.1:$port").health() } != null

    private fun <T> runCatchingIo(block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    }

    @Test
    fun `the first demo command starts the daemon`() {
        val launcher = launcher()

        val (exit, console) = kaiju("demo", "status", launcher = launcher)

        assertEquals(0, exit, console.err)
        assertEquals(1, launcher.launches)
        assertTrue("no demo exists" in console.out, console.out)
    }

    @Test
    fun `a running daemon is reused`() {
        val launcher = launcher()
        val _ = kaiju("demo", "status", launcher = launcher)

        val (exit) = kaiju("demo", "status", launcher = launcher)

        assertEquals(0, exit)
        assertEquals(1, launcher.launches)
    }

    @Test
    fun `two commands starting at the same moment launch one daemon`() {
        val launcher = launcher()
        val pool = Executors.newFixedThreadPool(2)

        val exits = pool.invokeAll(List(2) { Callable { kaiju("demo", "status", launcher = launcher).exit } })
            .map { it.get() }
        pool.shutdown()

        assertEquals([0, 0], exits)
        assertEquals(1, launcher.launches)
    }

    @Test
    fun `a stale lock is replaced by a new daemon`() {
        val deadPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        stateDir.resolve("daemon.lock").writeText("")
        stateDir.resolve("daemon.json").writeText(
            DaemonJson.encodeToString(DaemonInfo.serializer(), DaemonInfo(999_999, deadPort, "build-a")),
        )
        val launcher = launcher()

        val (exit) = kaiju("demo", "status", launcher = launcher)

        assertEquals(0, exit)
        assertEquals(1, launcher.launches)
        assertTrue(info()?.port != deadPort)
    }

    @Test
    fun `a daemon that does not answer in time exits with 3 and names the log`() {
        val (exit, console) =
            kaiju("demo", "status", launcher = launcher(starts = false), startTimeout = 300.milliseconds)

        assertEquals(3, exit)
        assertTrue("daemon.log" in console.err, console.err)
    }

    @Test
    fun `the daemon exits when idle and removes its lock file`() {
        val _ = kaiju("demo", "status", launcher = launcher(idle = 200.milliseconds))
        val port = checkNotNull(info()).port

        awaitTrue("idle exit") { !stateDir.resolve("daemon.lock").exists() }

        assertFalse(answers(port))
        assertFalse(stateDir.resolve("daemon.json").exists())
    }

    @Test
    fun `a demo waiting for next keeps the daemon alive`() {
        val launcher = launcher(idle = 200.milliseconds)
        val script = scripts.resolve("talk.kaiju").also { it.writeText("persona adjuster\nreply\n") }
        val _ = kaiju("demo", "start", script.toString(), "--step", launcher = launcher)
        val port = checkNotNull(info()).port

        Thread.sleep(700)

        assertTrue(answers(port))
        val (console) = kaiju("demo", "status", launcher = launcher)
        assertTrue("state: waiting-step" in console.out.lines(), console.out)
        assertEquals(1, launcher.launches)
    }

    @Test
    fun `a new daemon starts after the old one exited`() {
        val launcher = launcher(idle = 200.milliseconds)
        val _ = kaiju("demo", "status", launcher = launcher)
        awaitTrue("idle exit") { !stateDir.resolve("daemon.lock").exists() }

        val (exit) = kaiju("demo", "status", launcher = launcher)

        assertEquals(0, exit)
        assertEquals(2, launcher.launches)
    }

    @Test
    fun `a daemon of another build is replaced`() {
        val _ = kaiju("demo", "status", launcher = launcher("build-a"), build = "build-a")
        val oldPort = checkNotNull(info()).port

        val (exit) = kaiju("demo", "status", launcher = launcher("build-b"), build = "build-b")

        assertEquals(0, exit)
        assertFalse(answers(oldPort))
        val current = checkNotNull(info())
        assertEquals("build-b", current.build)
        assertTrue(answers(current.port))
    }

    @Test
    fun `the same build does not restart the daemon`() {
        val launcher = launcher()
        val _ = kaiju("demo", "status", launcher = launcher)
        val pidAndPort = info()

        val _ = kaiju("demo", "status", launcher = launcher)

        assertEquals(pidAndPort, info())
        assertEquals(1, launcher.launches)
    }

    @Test
    fun `state needs no daemon and starts none`() {
        val launcher = launcher()

        val (exit) = kaiju("state", "--json", launcher = launcher)

        assertEquals(0, exit)
        assertEquals(0, launcher.launches)
        assertEquals([], stateDir.listDirectoryEntries())
    }

    @Test
    fun `a daemon replaced mid-demo leaves the desktop as it is`() {
        val script = scripts.resolve("talk.kaiju").also { it.writeText("send \"Hello\"\nreply\n") }
        val _ = kaiju("demo", "start", script.toString(), "--step", launcher = launcher("build-a"), build = "build-a")
        val _ = kaiju("demo", "next", launcher = launcher("build-a"), build = "build-a")

        val status = kaiju("demo", "status", launcher = launcher("build-b"), build = "build-b").console
        val state = kaiju("state", "--json", launcher = launcher("build-b"), build = "build-b").console

        assertTrue("no demo exists" in status.out, status.out)
        assertTrue("Hel" in state.out, state.out)
        assertEquals(["send \"Hello\"", "state"], endpoint.lines)
    }

    @Test
    fun `the real launcher refuses when the CLI runs on the JVM`() {
        val launcher = NativeDaemonLauncher(executable = Path("/usr/lib/jvm/bin/java"))

        val result = launcher.launch(stateDir)

        val refused = result as LaunchResult.Refused
        assertTrue("installKaiju" in refused.message, refused.message)
    }
}
