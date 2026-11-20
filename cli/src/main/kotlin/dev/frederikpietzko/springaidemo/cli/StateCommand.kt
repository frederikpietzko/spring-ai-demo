package dev.frederikpietzko.springaidemo.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.controlprotocol.Command
import dev.frederikpietzko.springaidemo.controlprotocol.StateSnapshot

/** `kaiju state [--json]`: what the screen shows, as a readable summary or the endpoint's JSON. */
class StateCommand(private val cli: Cli) : CoreCliktCommand("state") {
    private val json by option("--json", help = "print the JSON snapshot unchanged, for agents").flag()

    override fun help(context: Context): String =
        "Prints what the desktop app shows: persona, streaming, input, highlighted control, pending confirmation, " +
            "todos, conversations, messages and enabled controls."

    override fun run() {
        val body = cli.sendControl(Command.State)
        if (json) {
            cli.console.outLine(body)
        } else {
            summary(ChatJson.decodeFromString<StateSnapshot>(body)).forEach(cli.console::outLine)
        }
    }
}
