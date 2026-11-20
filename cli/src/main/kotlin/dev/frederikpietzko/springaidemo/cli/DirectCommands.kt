package dev.frederikpietzko.springaidemo.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.controlprotocol.Command
import dev.frederikpietzko.springaidemo.controlprotocol.parseDuration
import dev.frederikpietzko.springaidemo.controlprotocol.render
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration

/** The commands that send one control command to the desktop app and print its answer. */
fun directCommands(cli: Cli): List<CoreCliktCommand> = [
    PersonaCommand(cli),
    NoArgumentCommand(cli, "new-conversation", "Presses \"New conversation\".", Command.NewConversation),
    OpenCommand(cli),
    SendCommand(cli),
    NoArgumentCommand(cli, "prepare-claim", "Presses \"Prepare claim\".", Command.PrepareClaim),
    NoArgumentCommand(cli, "confirm-claim", "Presses \"Confirm\" on the newest claim card.", Command.ConfirmClaim),
    NoArgumentCommand(cli, "approve", "Presses \"Approve\" on the pending confirmation.", Command.Approve),
    NoArgumentCommand(cli, "deny", "Presses \"Deny\" on the pending confirmation.", Command.Deny),
    DurationCommand(cli, "typing-speed", "Sets the pause after each typed character of later sends.") {
        Command.TypingSpeed(it)
    },
    DurationCommand(cli, "highlight", "Sets how long a pressed control is highlighted before it acts.") {
        Command.Highlight(it)
    },
    WaitIdleCommand(cli),
    DurationCommand(cli, "sleep", "Waits for a fixed time.") { Command.Sleep(it) },
    NoArgumentCommand(cli, "reply", "Prints the text of the newest assistant message.", Command.Reply),
]

/**
 * Sends [command] to the desktop app and prints the answer: the body on standard output for status 200, otherwise
 * the reason on standard error and exit code 1 (refused) or 2 (not reachable).
 */
fun Cli.sendControl(command: Command): String {
    when (val result = runBlocking { control.send(render(command)) }) {
        is ControlResult.Ok -> return result.message
        is ControlResult.Refused, is ControlResult.Unreachable -> {
            console.errLine(result.message)
            throw ProgramResult(result.exitCode)
        }
    }
}

private const val DURATION_HELP = "a whole number followed by ms, s or m, for example 300ms, 2s or 5m"

/** A direct command: builds its [Command], sends it and prints the answer. */
private abstract class DirectCommand(
    protected val cli: Cli,
    name: String,
    private val description: String,
) : CoreCliktCommand(name) {
    override fun help(context: Context): String = description

    abstract fun command(): Command

    override fun run() {
        val body = cli.sendControl(command())
        if (body.isNotEmpty()) cli.console.outLine(body)
    }
}

private class NoArgumentCommand(cli: Cli, name: String, description: String, private val command: Command) :
    DirectCommand(cli, name, description) {
    override fun command(): Command = command
}

private class PersonaCommand(cli: Cli) :
    DirectCommand(cli, "persona", "Switches the persona with the persona switch.") {
    private val persona by argument("persona", help = "claimant or adjuster")
        .choice(Persona.entries.associateBy { it.name.lowercase() })

    override fun command(): Command = Command.SelectPersona(persona)
}

private class OpenCommand(cli: Cli) :
    DirectCommand(cli, "open", "Opens the stored conversation with the given title from the list.") {
    private val title by argument("title", help = "the conversation's title as the list shows it")

    override fun command(): Command = Command.Open(title)
}

private class SendCommand(cli: Cli) :
    DirectCommand(cli, "send", "Types the text into the input, character by character, and presses Send.") {
    private val instant by option("--instant", help = "put the whole text in at once instead of typing it").flag()
    private val text by argument("text", help = "the message; quote it when it holds spaces")

    override fun command(): Command = Command.Send(text, instant)
}

private class DurationCommand(
    cli: Cli,
    name: String,
    description: String,
    private val build: (Duration) -> Command,
) : DirectCommand(cli, name, description) {
    private val duration by argument("duration", help = DURATION_HELP).convert { text ->
        parseDuration(text) ?: fail("bad duration '$text' ($DURATION_HELP)")
    }

    override fun command(): Command = build(duration)
}

private class WaitIdleCommand(cli: Cli) : DirectCommand(
    cli,
    "wait-idle",
    "Waits until no answer streams. Prints `idle` or `idle: confirmation pending (<tool>)`; exits with 1 when the " +
        "reply ended in an error or the timeout passed.",
) {
    private val timeout by argument("timeout", help = "at most this long ($DURATION_HELP); default 3m")
        .convert { text -> parseDuration(text) ?: fail("bad duration '$text' ($DURATION_HELP)") }
        .default(Command.DEFAULT_WAIT_TIMEOUT)

    override fun command(): Command = Command.WaitIdle(timeout)
}
