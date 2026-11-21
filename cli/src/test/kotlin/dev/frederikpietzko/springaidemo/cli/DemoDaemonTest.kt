package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.cli.daemon.DaemonClient
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonConnection
import dev.frederikpietzko.springaidemo.cli.daemon.DaemonConnector
import dev.frederikpietzko.springaidemo.cli.daemon.DemoDaemon
import dev.frederikpietzko.springaidemo.cli.fakes.FakeAnswer
import dev.frederikpietzko.springaidemo.cli.fakes.FakeControlEndpoint
import dev.frederikpietzko.springaidemo.cli.fakes.FakeSeedRunner
import dev.frederikpietzko.springaidemo.cli.fakes.RecordingConsole
import dev.frederikpietzko.springaidemo.cli.fakes.unusedControlUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay

class DemoDaemonTest {
    private val answers = mutableMapOf<String, FakeAnswer>()
    private val release = CountDownLatch(1)
    private val reachedWait = CountDownLatch(1)
    private val endpoint = FakeControlEndpoint { line ->
        if (line == "wait-idle 5m") {
            reachedWait.countDown()
            val _ = release.await(10, TimeUnit.SECONDS)
        }
        answers[line] ?: FakeAnswer(200, "ok")
    }
    private val seeds = FakeSeedRunner()
    private val daemon = DemoDaemon(seeds = seeds, build = "build-a", pacing = Pacing.NONE)
    private val connector = DaemonConnector { DaemonConnection.Connected(DaemonClient(daemon.url)) }

    @TempDir
    private lateinit var dir: Path

    @AfterEach
    fun close() {
        release.countDown()
        daemon.close()
        endpoint.close()
    }

    private fun script(text: String, name: String = "talk.kaiju"): Path = dir.resolve(name).also { it.writeText(text) }

    private data class Run(val exit: Int, val console: RecordingConsole)

    private fun kaiju(vararg args: String, console: RecordingConsole = RecordingConsole()): Run {
        val exit = kaiju(
            args.toList(),
            mapOf("KAIJU_CONTROL_URL" to endpoint.url),
            console,
            KaijuServices(seeds = seeds, daemon = connector),
        )
        return Run(exit, console)
    }

    private fun status(): JsonObject {
        val (console) = kaiju("demo", "status", "--json")
        return assertIs<JsonObject>(Json.parseToJsonElement(console.out))
    }

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.takeUnless { it.content == "null" }
        ?.content

    private fun awaitState(state: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (status().text("state") != state) {
            check(System.nanoTime() < deadline) { "state never became $state: ${status()}" }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `a valid file starts and returns before it has finished`() {
        val (exit) = kaiju("demo", "start", script("persona adjuster\nwait-idle 5m\nreply\n").toString())

        assertEquals(0, exit)
        assertTrue(reachedWait.await(5, TimeUnit.SECONDS))
        assertEquals("running", status().text("state"))
    }

    @Test
    fun `an invalid line refuses the file and nothing runs`() {
        val file = script("persona adjuster\nnew-conversation\napprove\ndance\n")

        val (exit, console) = kaiju("demo", "start", file.toString())

        assertEquals(1, exit)
        assertTrue("talk.kaiju" in console.err && "line 4" in console.err, console.err)
        assertEquals([], endpoint.lines)
        assertEquals("none", status().text("state"))
    }

    @Test
    fun `a missing file is refused`() {
        val (exit, console) = kaiju("demo", "start", dir.resolve("missing.kaiju").toString())

        assertEquals(1, exit)
        assertTrue("does not exist" in console.err, console.err)
    }

    @Test
    fun `a second demo is refused while one runs`() {
        val _ = kaiju("demo", "start", script("wait-idle 5m\n").toString())
        assertTrue(reachedWait.await(5, TimeUnit.SECONDS))

        val (exit, console) = kaiju("demo", "start", script("approve\n", "other.kaiju").toString())

        assertEquals(1, exit)
        assertTrue("stop" in console.err, console.err)
        assertEquals(["wait-idle 5m"], endpoint.lines)
    }

    @Test
    fun `the whole script runs in order and ends as done`() {
        val _ = kaiju("demo", "start", script("seed\npersona adjuster\nsend \"Hi\"\nwait-idle\nreply\n").toString())

        awaitState("done")
        assertEquals(["persona adjuster", "send \"Hi\"", "wait-idle 3m", "reply"], endpoint.lines)
        assertEquals([SeedSet.RESET, SeedSet.DESK_QUEUE], seeds.applied)
    }

    @Test
    fun `the demo stops at a refusal`() {
        answers["approve"] = FakeAnswer(409, "there is nothing to approve")
        val _ = kaiju("demo", "start", script("persona adjuster\napprove\nreply\n").toString())

        awaitState("failed")
        val status = status()
        assertEquals("2", status.text("line"))
        assertTrue("nothing to approve" in status.text("error").orEmpty(), status.toString())
        assertEquals(["persona adjuster", "approve"], endpoint.lines)
    }

    @Test
    fun `the demo stops at an error reply`() {
        answers["wait-idle 3m"] = FakeAnswer(422, "error: The claims desk is unavailable.")
        val _ = kaiju("demo", "start", script("send \"Hi\"\nwait-idle\nreply\n").toString())

        awaitState("failed")
        assertEquals("wait-idle", status().text("text"))
        assertEquals(["send \"Hi\"", "wait-idle 3m"], endpoint.lines)
    }

    @Test
    fun `an unreachable desktop app fails the first command line`() {
        val unreachable = kaiju(
            ["demo", "start", script("# intro\npersona adjuster\n").toString()],
            mapOf("KAIJU_CONTROL_URL" to unusedControlUrl()),
            RecordingConsole(),
            KaijuServices(seeds = seeds, daemon = connector),
        )

        assertEquals(0, unreachable)
        awaitState("failed")
        assertEquals("2", status().text("line"))
        assertTrue("DESKTOP_CONTROL_PORT" in status().text("error").orEmpty())
    }

    @Test
    fun `step mode starts paused at the first command line`() {
        val file = script("# intro\n\npersona adjuster\nnew-conversation\nreply\n")

        val _ = kaiju("demo", "start", file.toString(), "--step")

        val status = status()
        assertEquals("waiting-step", status.text("state"))
        assertEquals("3", status.text("line"))
        assertEquals("persona adjuster", status.text("text"))
        assertEquals([], endpoint.lines)
    }

    @Test
    fun `each next runs exactly one line`() {
        val _ = kaiju("demo", "start", script("persona adjuster\nnew-conversation\nreply\n").toString(), "--step")

        val exits = [kaiju("demo", "next").exit, kaiju("demo", "next").exit]

        assertEquals([0, 0], exits)
        assertEquals(["persona adjuster", "new-conversation"], endpoint.lines)
        assertEquals("waiting-step", status().text("state"))
        assertEquals("3", status().text("line"))
    }

    @Test
    fun `next on the last line ends the demo as done and prints the output`() {
        answers["reply"] = FakeAnswer(200, "Paid")
        val _ = kaiju("demo", "start", script("reply\n").toString(), "--step")

        val (exit, console) = kaiju("demo", "next")

        assertEquals(0, exit)
        assertEquals("Paid", console.out.trim())
        assertEquals("done", status().text("state"))
    }

    @Test
    fun `next fails with the refused line`() {
        answers["approve"] = FakeAnswer(409, "there is nothing to approve")
        val _ = kaiju("demo", "start", script("approve\nreply\n").toString(), "--step")

        val (exit, console) = kaiju("demo", "next")

        assertEquals(1, exit)
        assertTrue("nothing to approve" in console.err, console.err)
        assertEquals("failed", status().text("state"))
    }

    @Test
    fun `next without a paused demo has nothing to run`() {
        val (exit, console) = kaiju("demo", "next")

        assertEquals(1, exit)
        assertTrue("nothing to run" in console.err, console.err)
    }

    @Test
    fun `status while running shows file, line and state`() {
        val file = script("persona adjuster\nnew-conversation\n# wait\n\nwait-idle 5m\nreply\n")
        val _ = kaiju("demo", "start", file.toString())
        assertTrue(reachedWait.await(5, TimeUnit.SECONDS))

        val (exit, console) = kaiju("demo", "status")

        assertEquals(0, exit)
        val lines = console.out.lines()
        assertTrue(lines.any { file.toString() in it }, console.out)
        assertTrue("line 5: wait-idle 5m" in lines, console.out)
        assertTrue("state: running" in lines, console.out)
    }

    @Test
    fun `status as json is one object with file, line, state and error`() {
        val status = status()

        assertTrue(status.keys.containsAll(["file", "line", "state", "error"]), status.toString())
    }

    @Test
    fun `stop during a wait stops the demo without running further lines`() {
        val _ = kaiju("demo", "start", script("wait-idle 5m\nreply\n").toString())
        assertTrue(reachedWait.await(5, TimeUnit.SECONDS))

        val (exit) = kaiju("demo", "stop")
        release.countDown()

        assertEquals(0, exit)
        assertEquals("stopped", status().text("state"))
        Thread.sleep(200)
        assertEquals(["wait-idle 5m"], endpoint.lines)
    }

    @Test
    fun `a line that throws fails the demo with its message and a new demo can start`() {
        seeds.onApply = { error("the seed runner exploded") }
        val _ = kaiju("demo", "start", script("seed\nreply\n").toString())

        awaitState("failed")
        assertTrue("the seed runner exploded" in status().text("error").orEmpty(), status().toString())
        val (exit) = kaiju("demo", "start", script("reply\n", "other.kaiju").toString())

        assertEquals(0, exit)
        assertEquals("other.kaiju", status().text("file")?.substringAfterLast('/'))
    }

    @Test
    fun `next on a line that throws answers the failure instead of hanging`() {
        seeds.onApply = { error("the seed runner exploded") }
        val _ = kaiju("demo", "start", script("seed\nreply\n").toString(), "--step")

        val (exit, console) = assertTimeoutPreemptively(Duration.ofSeconds(5)) { kaiju("demo", "next") }

        assertEquals(1, exit)
        assertTrue("the seed runner exploded" in console.err, console.err)
        assertEquals("failed", status().text("state"))
    }

    @Test
    fun `stop while next is in flight answers next promptly and the demo stays stopped`() {
        val _ = kaiju("demo", "start", script("wait-idle 5m\nreply\n").toString(), "--step")
        val next = CompletableFuture.supplyAsync { kaiju("demo", "next") }
        assertTrue(reachedWait.await(5, TimeUnit.SECONDS))

        val (exit) = kaiju("demo", "stop")
        val nextRun = assertTimeoutPreemptively(Duration.ofSeconds(5)) { next.get() }
        release.countDown()

        assertEquals(0, exit)
        assertEquals(1, nextRun.exit)
        assertEquals("stopped", status().text("state"))
    }

    @Test
    fun `stop when nothing runs succeeds`() {
        val (exit) = kaiju("demo", "stop")

        assertEquals(0, exit)
    }

    @Test
    fun `the daemon listens on the loopback address only`() {
        assertTrue(daemon.address.isLoopbackAddress, daemon.address.toString())
    }

    @Test
    fun `a request from a web page is refused and starts nothing`() {
        val request = HttpRequest.newBuilder(URI.create("${daemon.url}/demo/start"))
            .header("Origin", "http://example.test")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"name":"x","lines":["approve"],"step":false,"controlUrl":"${endpoint.url}"}""",
                ),
            )
            .build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(403, response.statusCode())
        assertEquals("none", status().text("state"))
        assertEquals([], endpoint.lines)
    }

    @Test
    fun `an unattended demo pauses between lines, is running during the pause and stop interrupts it`() {
        val reachedPause = CountDownLatch(1)
        val paced = DemoDaemon(seeds = seeds, build = "build-a", pacing = Pacing(1.minutes, 1.minutes), pause = {
            reachedPause.countDown()
            delay(it)
        })
        val pacedConnector = DaemonConnector { DaemonConnection.Connected(DaemonClient(paced.url)) }
        fun pacedKaiju(vararg args: String) = RecordingConsole().let { console ->
            Run(
                kaiju(
                    args.toList(),
                    mapOf("KAIJU_CONTROL_URL" to endpoint.url),
                    console,
                    KaijuServices(daemon = pacedConnector),
                ),
                console,
            )
        }
        paced.use {
            val _ = pacedKaiju("demo", "start", script("persona adjuster\nnew-conversation\n").toString())
            assertTrue(reachedPause.await(5, TimeUnit.SECONDS))

            val running = pacedKaiju("demo", "status").console.out
            val stopped = pacedKaiju("demo", "stop").console.out

            assertTrue("state: running" in running.lines(), running)
            assertTrue("state: stopped" in stopped.lines(), stopped)
            Thread.sleep(200)
            assertEquals(["persona adjuster"], endpoint.lines)
        }
    }

    @Test
    fun `step mode is not paced`() {
        val pauses = java.util.concurrent.atomic.AtomicInteger()
        val paced = DemoDaemon(
            seeds = seeds,
            build = "build-a",
            pacing = Pacing(1.minutes, 1.minutes),
            pause = { val _ = pauses.incrementAndGet() },
        )
        val pacedConnector = DaemonConnector { DaemonConnection.Connected(DaemonClient(paced.url)) }
        fun pacedKaiju(vararg args: String) =
            kaiju(
                args.toList(),
                mapOf("KAIJU_CONTROL_URL" to endpoint.url),
                RecordingConsole(),
                KaijuServices(daemon = pacedConnector),
            )
        paced.use {
            val file = script("persona adjuster\nnew-conversation\nwait-idle\nreply\n").toString()
            val _ = pacedKaiju("demo", "start", file, "--step")

            val exits = [pacedKaiju("demo", "next"), pacedKaiju("demo", "next"), pacedKaiju("demo", "next")]

            assertEquals([0, 0, 0], exits)
            assertEquals(["persona adjuster", "new-conversation", "wait-idle 3m"], endpoint.lines)
            assertEquals(0, pauses.get())
        }
    }
}
