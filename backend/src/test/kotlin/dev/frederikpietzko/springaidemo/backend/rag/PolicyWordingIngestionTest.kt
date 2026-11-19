package dev.frederikpietzko.springaidemo.backend.rag

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The ingestion ran at startup of the shared context (with the fake embedding model); rows are read back by SQL. */
@IntegrationTest
class PolicyWordingIngestionTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var ingestion: PolicyWordingIngestion

    @Autowired
    private lateinit var vectorStore: VectorStore

    @Test
    fun `every chunk carries its corpus, source, title and a stable id naming its source`() {
        val chunks = policyWordingChunks()

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.title != null }, chunks.toString())
        assertTrue(chunks.all { it.source != null }, chunks.toString())
        assertTrue(chunks.all { (id, source) -> id.startsWith("policy-wording/$source#") }, chunks.toString())
    }

    @Test
    fun `every policy wording document is ingested`() {
        val sources = policyWordingChunks().mapNotNull { it.source }.toSet()

        assertEquals(["claims.md", "coverage.md", "exclusions.md"], sources)
    }

    @Test
    fun `ingesting again keeps the same chunks`() {
        val before = policyWordingChunks().map { it.id }

        val ingested = ingestion.ingest()

        assertEquals(before.size, ingested)
        assertEquals(before, policyWordingChunks().map { it.id })
    }

    @Test
    fun `an embedding failure aborts the ingestion and stores nothing`() {
        val before = policyWordingChunks()
        val failing = PolicyWordingIngestion(vectorStore, FailingEmbeddingModel("embedding provider down"))

        val thrown = assertFailsWith<IllegalStateException> { failing.ingest() }

        assertEquals("embedding provider down", thrown.message)
        assertEquals(before, policyWordingChunks())
    }

    private fun policyWordingChunks(): List<StoredChunk> =
        jdbc.query(
            """
            SELECT id, metadata::jsonb ->> 'source' AS source, metadata::jsonb ->> 'title' AS title
            FROM vector_store WHERE metadata::jsonb ->> 'corpus' = 'policy-wording' ORDER BY id
            """.trimIndent(),
        ) { rs, _ -> StoredChunk(rs.getString("id"), rs.getString("source"), rs.getString("title")) }

    /** An embedding provider that is down: every request fails with an [IllegalStateException] carrying [message]. */
    private class FailingEmbeddingModel(private val message: String) : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse = error(message)

        override fun embed(document: Document): FloatArray = error(message)
    }

    private data class StoredChunk(val id: String, val source: String?, val title: String?)
}
