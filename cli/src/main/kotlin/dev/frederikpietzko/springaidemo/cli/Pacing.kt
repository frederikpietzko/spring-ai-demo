package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.controlprotocol.Command
import dev.frederikpietzko.springaidemo.controlprotocol.ScriptStep
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How an unattended script run is paced: [pace] passes between two consecutive actions, [readPause] after each
 * succeeded `wait-idle`, so the audience can read the answer. A script changes both with `pace` and `read-pause`.
 */
data class Pacing(val pace: Duration, val readPause: Duration) {
    /** The pacing after [step] ran: a pacing line changes its value, every other step keeps both. */
    fun after(step: ScriptStep): Pacing = when (step) {
        is ScriptStep.Pace -> copy(pace = step.duration)
        is ScriptStep.ReadPause -> copy(readPause = step.duration)
        is ScriptStep.Control, is ScriptStep.Seed -> this
    }

    /**
     * The pause between the succeeded step [finished] and the [next] one: the read pause after `wait-idle`, the pace
     * between two actions, none otherwise.
     */
    fun gap(finished: ScriptStep, next: ScriptStep): Duration = when {
        finished.command is Command.WaitIdle -> readPause
        finished.command is Command.Action && next.command is Command.Action -> pace
        else -> Duration.ZERO
    }

    /** The pacings the CLI knows. */
    companion object {
        /** No pauses at all. */
        val NONE: Pacing = Pacing(Duration.ZERO, Duration.ZERO)

        /** The default of `kaiju demo run` and an unattended `kaiju demo start`: 1200 ms pace, 4 s read pause. */
        val DEMO: Pacing = Pacing(pace = 1200.milliseconds, readPause = 4.seconds)
    }
}

private val ScriptStep.command: Command? get() = (this as? ScriptStep.Control)?.command
