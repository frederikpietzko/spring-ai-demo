package dev.frederikpietzko.springaidemo.controlprotocol

import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RenderTest {
    @ParameterizedTest
    @MethodSource("commands")
    fun `a rendered command parses back to itself`(command: Command) {
        assertEquals(ParseResult.Parsed(command), parseCommand(render(command)))
    }

    companion object {
        @JvmStatic
        fun commands(): List<Command> = [
            Command.SelectPersona(Persona.ADJUSTER),
            Command.SelectPersona(Persona.CLAIMANT),
            Command.NewConversation,
            Command.Open("Gnome attack"),
            Command.Open("""a "quoted" \ title"""),
            Command.Send("Process claim #4711, please", instant = false),
            Command.Send("""He said "hi"""", instant = false),
            Command.Send("""back\slash and \" both""", instant = true),
            Command.Send("--instant", instant = false),
            Command.Send("Hi", instant = true),
            Command.PrepareClaim,
            Command.ConfirmClaim,
            Command.Approve,
            Command.Deny,
            Command.TypingSpeed(40.milliseconds),
            Command.TypingSpeed(0.milliseconds),
            Command.Highlight(1500.milliseconds),
            Command.WaitIdle(5.minutes),
            Command.WaitIdle(Command.DEFAULT_WAIT_TIMEOUT),
            Command.Sleep(2.seconds),
            Command.Sleep(90.seconds),
            Command.State,
            Command.Reply,
        ]
    }
}
