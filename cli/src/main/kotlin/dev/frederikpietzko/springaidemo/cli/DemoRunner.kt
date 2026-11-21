package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.controlprotocol.ScriptLine
import dev.frederikpietzko.springaidemo.controlprotocol.ScriptStep
import dev.frederikpietzko.springaidemo.controlprotocol.render
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlin.time.Duration

/** The outcome of one script line. */
@MustUseReturnValues
sealed interface LineOutcome {
    /** The line succeeded; [output] is what it printed. */
    data class Ok(val output: String) : LineOutcome

    /** The line failed with [message]; a direct command would exit with [exitCode]. */
    data class Failed(val message: String, val exitCode: Int) : LineOutcome
}

/** A line of a script that failed, with its [outcome]. */
data class LineFailure(val line: ScriptLine, val outcome: LineOutcome.Failed)

/**
 * The one executor of script lines: control commands go to the desktop app through [control], `seed` lines to
 * [seeds]. Used by `demo run` in the CLI process and by the daemon. A paced run waits between lines through [pause],
 * starting from [pacing] (see [Pacing.gap]); `pace` and `read-pause` lines change it for the rest of the run.
 */
class DemoRunner(
    private val control: ControlClient,
    private val seeds: SeedRunner,
    private val pacing: Pacing = Pacing.NONE,
    private val pause: suspend (Duration) -> Unit = { delay(it) },
) {
    /** Runs one [line] and waits until it has finished. */
    suspend fun execute(line: ScriptLine): LineOutcome = when (val step = line.step) {
        is ScriptStep.Control -> when (val result = control.send(render(step.command))) {
            is ControlResult.Ok -> LineOutcome.Ok(result.message)
            is ControlResult.Refused, is ControlResult.Unreachable ->
                LineOutcome.Failed(result.message, result.exitCode)
        }
        is ScriptStep.Seed -> seed(step.name)
        is ScriptStep.Pace, is ScriptStep.ReadPause -> LineOutcome.Ok("")
    }

    /**
     * Runs [lines] in order, calling [beforeLine] before each (step mode suspends there) and [afterLine] after it;
     * when [paced], pauses after a succeeded line that is not the last. Stops at the first failing line and answers
     * it, or `null` when every line succeeded.
     */
    suspend fun run(
        lines: List<ScriptLine>,
        paced: Boolean = true,
        beforeLine: suspend (ScriptLine) -> Unit = {},
        afterLine: (ScriptLine, LineOutcome) -> Unit = { _, _ -> },
    ): LineFailure? {
        var current = pacing
        for ([index, line] in lines.withIndex()) {
            beforeLine(line)
            val outcome = execute(line)
            afterLine(line, outcome)
            if (outcome is LineOutcome.Failed) return LineFailure(line, outcome)
            current = current.after(line.step)
            val next = lines.getOrNull(index + 1) ?: break
            val gap = current.gap(line.step, next.step)
            if (paced && gap > Duration.ZERO) pause(gap)
        }
        return null
    }

    private suspend fun seed(name: String?): LineOutcome {
        val sets = name?.let { listOfNotNull(SeedSet.named(it)) } ?: SeedSet.defaults
        val applied = mutableListOf<String>()
        for (set in sets) {
            when (val result = runInterruptible(Dispatchers.IO) { seeds.apply(set) }) {
                SeedResult.Applied -> applied += "seeded ${set.setName}"
                is SeedResult.Failed -> return LineOutcome.Failed(result.message, EXIT_REFUSED)
            }
        }
        return LineOutcome.Ok(applied.joinToString("\n"))
    }
}
