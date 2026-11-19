package dev.frederikpietzko.springaidemo.backend.fraud

import com.fasterxml.jackson.annotation.JsonPropertyDescription

/** The outcome of comparing a claim with the sighting bulletins. */
enum class FraudVerdict { CLEAR, FLAGGED, INCONCLUSIVE }

/** One bulletin the verdict rests on and what it shows about the claim. */
data class Evidence(
    @field:JsonPropertyDescription("Id of a sighting bulletin, for example SB-101")
    val bulletinId: String,
    @field:JsonPropertyDescription("What the bulletin shows about the claim's place and incident time")
    val finding: String,
)

/** The fraud check's typed verdict (ROADMAP glossary: Fraud check), the model's structured output. */
data class FraudAssessment(
    @field:JsonPropertyDescription("FLAGGED only when a bulletin contradicts the claim, else CLEAR or INCONCLUSIVE")
    val verdict: FraudVerdict,
    @field:JsonPropertyDescription("The bulletins the verdict rests on")
    val evidence: List<Evidence>,
    @field:JsonPropertyDescription("Confidence in the verdict, from 0 to 1")
    val confidence: Double,
)

/** The result of a fraud check. */
sealed interface FraudCheckResult {
    /** The model replied with a readable [assessment]. */
    data class Assessed(val assessment: FraudAssessment) : FraudCheckResult

    /** The model's reply could not be read as a fraud assessment. */
    data object Unreadable : FraudCheckResult
}
