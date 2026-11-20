package dev.frederikpietzko.springaidemo.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.choice

/** `kaiju seed [reset|desk-queue|back-office]`: applies `core-mcp`'s seed sets to the compose database `core`. */
class SeedCommand(private val cli: Cli) : CoreCliktCommand("seed") {
    private val set by argument(
        "set",
        help = "reset, desk-queue or back-office; without a name: reset, then desk-queue",
    )
        .choice(SeedSet.entries.associateBy { it.setName })
        .optional()

    override fun help(context: Context): String =
        "Puts the core database of the compose PostgreSQL into the demo state: `reset` restores the scenario " +
            "claims, `desk-queue` adds a queue of claims, `back-office` adds claim 4810 for the adjuster (only by " +
            "name). The SQL is core-mcp's, bundled into this executable."

    override fun run() {
        for (each in set?.let { [it] } ?: SeedSet.defaults) {
            when (val result = cli.seeds.apply(each)) {
                SeedResult.Applied -> cli.console.outLine("seeded ${each.setName}")
                is SeedResult.Failed -> {
                    cli.console.errLine(result.message)
                    throw ProgramResult(EXIT_REFUSED)
                }
            }
        }
    }
}
