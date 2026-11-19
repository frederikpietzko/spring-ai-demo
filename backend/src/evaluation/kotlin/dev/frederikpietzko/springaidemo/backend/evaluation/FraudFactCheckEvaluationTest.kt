package dev.frederikpietzko.springaidemo.backend.evaluation

import dev.frederikpietzko.springaidemo.backend.fraud.Evidence
import dev.frederikpietzko.springaidemo.backend.fraud.FraudAssessment
import dev.frederikpietzko.springaidemo.backend.fraud.FraudCheck
import dev.frederikpietzko.springaidemo.backend.fraud.FraudCheckResult
import dev.frederikpietzko.springaidemo.backend.fraud.FraudVerdict
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator
import org.springframework.ai.document.Document
import org.springframework.ai.evaluation.EvaluationRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real fraud check assesses the three seed claims; a judge model checks every cited finding against the claim's
 * facts and the full text of the cited bulletin (its header holds the time and place). The evaluator gives no
 * feedback, so the messages carry the claim, the bulletin id, the finding and the judge's raw answer ([JudgeAnswers]).
 */
@RealModelEvaluation
class FraudFactCheckEvaluationTest {
    @Autowired
    private lateinit var fraudCheck: FraudCheck

    @Autowired
    private lateinit var seedClaims: SeedClaims

    @Autowired
    private lateinit var factChecking: FactCheckingEvaluator

    @Autowired
    private lateinit var judgeAnswers: JudgeAnswers

    @ParameterizedTest
    @ValueSource(longs = [4711, 4712, 4713])
    fun `every cited finding is supported by the cited bulletin`(claimNumber: Long) {
        val assessment = assessed(claimNumber)

        val unsupported = assessment.evidence.mapNotNull { unsupported(claimNumber, it) }

        assertTrue(unsupported.isEmpty(), "Claim $claimNumber, findings not supported by their bulletin: $unsupported")
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

    @Test
    fun `a false finding fails the evaluator`() {
        val falseFinding = "SB-102: places the kaiju at the harbour at 15:00"

        val evaluation = factChecking.evaluate(EvaluationRequest(documents(4711, "SB-102"), falseFinding))

        assertFalse(
            evaluation.isPass,
            "Judged supported.\nClaim: 4711\nFinding: $falseFinding\nJudge: ${judgeAnswers.last}",
        )
    }

    private fun assessed(claimNumber: Long): FraudAssessment =
        assertIs<FraudCheckResult.Assessed>(fraudCheck.assess(claimNumber), "Claim $claimNumber: unreadable").assessment

    /** `null` when the judge supports [evidence], else the evidence with the judge's raw answer. */
    private fun unsupported(claimNumber: Long, evidence: Evidence): String? {
        val (bulletinId, finding) = evidence
        val claim = "$bulletinId: $finding"
        val evaluation = factChecking.evaluate(EvaluationRequest(documents(claimNumber, bulletinId), claim))
        return if (evaluation.isPass) null else "$evidence (judge: ${judgeAnswers.last})"
    }

    /** The claim's facts and the complete text of the cited bulletin file; a cited id without a file fails. */
    private fun documents(claimNumber: Long, bulletinId: String): List<Document> {
        val claim = checkNotNull(seedClaims.getClaim(claimNumber)) { "No seed claim $claimNumber" }
        val facts = "Claim ${claim.claimNumber}: kaiju damage claimed in district ${claim.district} at " +
            "${claim.incidentAt}, kaiju class ${claim.kaijuClass}."
        val bulletin = ClassPathResource("corpus/sighting-bulletins/$bulletinId.md")
        if (!bulletin.exists()) fail("Claim $claimNumber cites bulletin $bulletinId, which does not exist")
        return [Document(facts), Document(bulletin.getContentAsString(Charsets.UTF_8))]
    }
}
