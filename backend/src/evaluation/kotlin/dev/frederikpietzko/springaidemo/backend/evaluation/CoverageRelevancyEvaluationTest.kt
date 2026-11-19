package dev.frederikpietzko.springaidemo.backend.evaluation

import dev.frederikpietzko.springaidemo.backend.rag.CoverageQuestions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.ai.chat.evaluation.RelevancyEvaluator
import org.springframework.ai.evaluation.EvaluationRequest
import org.springframework.ai.rag.Query
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GARDEN_QUESTION = "A kaiju trampled my rooftop garden. Is that covered?"

/**
 * The real coverage service answers fixed questions; a judge model decides whether each answer is in line with the
 * policy wording retrieved for the question. The evaluator gives no feedback, so the message carries the evidence.
 */
@RealModelEvaluation
class CoverageRelevancyEvaluationTest {
    @Autowired
    private lateinit var coverageQuestions: CoverageQuestions

    @Autowired
    private lateinit var policyWordingRetriever: VectorStoreDocumentRetriever

    @Autowired
    private lateinit var relevancy: RelevancyEvaluator

    @ParameterizedTest
    @ValueSource(
        strings = [
            GARDEN_QUESTION,
            "A class 5 kaiju damaged my house. Am I covered?",
            "I aimed fireworks at the kaiju and it then smashed my car. Is the damage covered?",
            "How much of a claim do I have to pay myself?",
        ],
    )
    fun `the coverage answer is in line with the policy wording`(question: String) {
        val clauses = policyWordingRetriever.retrieve(Query(question))
        val answer = coverageQuestions.answer(question)

        val evaluation = relevancy.evaluate(EvaluationRequest(question, clauses, answer.text))

        assertTrue(evaluation.isPass, "Judged not relevant.\nQuestion: $question\nAnswer: ${answer.text}")
    }

    @Test
    fun `an unrelated answer fails the evaluator`() {
        val unrelated = "The harbour opens at 9."
        val clauses = policyWordingRetriever.retrieve(Query(GARDEN_QUESTION))

        val evaluation = relevancy.evaluate(EvaluationRequest(GARDEN_QUESTION, clauses, unrelated))

        assertFalse(evaluation.isPass, "Judged relevant.\nQuestion: $GARDEN_QUESTION\nAnswer: $unrelated")
    }
}
