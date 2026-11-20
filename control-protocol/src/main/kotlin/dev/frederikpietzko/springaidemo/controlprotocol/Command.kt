package dev.frederikpietzko.springaidemo.controlprotocol

import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One command of the control endpoint, as parsed from a command line by [parseCommand]. */
sealed interface Command {
    /** A command that presses a control; actions run one at a time. */
    sealed interface Action : Command

    /** `persona <claimant|adjuster>`: presses the persona switch. */
    data class SelectPersona(val persona: Persona) : Action

    /** `new-conversation`: presses "New conversation". */
    data object NewConversation : Action

    /** `open "<title>"`: presses the row of the stored conversation titled [title]. */
    data class Open(val title: String) : Action

    /** `send [--instant] "<text>"`: types [text] into the input (all at once when [instant]) and presses Send. */
    data class Send(val text: String, val instant: Boolean) : Action

    /** `prepare-claim`: presses "Prepare claim". */
    data object PrepareClaim : Action

    /** `confirm-claim`: presses "Confirm" on the newest claim card. */
    data object ConfirmClaim : Action

    /** `approve`: presses "Approve" on the pending confirmation. */
    data object Approve : Action

    /** `deny`: presses "Deny" on the pending confirmation. */
    data object Deny : Action

    /** `typing-speed <duration>`: the pause after each typed character of later sends. */
    data class TypingSpeed(val duration: Duration) : Command

    /** `highlight <duration>`: how long a pressed control is highlighted before its action runs. */
    data class Highlight(val duration: Duration) : Command

    /** `wait-idle [timeout]`: waits until no answer streams, at most [timeout]. */
    data class WaitIdle(val timeout: Duration) : Command

    /** `sleep <duration>`: a fixed pause. */
    data class Sleep(val duration: Duration) : Command

    /** `state`: the screen state as JSON. */
    data object State : Command

    /** `reply`: the text of the newest assistant message. */
    data object Reply : Command

    /** Constants of the command syntax. */
    companion object {
        /** How long `wait-idle` waits when no timeout is given. */
        val DEFAULT_WAIT_TIMEOUT: Duration = 180.seconds
    }
}

/** The outcome of [parseCommand]: the command, or why the line is not one. */
@MustUseReturnValues
sealed interface ParseResult {
    /** The line is the [command]. */
    data class Parsed(val command: Command) : ParseResult

    /** The line is not a valid command; [message] names the problem. */
    data class Invalid(val message: String) : ParseResult
}

/** The option of `send` that puts the text in at once; an option only when unquoted. */
const val INSTANT_OPTION = "--instant"

private val DURATION = Regex("""(\d+)(ms|s|m)""")

/** Each command name with the parser of its arguments. */
private val COMMANDS: Map<String, (List<Word>) -> ParseResult> = mapOf(
    "persona" to texts { args -> oneArgument("persona", args, ::persona) },
    "new-conversation" to texts { args -> noArguments("new-conversation", args, Command.NewConversation) },
    "open" to texts { args -> oneArgument("open", args) { ParseResult.Parsed(Command.Open(it)) } },
    "send" to { args ->
        val instant = args.firstOrNull() == Word(INSTANT_OPTION, quoted = false)
        send(args.drop(if (instant) 1 else 0).map { it.text }, instant)
    },
    "prepare-claim" to texts { args -> noArguments("prepare-claim", args, Command.PrepareClaim) },
    "confirm-claim" to texts { args -> noArguments("confirm-claim", args, Command.ConfirmClaim) },
    "approve" to texts { args -> noArguments("approve", args, Command.Approve) },
    "deny" to texts { args -> noArguments("deny", args, Command.Deny) },
    "typing-speed" to texts { args -> oneArgument("typing-speed", args) { duration(it, Command::TypingSpeed) } },
    "highlight" to texts { args -> oneArgument("highlight", args) { duration(it, Command::Highlight) } },
    "wait-idle" to texts(::waitIdle),
    "sleep" to texts { args -> oneArgument("sleep", args) { duration(it, Command::Sleep) } },
    "state" to texts { args -> noArguments("state", args, Command.State) },
    "reply" to texts { args -> noArguments("reply", args, Command.Reply) },
)

private fun texts(parse: (List<String>) -> ParseResult): (List<Word>) -> ParseResult = { words ->
    parse(words.map { it.text })
}

/**
 * Parses one command line: a command name and arguments separated by spaces; an argument in double quotes may hold
 * spaces, with `\"` and `\\` as the only escapes. Durations are a whole number followed by `ms`, `s` or `m`. An empty
 * line, an unknown command, a missing or surplus argument, a bad duration, an empty text to send or an unterminated
 * quote is [ParseResult.Invalid] with a message that names the problem.
 */
fun parseCommand(line: String): ParseResult = when (val tokens = tokenize(line)) {
    is Tokens.Invalid -> ParseResult.Invalid(tokens.message)
    is Tokens.Words if tokens.words.isEmpty() -> ParseResult.Invalid("empty command line")
    is Tokens.Words -> {
        val name = tokens.words.first().text
        val parser = COMMANDS[name]
        parser?.invoke(tokens.words.drop(1)) ?: ParseResult.Invalid(
            "unknown command '$name' (known: ${COMMANDS.keys.joinToString(", ")})",
        )
    }
}

private fun noArguments(name: String, args: List<String>, command: Command): ParseResult =
    if (args.isEmpty()) ParseResult.Parsed(command) else ParseResult.Invalid("'$name' takes no arguments")

private fun oneArgument(name: String, args: List<String>, parse: (String) -> ParseResult): ParseResult =
    when (args.size) {
        0 -> ParseResult.Invalid("'$name' needs one argument")
        1 -> parse(args.single())
        else -> ParseResult.Invalid("'$name' takes one argument, got ${args.size} (quote an argument with spaces)")
    }

private fun persona(name: String): ParseResult =
    Persona.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?.let { ParseResult.Parsed(Command.SelectPersona(it)) }
        ?: ParseResult.Invalid("unknown persona '$name' (use claimant or adjuster)")

private fun send(args: List<String>, instant: Boolean): ParseResult =
    oneArgument("send", args) { text ->
        if (text.isEmpty()) {
            ParseResult.Invalid("'send' needs a non-empty text")
        } else {
            ParseResult.Parsed(Command.Send(text, instant))
        }
    }

private fun waitIdle(args: List<String>): ParseResult =
    if (args.isEmpty()) {
        ParseResult.Parsed(Command.WaitIdle(Command.DEFAULT_WAIT_TIMEOUT))
    } else {
        oneArgument("wait-idle", args) { duration(it, Command::WaitIdle) }
    }

private fun duration(text: String, command: (Duration) -> Command): ParseResult =
    parseDuration(text)?.let { ParseResult.Parsed(command(it)) }
        ?: ParseResult.Invalid("bad duration '$text' (use a whole number followed by ms, s or m)")

/** Parses a duration of the command syntax: a whole number followed by `ms`, `s` or `m`; `null` for anything else. */
fun parseDuration(text: String): Duration? {
    val match = DURATION.matchEntire(text)
    val amount = match?.groupValues?.get(1)?.toLongOrNull()
    return when (match?.groupValues?.get(2)) {
        "ms" -> amount?.milliseconds
        "s" -> amount?.seconds
        "m" -> amount?.minutes
        else -> null
    }
}

/** A word of a command line; [quoted] when any part of it was in quotes, so `"--instant"` is text, not an option. */
private data class Word(val text: String, val quoted: Boolean)

private sealed interface Tokens {
    data class Words(val words: List<Word>) : Tokens

    data class Invalid(val message: String) : Tokens
}

private fun tokenize(line: String): Tokens = Tokenizer(line).run()

/** Splits a line into words like a shell: spaces separate, double quotes group, `\"` and `\\` escape in quotes. */
private class Tokenizer(private val line: String) {
    private val words = mutableListOf<Word>()
    private val current = StringBuilder()
    private var inWord = false
    private var quoted = false
    private var wordQuoted = false
    private var error: String? = null
    private var index = 0

    fun run(): Tokens {
        while (index < line.length && error == null) {
            step(line[index])
            index++
        }
        if (inWord) words += Word(current.toString(), wordQuoted)
        val problem = error ?: if (quoted) "unterminated quote" else null
        return problem?.let { Tokens.Invalid(it) } ?: Tokens.Words(words.toList())
    }

    private fun step(char: Char) {
        when {
            quoted && char == '\\' -> escape()
            quoted && char == '"' -> quoted = false
            quoted -> current.append(char)
            char == '"' -> {
                quoted = true
                inWord = true
                wordQuoted = true
            }
            char.isWhitespace() -> endWord()
            else -> {
                current.append(char)
                inWord = true
            }
        }
    }

    private fun escape() {
        val next = line.getOrNull(index + 1)
        if (next == '"' || next == '\\') {
            current.append(next)
            index++
        } else {
            error = "unknown escape '\\${next ?: ""}' (only \\\" and \\\\ are escapes)"
        }
    }

    private fun endWord() {
        if (!inWord) return
        words += Word(current.toString(), wordQuoted)
        current.clear()
        inWord = false
        wordQuoted = false
    }
}
