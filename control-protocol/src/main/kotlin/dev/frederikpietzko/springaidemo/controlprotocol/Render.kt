package dev.frederikpietzko.springaidemo.controlprotocol

import kotlin.time.Duration

/**
 * Renders [command] as a command line that [parseCommand] parses back to it: text arguments in double quotes with
 * `"` and `\` escaped, durations in the largest whole unit (`m`, `s` or `ms`).
 */
fun render(command: Command): String = when (command) {
    is Command.Action -> renderAction(command)
    is Command.TypingSpeed -> "typing-speed ${render(command.duration)}"
    is Command.Highlight -> "highlight ${render(command.duration)}"
    is Command.WaitIdle -> "wait-idle ${render(command.timeout)}"
    is Command.Sleep -> "sleep ${render(command.duration)}"
    Command.State -> "state"
    Command.Reply -> "reply"
}

private fun renderAction(action: Command.Action): String = when (action) {
    is Command.SelectPersona -> "persona ${action.persona.name.lowercase()}"
    Command.NewConversation -> "new-conversation"
    is Command.Open -> "open ${quote(action.title)}"
    is Command.Send if action.instant -> "send $INSTANT_OPTION ${quote(action.text)}"
    is Command.Send -> "send ${quote(action.text)}"
    Command.PrepareClaim -> "prepare-claim"
    Command.ConfirmClaim -> "confirm-claim"
    Command.Approve -> "approve"
    Command.Deny -> "deny"
}

/** Renders [duration] in the syntax of the command line, in the largest unit that keeps it whole. */
fun render(duration: Duration): String {
    val millis = duration.inWholeMilliseconds
    return when {
        millis != 0L && millis % MILLIS_PER_MINUTE == 0L -> "${millis / MILLIS_PER_MINUTE}m"
        millis != 0L && millis % MILLIS_PER_SECOND == 0L -> "${millis / MILLIS_PER_SECOND}s"
        else -> "${millis}ms"
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND

private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
