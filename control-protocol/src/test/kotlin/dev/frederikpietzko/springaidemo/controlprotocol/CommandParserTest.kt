package dev.frederikpietzko.springaidemo.controlprotocol

import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CommandParserTest {
    @Test
    fun `a quoted argument keeps its spaces and punctuation`() {
        assertEquals(
            ParseResult.Parsed(Command.Send("Process claim #4711, please", instant = false)),
            parseCommand("""send "Process claim #4711, please""""),
        )
    }

    @Test
    fun `escaped quotes and backslashes inside quotes are taken literally`() {
        assertEquals(
            ParseResult.Parsed(Command.Send("""say "hi" \ bye""", instant = false)),
            parseCommand("""send "say \"hi\" \\ bye""""),
        )
    }

    @Test
    fun `an unquoted single word is an argument too`() {
        assertEquals(ParseResult.Parsed(Command.Send("Hi", instant = false)), parseCommand("send Hi"))
    }

    @Test
    fun `send with --instant puts the text in at once`() {
        assertEquals(ParseResult.Parsed(Command.Send("Hi", instant = true)), parseCommand("""send --instant "Hi""""))
    }

    @Test
    fun `surrounding and repeated spaces are ignored`() {
        assertEquals(ParseResult.Parsed(Command.Open("Gnome")), parseCommand("""   open    "Gnome"   """))
    }

    @Test
    fun `every command without arguments is recognised`() {
        assertEquals(
            [
                Command.NewConversation,
                Command.PrepareClaim,
                Command.ConfirmClaim,
                Command.Approve,
                Command.Deny,
                Command.State,
                Command.Reply,
                Command.WaitIdle(Command.DEFAULT_WAIT_TIMEOUT),
            ],
            ["new-conversation", "prepare-claim", "confirm-claim", "approve", "deny", "state", "reply", "wait-idle"]
                .map { (parseCommand(it) as ParseResult.Parsed).command },
        )
    }

    @Test
    fun `persona names a persona in any case`() {
        assertEquals(
            [
                ParseResult.Parsed(Command.SelectPersona(Persona.ADJUSTER)),
                ParseResult.Parsed(Command.SelectPersona(Persona.CLAIMANT)),
            ],
            [parseCommand("persona adjuster"), parseCommand("persona CLAIMANT")],
        )
    }

    @Test
    fun `durations take milliseconds, seconds and minutes`() {
        assertEquals(
            [
                Command.TypingSpeed(40.milliseconds),
                Command.Highlight(0.milliseconds),
                Command.Sleep(2.seconds),
                Command.WaitIdle(5.minutes),
            ],
            ["typing-speed 40ms", "highlight 0ms", "sleep 2s", "wait-idle 5m"]
                .map { (parseCommand(it) as ParseResult.Parsed).command },
        )
    }

    @Test
    fun `an unknown command is named in the error`() {
        val error = assertIs<ParseResult.Invalid>(parseCommand("dance"))

        assertTrue("dance" in error.message, error.message)
    }

    @Test
    fun `an unterminated quote is an error`() {
        val error = assertIs<ParseResult.Invalid>(parseCommand("""send "Hello"""))

        assertTrue("quote" in error.message, error.message)
    }

    @Test
    fun `an escape other than quote and backslash is an error`() {
        assertIs<ParseResult.Invalid>(parseCommand("""send "a\nb""""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["sleep soon", "typing-speed fast", "highlight 3", "wait-idle -1s", "sleep 1h", "sleep 1.5s"],
    )
    fun `a bad duration is named in the error`(line: String) {
        val error = assertIs<ParseResult.Invalid>(parseCommand(line))

        assertTrue(line.substringAfter(' ') in error.message, error.message)
    }

    @Test
    fun `an empty text to send is an error`() {
        val error = assertIs<ParseResult.Invalid>(parseCommand("""send """""))

        assertTrue("empty" in error.message, error.message)
    }

    @Test
    fun `an empty text to send with --instant is an error`() {
        assertIs<ParseResult.Invalid>(parseCommand("""send --instant """""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["approve now", "state x", "persona adjuster claimant", "send a b", "sleep 1s 2s", "wait-idle 1s 2s"],
    )
    fun `a surplus argument is an error`(line: String) {
        val error = assertIs<ParseResult.Invalid>(parseCommand(line))

        assertTrue(line.substringBefore(' ') in error.message, error.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["send", "send --instant", "open", "persona", "sleep", "typing-speed", "highlight"])
    fun `a missing argument is an error`(line: String) {
        val error = assertIs<ParseResult.Invalid>(parseCommand(line))

        assertTrue(line.substringBefore(' ') in error.message, error.message)
    }

    @Test
    fun `an unknown persona is an error`() {
        val error = assertIs<ParseResult.Invalid>(parseCommand("persona pilot"))

        assertTrue("pilot" in error.message, error.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `an empty line is an error`(line: String) {
        assertIs<ParseResult.Invalid>(parseCommand(line))
    }
}
