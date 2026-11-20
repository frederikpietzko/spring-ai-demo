package dev.frederikpietzko.springaidemo.controlprotocol

/** One step of a demo script. */
sealed interface ScriptStep {
    /** A control command for the desktop app. */
    data class Control(val command: Command) : ScriptStep

    /** `seed [set]`: applies the seed set [name], or all sets when `null`; run by the client, not the app. */
    data class Seed(val name: String?) : ScriptStep
}

/** A command line of a script: its 1-based [number] in the file, its [text] and the parsed [step]. */
data class ScriptLine(val number: Int, val text: String, val step: ScriptStep)

/** The outcome of [parseScript]. */
@MustUseReturnValues
sealed interface ScriptResult {
    /** Every line is valid; [lines] are the command lines in order. */
    data class Valid(val lines: List<ScriptLine>) : ScriptResult

    /** Line [lineNumber] ([text]) is the first invalid one; [message] names the problem. */
    data class Invalid(val lineNumber: Int, val text: String, val message: String) : ScriptResult
}

private const val SEED = "seed"
private const val COMMENT = "#"

/**
 * Parses a demo script: one line per step; blank lines and lines starting with `#` are skipped; every other line is
 * a control command ([parseCommand]) or `seed [set]` with a set out of [seedSets]. The first invalid line makes the
 * whole script [ScriptResult.Invalid], so nothing of a broken script runs.
 */
fun parseScript(text: String, seedSets: Collection<String>): ScriptResult {
    val lines = mutableListOf<ScriptLine>()
    for ((index, value) in text.lines().withIndex()) {
        val line = value.trim()
        if (line.isEmpty() || line.startsWith(COMMENT)) continue
        when (val step = parseStep(line, seedSets)) {
            is StepResult.Ok -> lines += ScriptLine(index + 1, line, step.step)
            is StepResult.Bad -> return ScriptResult.Invalid(index + 1, line, step.message)
        }
    }
    return ScriptResult.Valid(lines.toList())
}

private sealed interface StepResult {
    data class Ok(val step: ScriptStep) : StepResult

    data class Bad(val message: String) : StepResult
}

private fun parseStep(line: String, seedSets: Collection<String>): StepResult {
    val words = line.split(Regex("\\s+"))
    if (words.first() == SEED) return parseSeed(words.drop(1), seedSets)
    return when (val parsed = parseCommand(line)) {
        is ParseResult.Parsed -> StepResult.Ok(ScriptStep.Control(parsed.command))
        is ParseResult.Invalid -> StepResult.Bad(parsed.message)
    }
}

private fun parseSeed(args: List<String>, seedSets: Collection<String>): StepResult = when {
    args.isEmpty() -> StepResult.Ok(ScriptStep.Seed(null))
    args.size == 1 && args.single() in seedSets -> StepResult.Ok(ScriptStep.Seed(args.single()))
    args.size == 1 -> StepResult.Bad("unknown seed set '${args.single()}' (known: ${seedSets.joinToString(", ")})")
    else -> StepResult.Bad("'seed' takes at most one set (known: ${seedSets.joinToString(", ")})")
}
