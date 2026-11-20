package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.cli.fakes.FakeAnswer
import dev.frederikpietzko.springaidemo.cli.fakes.FakeControlEndpoint
import dev.frederikpietzko.springaidemo.cli.fakes.FakeSeedRunner
import dev.frederikpietzko.springaidemo.cli.fakes.RecordingConsole
import dev.frederikpietzko.springaidemo.cli.fakes.unusedControlUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoRunTest {
    private val answers = mutableMapOf<String, FakeAnswer>()
    private val endpoint = FakeControlEndpoint { line -> answers[line] ?: FakeAnswer(200, "ok") }
    private val seeds = FakeSeedRunner()
    private val console = RecordingConsole()

    @TempDir
    private lateinit var dir: Path

    @AfterEach
    fun close() = endpoint.close()

    private fun script(text: String): Path = dir.resolve("talk.kaiju").also { it.writeText(text) }

    private fun runDemo(file: Path, url: String = endpoint.url): Int = kaiju(
        ["demo", "run", file.toString()],
        mapOf("KAIJU_CONTROL_URL" to url, "KAIJU_STATE_DIR" to dir.resolve("state").toString()),
        console,
        KaijuServices(seeds = seeds),
    )

    @Test
    fun `a script runs in the foreground and prints each command and its output`() {
        answers["reply"] = FakeAnswer(200, "Paid")
        val file = script("persona adjuster\nsend \"Hi\"\nwait-idle\nreply\n")

        val exit = runDemo(file)

        assertEquals(0, exit)
        assertEquals(["persona adjuster", "send \"Hi\"", "wait-idle 3m", "reply"], endpoint.lines)
        val out = console.out.lines().filter { it.isNotBlank() }
        assertTrue(out.first().contains("persona adjuster"), console.out)
        assertTrue(out.any { it.contains("send \"Hi\"") }, console.out)
        assertEquals("Paid", out.last())
    }

    @Test
    fun `the run stops at the first failing line`() {
        answers["approve"] = FakeAnswer(409, "there is nothing to approve")
        val file = script("persona adjuster\napprove\nreply\n")

        val exit = runDemo(file)

        assertEquals(1, exit)
        assertEquals(["persona adjuster", "approve"], endpoint.lines)
        assertTrue("talk.kaiju" in console.err && "line 2" in console.err, console.err)
        assertTrue("nothing to approve" in console.err, console.err)
    }

    @Test
    fun `the run stops at an error reply`() {
        answers["wait-idle 3m"] = FakeAnswer(422, "error: The claims desk is unavailable.")
        val file = script("send \"Hi\"\nwait-idle\nreply\n")

        val exit = runDemo(file)

        assertEquals(1, exit)
        assertEquals(["send \"Hi\"", "wait-idle 3m"], endpoint.lines)
        assertTrue("line 2" in console.err && "The claims desk is unavailable." in console.err, console.err)
    }

    @Test
    fun `a file of comments and blank lines sends nothing`() {
        val exit = runDemo(script("# intro\n\n# outro\n"))

        assertEquals(0, exit)
        assertEquals([], endpoint.lines)
    }

    @Test
    fun `a seed line is applied before the next command runs`() {
        val linesAtSeed = mutableListOf<Int>()
        seeds.onApply = { linesAtSeed += endpoint.lines.size }

        val exit = runDemo(script("seed\npersona adjuster\n"))

        assertEquals(0, exit)
        assertEquals([SeedSet.RESET, SeedSet.DESK_QUEUE], seeds.applied)
        assertEquals([0, 0], linesAtSeed)
        assertEquals(["persona adjuster"], endpoint.lines)
    }

    @Test
    fun `a failed seed stops the run`() {
        val failing = FakeSeedRunner { SeedResult.Failed("the database is not running") }
        val exit = kaiju(
            ["demo", "run", script("seed reset\npersona adjuster\n").toString()],
            mapOf("KAIJU_CONTROL_URL" to endpoint.url),
            console,
            KaijuServices(seeds = failing),
        )

        assertEquals(1, exit)
        assertEquals([], endpoint.lines)
        assertTrue("not running" in console.err, console.err)
    }

    @Test
    fun `an unreachable desktop app fails at the first command line with exit code 2`() {
        val exit = runDemo(script("# intro\npersona adjuster\nreply\n"), url = unusedControlUrl())

        assertEquals(2, exit)
        assertTrue("line 2" in console.err && "DESKTOP_CONTROL_PORT" in console.err, console.err)
    }

    @Test
    fun `an invalid line names file and line and nothing runs`() {
        val exit = runDemo(script("# talk\npersona adjuster\nnew-conversation\ndance\n"))

        assertEquals(1, exit)
        assertEquals([], endpoint.lines)
        assertTrue("talk.kaiju" in console.err && "line 4" in console.err && "dance" in console.err, console.err)
    }

    @Test
    fun `a missing file is named and nothing runs`() {
        val exit = runDemo(dir.resolve("missing.kaiju"))

        assertEquals(1, exit)
        assertEquals([], endpoint.lines)
        assertTrue("missing.kaiju" in console.err && "does not exist" in console.err, console.err)
    }
}
