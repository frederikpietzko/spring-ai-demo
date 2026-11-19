package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.decision.DecisionModel
import org.springframework.ai.decision.DecisionPrompt
import org.springframework.ai.decision.DecisionResponse
import org.springframework.ai.decision.answer.DecisionAnswer
import org.springframework.ai.decision.answer.RefusalAnswer
import org.springframework.ai.decision.answer.YesNoAnswer
import org.springframework.ai.decision.input.TextInput
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scripted decision model for tests. By default it answers every question with probability 0 (not flagged), except
 * for an input text that contains [phrase] (case-insensitive), which it answers with probability 1. A test can switch
 * the phrase off (`null`), make every question refused ([Mode.REFUSE]) or make the call fail ([Mode.FAIL]). Every
 * input text is recorded, also for a failing call. State is thread-safe: the guardrail calls it on Reactor threads.
 * [reset] (run before each test by [FakeChatModelReset]) restores the defaults.
 */
class FakeDecisionModel : DecisionModel {
    /** How the fake answers. */
    enum class Mode { ANSWER, REFUSE, FAIL }

    private val received = CopyOnWriteArrayList<String>()

    /** The text that makes every answer a yes; `null` flags nothing. */
    @Volatile
    var phrase: String? = DEFAULT_PHRASE

    /** Answer, refuse every question or fail the call. */
    @Volatile
    var mode: Mode = Mode.ANSWER

    /** Every input text received so far, in order. */
    val inputs: List<String> get() = received.toList()

    /** Restores the default phrase and mode and forgets the recorded inputs. */
    fun reset() {
        phrase = DEFAULT_PHRASE
        mode = Mode.ANSWER
        received.clear()
    }

    override fun call(prompt: DecisionPrompt): DecisionResponse {
        val text = (prompt.instructions as? TextInput)?.text ?: error("The fake decides about text input only")
        received.add(text)
        val answer: DecisionAnswer = when (mode) {
            Mode.ANSWER -> YesNoAnswer.builder().probability(if (isFlagged(text)) YES else NO).build()
            Mode.REFUSE -> RefusalAnswer.builder().reason("scripted refusal").build()
            Mode.FAIL -> error("Scripted decision failure")
        }
        val response = DecisionResponse.builder(prompt)
        prompt.questions.keys.forEach { response.answer(it, answer) }
        return response.build()
    }

    private fun isFlagged(text: String): Boolean = phrase?.let { text.contains(it, ignoreCase = true) } ?: false

    companion object {
        /** The phrase the fake flags until a test changes it. */
        const val DEFAULT_PHRASE = "Ignore previous instructions"

        private const val YES = 1.0
        private const val NO = 0.0
    }
}
