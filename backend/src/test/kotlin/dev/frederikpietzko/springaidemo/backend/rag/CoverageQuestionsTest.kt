package dev.frederikpietzko.springaidemo.backend.rag

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.rag.Query
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@IntegrationTest
class CoverageQuestionsTest {
    @Autowired
    private lateinit var coverageQuestions: CoverageQuestions

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var policyWordingRetriever: VectorStoreDocumentRetriever

    @Test
    fun `the retrieved wording reaches the model with its label, next to the question`() {
        fake.reply("Not covered.")

        coverageQuestions.answer(CLASS_5_QUESTION)

        val userText = fake.prompts.single().userMessage.text.orEmpty()
        assertTrue(userText.contains("$ACT_OF_KAIJU_LABEL\n$ACT_OF_KAIJU_FIRST_SENTENCE"), userText)
        assertTrue(userText.contains(CLASS_5_QUESTION), userText)
    }

    @Test
    fun `the prompt asks to cite the labels for coverage questions and to answer anything else normally`() {
        fake.reply("Not covered.")

        coverageQuestions.answer(CLASS_5_QUESTION)

        val userText = fake.prompts.single().userMessage.text.orEmpty()
        assertTrue(userText.contains("cite the [labels] of the clauses you rely on"), userText)
        assertTrue(userText.contains("Otherwise answer normally."), userText)
    }

    @Test
    fun `the answer carries the reply and the retrieved clauses as citations`() {
        fake.reply("Not covered, see [exclusions.md - Act of Kaiju exclusion].")

        val (text, citations) = coverageQuestions.answer(CLASS_5_QUESTION)

        assertEquals("Not covered, see [exclusions.md - Act of Kaiju exclusion].", text)
        assertTrue(Citation("exclusions.md", "Act of Kaiju exclusion") in citations, citations.toString())
    }

    @Test
    fun `a question that retrieves no wording reaches the model unchanged and has no citations`() {
        assertEquals([], policyWordingRetriever.retrieve(Query(NO_WORDING_QUESTION)), "precondition: nothing retrieved")
        fake.reply("Hello, how can I help?")

        val (text, citations) = coverageQuestions.answer(NO_WORDING_QUESTION)

        assertEquals(NO_WORDING_QUESTION, fake.prompts.single().userMessage.text)
        assertEquals("Hello, how can I help?", text)
        assertEquals([], citations)
    }

    private companion object {
        const val CLASS_5_QUESTION = "Is my car covered after a class 5 kaiju event?"
        /** Shares no word (and, under the fake embedding model, no hash bucket) with any clause. */
        const val NO_WORDING_QUESTION = "Hello there"
        const val ACT_OF_KAIJU_LABEL = "[exclusions.md - Act of Kaiju exclusion]"
        const val ACT_OF_KAIJU_FIRST_SENTENCE =
            "The Act of Kaiju exclusion: damage from a class 5 kaiju event is not covered."
    }
}
