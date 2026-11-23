package dev.frederikpietzko.springaidemo.backend.evaluation

import dev.dokimos.core.EvalTestCaseParam.ACTUAL_OUTPUT
import dev.dokimos.core.EvalTestCaseParam.INPUT
import dev.dokimos.core.ExperimentResult
import dev.dokimos.core.JudgeLM
import dev.dokimos.kotlin.dsl.experiment
import dev.frederikpietzko.springaidemo.backend.rag.CoverageQuestions
import org.junit.jupiter.api.Test
import org.springframework.ai.rag.Query
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

/**
 * The real coverage service answers fixed questions; a judge model checks each answer against the policy wording
 * retrieved for the question: every statement must be backed by a clause (faithfulness), and the answer must address
 * the question. The same experiment with an unrelated answer is the negative control. A failure message is the
 * experiment's Markdown report: every question with its scores and the judge's reasons.
 */
@RealModelEvaluation
class CoverageEvaluationTest {
    @Autowired
    private lateinit var coverageQuestions: CoverageQuestions

    @Autowired
    private lateinit var policyWordingRetriever: VectorStoreDocumentRetriever

    @Autowired
    private lateinit var judge: JudgeLM

    @Test
    fun `coverage answers are grounded in the policy wording`() {
        val result = coverage { question -> coverageQuestions.answer(question).text }

        assertEquals(1.0, result.passRate(), result.toMarkdown())
    }

    @Test
    fun `an unrelated answer fails the judge`() {
        val result = coverage { "The harbour opens at 9." }

        assertEquals(0.0, result.passRate(), result.toMarkdown())
    }

    /** Judges [answer] to each question against the clauses retrieved for that question. */
    private fun coverage(answer: (question: String) -> String): ExperimentResult = experiment {
        name = "coverage-answers"
        dataset {
            example { input = "A kaiju trampled my rooftop garden. Is that covered?" }
            example { input = "A class 5 kaiju damaged my house. Am I covered?" }
            example { input = "I aimed fireworks at the kaiju and it then smashed my car. Is the damage covered?" }
            example { input = "How much of a claim do I have to pay myself?" }
        }
        task { example ->
            mapOf(
                "output" to answer(example.input()),
                "context" to clauses(example.input()),
            )
        }
        evaluators {
            faithfulness(judge) { threshold = 0.8 }
            llmJudge(judge) {
                name = "Answers the question"
                criteria = "Does the answer address the claimant's question about their coverage?"
                params(INPUT, ACTUAL_OUTPUT)
            }
        }
    }.run()

    private fun clauses(question: String): List<String> =
        policyWordingRetriever.retrieve(Query(question)).mapNotNull { it.text }
}
