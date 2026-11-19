package dev.frederikpietzko.springaidemo.backend.fraud

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

/** The bulletin ingestion ran at startup of the shared context (fake embedding model); rows are read back by SQL. */
@IntegrationTest
class SightingBulletinIngestionTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var ingestion: SightingBulletinIngestion

    @Autowired
    private lateinit var vectorStore: VectorStore

    @Test
    fun `bulletins carry their metadata`() {
        val rows = bulletinRows()

        assertTrue(rows.all { it.id.startsWith("sighting-bulletin/") }, rows.toString())
        assertTrue(rows.all { it.place != null && it.observedAtEpochSecond != null }, rows.toString())
        assertEquals(readSightingBulletins().map { it.id }, rows.map { it.bulletinId })
    }

    @Test
    fun `ingesting again adds nothing`() {
        val before = bulletinRows().map { it.id }

        val ingested = ingestion.ingest()

        assertEquals(before.size, ingested)
        assertEquals(before, bulletinRows().map { it.id })
    }

    @Test
    fun `an embedding failure aborts the ingestion and stores nothing`() {
        val before = bulletinRows()
        val failing = SightingBulletinIngestion(vectorStore, FailingEmbeddingModel("embedding provider down"))

        val thrown = assertFailsWith<IllegalStateException> { failing.ingest() }

        assertEquals("embedding provider down", thrown.message)
        assertEquals(before, bulletinRows())
    }

    private fun bulletinRows(): List<StoredBulletin> =
        jdbc.query(
            """
            SELECT id, metadata::jsonb ->> 'bulletinId' AS bulletin_id, metadata::jsonb ->> 'place' AS place,
                   metadata::jsonb ->> 'observedAtEpochSecond' AS observed_at
            FROM vector_store WHERE metadata::jsonb ->> 'corpus' = 'sighting-bulletin' ORDER BY id
            """.trimIndent(),
        ) { rs, _ ->
            StoredBulletin(
                id = rs.getString("id"),
                bulletinId = rs.getString("bulletin_id"),
                place = rs.getString("place"),
                observedAtEpochSecond = rs.getString("observed_at")?.toLongOrNull(),
            )
        }

    /** An embedding provider that is down: every request fails with an [IllegalStateException] carrying [message]. */
    private class FailingEmbeddingModel(private val message: String) : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse = error(message)

        override fun embed(document: Document): FloatArray = error(message)
    }

    private data class StoredBulletin(
        val id: String,
        val bulletinId: String?,
        val place: String?,
        val observedAtEpochSecond: Long?,
    )
}
