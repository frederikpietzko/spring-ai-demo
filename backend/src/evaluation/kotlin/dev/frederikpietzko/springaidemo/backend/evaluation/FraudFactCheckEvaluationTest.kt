package dev.frederikpietzko.springaidemo.backend.evaluation

import dev.dokimos.core.EvalTestCaseParam.ACTUAL_OUTPUT
import dev.dokimos.core.EvalTestCaseParam.INPUT
import dev.dokimos.core.ExperimentResult
import dev.dokimos.core.JudgeLM
import dev.dokimos.kotlin.core.inputAs
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
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * What the judge is asked. A finding typically compares the claim with a bulletin ("the same place and time as claim
 * 4713") or the bulletins with each other ("no bulletin places the kaiju elsewhere"), so the criteria say that a
 * comparison counts. A faithfulness check splits such a finding into loose facts and loses the comparison.
 */
private const val SUPPORTED_BY_DOCUMENTS = """
    The input holds the record of an insurance claim and every sighting bulletin (each header holds the bulletin id,
    the observation time and the place). The actual output lists the findings of a fraud check, one per line, each
    starting with the id of the bulletin it cites. Score 1 when every finding cites a bulletin in the input and every
    fact it states can be read from the documents, including what follows from comparing them: the same or a different
    place, time or kaiju class, a distance a bulletin names, or that no bulletin says otherwise. Score 0 when a finding
    states something the documents contradict or that no document contains.
"""

/**
 * The real fraud check assesses the three seed claims; a judge model checks the cited findings of each against the
 * claim's record and every bulletin. The same experiment with a false finding is the negative control. A failure
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
    fun `every cited finding is supported by the bulletins`() {
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

    /** Judges the [evidence] cited for each of the [claims] against the claim's record and every bulletin. */
    private fun findings(vararg claims: Long, evidence: (claim: Long) -> List<Evidence>): ExperimentResult =
        experiment {
            name = "fraud-findings"
            dataset {
                for (claim in claims) {
                    example {
                        input = documents(claim)
                        input("claim", claim)
                    }
                }
            }
            task { example ->
                val cited = evidence(checkNotNull(example.inputAs<Long>("claim")))
                mapOf("output" to cited.joinToString("\n") { "${it.bulletinId}: ${it.finding}" })
            }
            evaluators {
                llmJudge(judge) {
                    name = "Findings supported"
                    criteria = SUPPORTED_BY_DOCUMENTS.trimIndent()
                    params(INPUT, ACTUAL_OUTPUT)
                    threshold = 0.8
                }
            }
        }.run()

    private fun assessed(claimNumber: Long): FraudAssessment =
        assertIs<FraudCheckResult.Assessed>(fraudCheck.assess(claimNumber), "Claim $claimNumber: unreadable").assessment

    /** The claim's facts, then the complete text of every bulletin file. */
    private fun documents(claimNumber: Long): String {
        val claim = checkNotNull(seedClaims.getClaim(claimNumber)) { "No seed claim $claimNumber" }
        val facts = "Claim ${claim.claimNumber}: kaiju damage claimed in district ${claim.district} at " +
            "${claim.incidentAt}, kaiju class ${claim.kaijuClass}."
        val bulletins = PathMatchingResourcePatternResolver().getResources("classpath:corpus/sighting-bulletins/*.md")
            .sortedBy { it.filename }
            .joinToString("\n\n") { it.getContentAsString(Charsets.UTF_8) }
        return "Claim record:\n$facts\n\nSighting bulletins:\n$bulletins"
    }
}
