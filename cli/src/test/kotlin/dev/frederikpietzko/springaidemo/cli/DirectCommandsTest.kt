package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.cli.fakes.FakeAnswer
import dev.frederikpietzko.springaidemo.cli.fakes.FakeControlEndpoint
import dev.frederikpietzko.springaidemo.cli.fakes.RecordingConsole
import dev.frederikpietzko.springaidemo.cli.fakes.unusedControlUrl
import dev.frederikpietzko.springaidemo.controlprotocol.Command
import dev.frederikpietzko.springaidemo.controlprotocol.ParseResult
import dev.frederikpietzko.springaidemo.controlprotocol.parseCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectCommandsTest {
    private val answers = mutableMapOf<String, FakeAnswer>()
    private val endpoint = FakeControlEndpoint { line -> answers[line] ?: FakeAnswer(200, "ok") }
    private val console = RecordingConsole()

    @TempDir
    private lateinit var stateDir: Path

    @AfterEach
    fun close() = endpoint.close()

    private fun run(vararg args: String, url: String = endpoint.url): Int =
        kaiju(args.toList(), mapOf("KAIJU_CONTROL_URL" to url, "KAIJU_STATE_DIR" to stateDir.toString()), console)

    @Test
    fun `an argument with spaces is sent as one value`() {
        val exit = run("send", "Process claim #4711, please")

        assertEquals(0, exit)
        assertEquals(
            [ParseResult.Parsed(Command.Send("Process claim #4711, please", instant = false))],
            endpoint.lines.map(::parseCommand),
        )
    }

    @Test
    fun `the instant option is passed on`() {
        val _ = run("send", "--instant", "Hi")

        assertEquals(["send --instant \"Hi\""], endpoint.lines)
    }

    @Test
    fun `quotes inside an argument arrive unchanged`() {
        val _ = run("send", "He said \"hi\"")

        assertEquals(
            [ParseResult.Parsed(Command.Send("He said \"hi\"", instant = false))],
            endpoint.lines.map(::parseCommand),
        )
    }

    @Test
    fun `every direct command sends its control command`() {
        val invocations = [
            ["persona", "adjuster"],
            ["new-conversation"],
            ["open", "Gnome attack"],
            ["prepare-claim"],
            ["confirm-claim"],
            ["approve"],
            ["deny"],
            ["typing-speed", "40ms"],
            ["highlight", "0ms"],
            ["wait-idle", "5m"],
            ["wait-idle"],
            ["sleep", "1s"],
            ["reply"],
        ]

        val exits = invocations.map { run(*it.toTypedArray()) }

        assertEquals(List(invocations.size) { 0 }, exits)
        assertEquals(
            [
                "persona adjuster", "new-conversation", "open \"Gnome attack\"", "prepare-claim", "confirm-claim",
                "approve", "deny", "typing-speed 40ms", "highlight 0ms", "wait-idle 5m", "wait-idle 3m", "sleep 1s",
                "reply",
            ],
            endpoint.lines,
        )
    }

    @Test
    fun `the reply is printed`() {
        answers["reply"] = FakeAnswer(200, "Paid")

        val exit = run("reply")

        assertEquals(0, exit)
        assertEquals("Paid", console.out.trim())
    }

    @Test
    fun `the address comes from KAIJU_CONTROL_URL`() {
        FakeControlEndpoint().use { other ->
            val _ = run("approve", url = other.url)

            assertEquals(["approve"], other.lines)
            assertEquals([], endpoint.lines)
        }
    }

    @Test
    fun `a direct command needs no daemon and starts none`() {
        val exit = run("approve")

        assertEquals(0, exit)
        assertEquals([], stateDir.listDirectoryEntries())
    }

    @Test
    fun `a refused command exits with 1 and the reason on standard error`() {
        answers["approve"] = FakeAnswer(409, "there is nothing to approve: no confirmation is pending")

        val exit = run("approve")

        assertEquals(1, exit)
        assertTrue("nothing to approve" in console.err, console.err)
    }

    @Test
    fun `a timed out wait exits with 1`() {
        answers["wait-idle 100ms"] = FakeAnswer(408, "timeout")

        val exit = run("wait-idle", "100ms")

        assertEquals(1, exit)
        assertTrue("timeout" in console.err, console.err)
    }

    @Test
    fun `a wait that ended in an error reply exits with 1`() {
        answers["wait-idle 3m"] = FakeAnswer(422, "error: The claims desk is unavailable.")

        val exit = run("wait-idle")

        assertEquals(1, exit)
        assertTrue("error: The claims desk is unavailable." in console.err, console.err)
    }

    @Test
    fun `a pending confirmation is not a failure`() {
        answers["wait-idle 3m"] = FakeAnswer(200, "idle: confirmation pending (issuePayout)")

        val exit = run("wait-idle")

        assertEquals(0, exit)
        assertTrue(console.out.startsWith("idle: confirmation pending (issuePayout)"), console.out)
    }

    @Test
    fun `an unreachable desktop app exits with 2 and a hint`() {
        val exit = run("state", url = unusedControlUrl())

        assertEquals(2, exit)
        assertTrue("DESKTOP_CONTROL_PORT" in console.err, console.err)
    }

    @Test
    fun `wrong usage exits with 1 and names the allowed values`() {
        val exit = run("persona", "wizard")

        assertEquals(1, exit)
        assertTrue("claimant" in console.err && "adjuster" in console.err, console.err)
        assertEquals([], endpoint.lines)
    }

    @Test
    fun `a bad duration is wrong usage`() {
        val exit = run("sleep", "soon")

        assertEquals(1, exit)
        assertTrue("soon" in console.err, console.err)
        assertEquals([], endpoint.lines)
    }
}
