package dev.frederikpietzko.springaidemo.backend.rag

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeEmbeddingModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.document.Document
import org.springframework.ai.rag.Query
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever
import org.springframework.ai.vectorstore.EmbeddedDocument
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The production retrieval (filter, top K, cosine distance in pgvector) over the corpus ingested at startup. */
@IntegrationTest
class PolicyWordingRetrievalTest {
    @Autowired
    private lateinit var policyWordingRetriever: VectorStoreDocumentRetriever

    @Autowired
    private lateinit var vectorStore: VectorStore

    @Autowired
    private lateinit var embeddingModel: FakeEmbeddingModel

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a class 5 question finds the Act of Kaiju exclusion`() {
        val clauses = policyWordingRetriever.retrieve(Query(CLASS_5_QUESTION))

        assertTrue(clauses.size <= 4, clauses.toString())
        assertTrue(clauses.any { it.text.orEmpty().contains("Act of Kaiju exclusion") }, clauses.toString())
    }

    @Test
    fun `a question about provoking the kaiju finds the provoking exclusion`() {
        val clauses = policyWordingRetriever.retrieve(Query("Is the damage covered after provoking the kaiju?"))

        assertTrue(clauses.any { it.text.orEmpty().contains("provoking the kaiju exclusion") }, clauses.toString())
    }

    @Test
    fun `chunks of another corpus are not searched`() {
        val foreign = Document.builder()
            .id(FOREIGN_ID)
            .text("$CLASS_5_QUESTION A class 5 kaiju event was sighted.")
            .metadata("corpus", "sighting-bulletin")
            .build()
        vectorStore.upsert([EmbeddedDocument(foreign, embeddingModel.embed(foreign))])
        try {
            val clauses = policyWordingRetriever.retrieve(Query(CLASS_5_QUESTION))

            assertEquals(1, foreignRows())
            assertTrue(clauses.none { it.metadata["corpus"] == "sighting-bulletin" }, clauses.toString())
        } finally {
            vectorStore.delete([FOREIGN_ID])
        }
    }

    private fun foreignRows(): Int? =
        jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE id = ?", Int::class.java, FOREIGN_ID)

    private companion object {
        const val CLASS_5_QUESTION = "Is my car covered after a class 5 kaiju event?"
        const val FOREIGN_ID = "sighting-bulletin/policy-wording-retrieval-test#0"
    }
}
