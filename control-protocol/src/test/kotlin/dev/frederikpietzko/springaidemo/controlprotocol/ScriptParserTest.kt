package dev.frederikpietzko.springaidemo.controlprotocol

import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScriptParserTest {
    private val seedSets = ["reset", "desk-queue"]

    @Test
    fun `command lines keep their line numbers and text`() {
        val script = assertIs<ScriptResult.Valid>(parseScript("persona adjuster\nsend \"Hi\"\n", seedSets))

        assertEquals(
            [
                ScriptLine(1, "persona adjuster", ScriptStep.Control(Command.SelectPersona(Persona.ADJUSTER))),
                ScriptLine(2, "send \"Hi\"", ScriptStep.Control(Command.Send("Hi", instant = false))),
            ],
            script.lines,
        )
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val script = assertIs<ScriptResult.Valid>(parseScript("# intro\n\n   \n  # indented\napprove\n", seedSets))

        assertEquals([ScriptLine(5, "approve", ScriptStep.Control(Command.Approve))], script.lines)
    }

    @Test
    fun `a file of only comments and blank lines has no lines`() {
        val script = assertIs<ScriptResult.Valid>(parseScript("# nothing\n\n", seedSets))

        assertEquals([], script.lines)
    }

    @Test
    fun `a seed line names a set or none for all sets`() {
        val script = assertIs<ScriptResult.Valid>(parseScript("seed\nseed reset\nseed desk-queue", seedSets))

        assertEquals(
            [ScriptStep.Seed(null), ScriptStep.Seed("reset"), ScriptStep.Seed("desk-queue")],
            script.lines.map { it.step },
        )
    }

    @Test
    fun `an unknown seed set is an invalid line listing the known sets`() {
        val invalid = assertIs<ScriptResult.Invalid>(parseScript("persona adjuster\nseed nonsense", seedSets))

        assertEquals(2, invalid.lineNumber)
        assertTrue("reset" in invalid.message && "desk-queue" in invalid.message, invalid.message)
    }

    @Test
    fun `a seed line with two sets is an invalid line`() {
        val invalid = assertIs<ScriptResult.Invalid>(parseScript("seed reset desk-queue", seedSets))

        assertEquals(1, invalid.lineNumber)
    }

    @Test
    fun `the first invalid line is named with its number`() {
        val invalid = assertIs<ScriptResult.Invalid>(
            parseScript("# talk\npersona adjuster\nnew-conversation\ndance\nsing", seedSets),
        )

        assertEquals(4, invalid.lineNumber)
        assertEquals("dance", invalid.text)
        assertTrue("dance" in invalid.message, invalid.message)
    }

    @Test
    fun `windows line endings are accepted`() {
        val script = assertIs<ScriptResult.Valid>(parseScript("approve\r\ndeny\r\n", seedSets))

        assertEquals(["approve", "deny"], script.lines.map { it.text })
    }

    @Test
    fun `pace and read-pause lines are script steps with their duration`() {
        val script = assertIs<ScriptResult.Valid>(parseScript("pace 2s\nread-pause 500ms\npace 0ms", seedSets))

        assertEquals(
            [ScriptStep.Pace(2.seconds), ScriptStep.ReadPause(500.milliseconds), ScriptStep.Pace(Duration.ZERO)],
            script.lines.map { it.step },
        )
    }

    @Test
    fun `a bad pacing duration is an invalid line naming the value`() {
        val invalid = assertIs<ScriptResult.Invalid>(parseScript("persona adjuster\npace soon", seedSets))

        assertEquals(2, invalid.lineNumber)
        assertTrue("soon" in invalid.message, invalid.message)
    }

    @Test
    fun `a pacing line without exactly one duration is an invalid line`() {
        val missing = assertIs<ScriptResult.Invalid>(parseScript("read-pause", seedSets))
        val surplus = assertIs<ScriptResult.Invalid>(parseScript("pace 1s 2s", seedSets))

        assertTrue("read-pause" in missing.message, missing.message)
        assertTrue("pace" in surplus.message, surplus.message)
    }

    @Test
    fun `pacing lines are not control commands`() {
        val pace = assertIs<ParseResult.Invalid>(parseCommand("pace 1s"))
        val readPause = assertIs<ParseResult.Invalid>(parseCommand("read-pause 4s"))

        assertTrue("'pace'" in pace.message, pace.message)
        assertTrue("'read-pause'" in readPause.message, readPause.message)
    }
}
