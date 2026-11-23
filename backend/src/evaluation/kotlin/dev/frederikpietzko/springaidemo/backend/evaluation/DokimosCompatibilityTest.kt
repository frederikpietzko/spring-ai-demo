package dev.frederikpietzko.springaidemo.backend.evaluation

import dev.dokimos.core.EvalTestCaseParam.ACTUAL_OUTPUT
import dev.dokimos.core.EvalTestCaseParam.INPUT
import dev.dokimos.kotlin.dsl.llmJudge
import dev.dokimos.springai.SpringAiSupport
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.document.Document
import org.springframework.ai.evaluation.EvaluationRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * dokimos-spring-ai is built against Spring AI 1.1; this proves the parts the suite uses run on this project's 2.1
 * without a real model: the judge adapter over a `ChatClient.Builder` and the `EvaluationRequest` conversion.
 */
class DokimosCompatibilityTest {
    private val prompts = CopyOnWriteArrayList<String>()

    private val model = ChatModel { prompt ->
        prompts += prompt.userMessage.text.orEmpty()
        ChatResponse([Generation(AssistantMessage("""{"score": 1.0, "reason": "backed by the clause"}"""))])
    }

    @Test
    fun `a Spring AI chat client judges a dokimos evaluation`() {
        val evaluator = llmJudge(SpringAiSupport.asJudge(ChatClient.builder(model))) {
            name = "Answers the question"
            criteria = "Does the answer address the question?"
            params(INPUT, ACTUAL_OUTPUT)
        }
        val request = EvaluationRequest("Is my garden covered?", [Document("Gardens are covered.")], "Yes, it is.")

        val result = evaluator.evaluate(SpringAiSupport.toTestCase(request))

        assertTrue(result.success(), "Result: $result")
        assertEquals("backed by the clause", result.reason())
        assertTrue(prompts.single().contains("Yes, it is."), "Prompt: $prompts")
    }
}
