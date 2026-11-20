package dev.frederikpietzko.springaidemo.cli

import com.github.ajalt.clikt.core.CoreCliktCommand
import dev.frederikpietzko.springaidemo.cli.fakes.RecordingConsole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelpTest {
    private val console = RecordingConsole()

    @Test
    fun `root help lists the commands`() {
        val exit = kaiju(["--help"], mapOf(), console)

        assertEquals(0, exit)
        val listed = commandNamesIn(console.out)
        assertTrue(listed.containsAll(EXPECTED), "missing: ${EXPECTED - listed}")
    }

    @Test
    fun `no arguments prints the help`() {
        val _ = kaiju([], mapOf(), console)

        assertTrue("Usage:" in console.out + console.err, console.out + console.err)
    }

    @Test
    fun `every command describes itself`() {
        val paths = commandPaths(kaijuCommand(mapOf(), RecordingConsole()), [])

        val results = paths.map { path ->
            val out = RecordingConsole()
            val exit = kaiju(path + "--help", mapOf(), out)
            HelpResult(path, exit, out.out)
        }

        assertTrue(paths.map { it.first() }.containsAll(EXPECTED), paths.toString())
        val failing = results.filterNot { it.exit == 0 && "Usage:" in it.out && hasDescription(it.out) }
        assertEquals([], failing.map { it.path })
    }

    /** The plain-text help holds the description between the usage line and the first section. */
    private fun hasDescription(out: String): Boolean =
        out.substringAfter("Usage:").lines().drop(1).takeWhile { !it.endsWith(":") }.any { it.isNotBlank() }

    private fun commandPaths(command: CoreCliktCommand, prefix: List<String>): List<List<String>> =
        command.registeredSubcommands().flatMap { sub ->
            val path = prefix + sub.commandName
            [path] + commandPaths(sub, path)
        }

    private fun commandNamesIn(help: String): Set<String> =
        help.substringAfter("Commands:").lines()
            .mapNotNull { it.trim().split(" ").firstOrNull()?.takeIf(String::isNotBlank) }
            .toSet()

    private data class HelpResult(val path: List<String>, val exit: Int, val out: String)

    private companion object {
        val EXPECTED = setOf(
            "persona", "new-conversation", "open", "send", "prepare-claim", "confirm-claim", "approve", "deny",
            "typing-speed", "highlight", "wait-idle", "sleep", "reply", "state", "seed", "demo",
        )
    }
}
