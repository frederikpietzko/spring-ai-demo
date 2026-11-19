package dev.frederikpietzko.springaidemo.backend.guardrail

import org.springframework.ai.decision.DecisionModel
import org.springframework.ai.decision.question.YesNoQuestion
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.stereotype.Component

/** A short, neutral question: only whether the model answers matters, not what it answers. */
private val PROBE: Map<String, YesNoQuestion> = mapOf(
    "probe" to YesNoQuestion.builder()
        .text("Is the text a greeting?")
        .whenYes("It greets someone.")
        .whenNo("It does not greet anyone.")
        .build(),
)

/**
 * The health of the claimant chat's guardrail (component `decisionModel`): asks the [DecisionModel] one short question.
 * The guardrail fails closed, so while the model cannot answer (an Ollama older than 0.40.4, which has no decision
 * endpoint, or `d1-omni:600m` not pulled) every claimant message ends in the generic error event. Any answer, a
 * refusal included, is up; a failed call is down, and with it the backend's overall health. It costs one small model
 * call per health request.
 */
@Component
class DecisionModelHealthIndicator(private val decisionModel: DecisionModel) :
    AbstractHealthIndicator("The decision model of the guardrail did not answer") {
    override fun doHealthCheck(builder: Health.Builder) {
        val _ = decisionModel.call("Hello", PROBE)
        builder.up()
    }
}
