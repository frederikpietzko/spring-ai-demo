package dev.frederikpietzko.springaidemo.backend.evaluation

import dev.dokimos.core.ExperimentResult
import dev.dokimos.core.JudgeLM
import dev.dokimos.kotlin.dsl.experiment
import dev.frederikpietzko.springaidemo.backend.fraud.Evidence
import dev.frederikpietzko.springaidemo.backend.fraud.FraudAssessment
import dev.frederikpietzko.springaidemo.backend.fraud.FraudCheck
import dev.frederikpietzko.springaidemo.backend.fraud.FraudCheckResult
import dev.frederikpietzko.springaidemo.backend.fraud.FraudVerdict
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.fail

/**
 * The real fraud check assesses the three seed claims; a judge model checks every cited finding against the claim's
 * record and the full text of each cited bulletin (its header holds the time and place): every fact a finding states
 * must be backed (faithfulness 1.0). The same experiment with a false finding is the negative control. A failure
 * message is the experiment's Markdown report with the judge's reasons.
 */
@RealModelEvaluation
class FraudFactCheckEvaluationTest {
    @Autowired
    private lateinit var fraudCheck: FraudCheck

    @Autowired
    private lateinit var seedClaims: SeedClaims

    @Autowired
    private lateinit var judge: JudgeLM

    @Test
    fun `every cited finding is supported by the cited bulletin`() {
        val result = findings(4711, 4712, 4713) { claim -> assessed(claim).evidence }

        assertEquals(1.0, result.passRate(), result.toMarkdown())
    }

    @Test
    fun `a false finding fails the judge`() {
        val result = findings(4711) { listOf(Evidence("SB-102", "places the kaiju at the harbour at 15:00")) }

        assertEquals(0.0, result.passRate(), result.toMarkdown())
    }

    @Test
    fun `the harbour claim is flagged`() {
        val assessment = assessed(4711)

        assertEquals(FraudVerdict.FLAGGED, assessment.verdict, "Claim 4711: $assessment")
    }

    @ParameterizedTest
    @ValueSource(longs = [4712, 4713])
    fun `the clean claims are not flagged`(claimNumber: Long) {
        val assessment = assessed(claimNumber)

        assertNotEquals(FraudVerdict.FLAGGED, assessment.verdict, "Claim $claimNumber: $assessment")
    }

    /** Judges the [evidence] cited for each of the [claims] against the claim's record and the cited bulletins. */
    private fun findings(vararg claims: Long, evidence: (claim: Long) -> List<Evidence>): ExperimentResult =
        experiment {
            name = "fraud-findings"
            dataset {
                for (claim in claims) example { input = "$claim" }
            }
            task { example ->
                val claim = example.input().toLong()
                val cited = evidence(claim)
                mapOf(
                    "output" to cited.joinToString("\n") { "${it.bulletinId}: ${it.finding}" },
                    "context" to documents(claim, cited),
                )
            }
            evaluators {
                faithfulness(judge) {
                    name = "Findings supported"
                    threshold = 1.0
                }
            }
        }.run()

    private fun assessed(claimNumber: Long): FraudAssessment =
        assertIs<FraudCheckResult.Assessed>(fraudCheck.assess(claimNumber), "Claim $claimNumber: unreadable").assessment

    /** The claim's facts and the complete text of each cited bulletin file; a cited id without a file fails. */
    private fun documents(claimNumber: Long, cited: List<Evidence>): List<String> {
        val claim = checkNotNull(seedClaims.getClaim(claimNumber)) { "No seed claim $claimNumber" }
        val facts = "Claim ${claim.claimNumber}: kaiju damage claimed in district ${claim.district} at " +
            "${claim.incidentAt}, kaiju class ${claim.kaijuClass}."
        val bulletins = cited.map { it.bulletinId }.distinct().map { id ->
            val bulletin = ClassPathResource("corpus/sighting-bulletins/$id.md")
            if (!bulletin.exists()) fail("Claim $claimNumber cites bulletin $id, which does not exist")
            bulletin.getContentAsString(Charsets.UTF_8)
        }
        return [facts] + bulletins
    }
}
