package dev.frederikpietzko.springaidemo.backend.fraud

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeEmbeddingModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.document.Document
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.vectorstore.EmbeddedDocument
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The search tool over the bulletins ingested at startup: real pgvector filters, the fake embedding model. */
@IntegrationTest
class SightingBulletinSearchTest {
    @Autowired
    private lateinit var search: SightingBulletinSearch

    @Autowired
    private lateinit var vectorStore: VectorStore

    @Autowired
    private lateinit var embeddingModel: FakeEmbeddingModel

    @Test
    fun `window and place both filter`() {
        val hits = search.searchSightingBulletins(QUERY, AFTERNOON_FROM, AFTERNOON_TO, "SALTMARSH")

        assertEquals(["SB-102", "SB-103"], hits.map { it.bulletinId }.sorted())
    }

    @Test
    fun `a place without bulletins in the window is empty`() {
        val hits = search.searchSightingBulletins(QUERY, AFTERNOON_FROM, AFTERNOON_TO, "HARBOUR")

        assertEquals([], hits)
    }

    @Test
    fun `no place searches everywhere in the window`() {
        val hits = search.searchSightingBulletins(QUERY, AFTERNOON_FROM, AFTERNOON_TO, null)

        assertEquals(["SB-102", "SB-103", "SB-104"], hits.map { it.bulletinId }.sorted())
    }

    @Test
    fun `a wider window finds earlier and later bulletins at one place`() {
        val from = Instant.parse("2026-11-18T00:00:00Z")
        val to = Instant.parse("2026-11-19T23:59:59Z")

        val hits = search.searchSightingBulletins(QUERY, from, to, "HARBOUR")

        assertEquals(["SB-101", "SB-105"], hits.map { it.bulletinId }.sorted())
    }

    @Test
    fun `bounds are inclusive`() {
        val observed = Instant.parse("2026-11-18T14:45:00Z")

        val hits = search.searchSightingBulletins(QUERY, observed, observed, null)

        assertEquals(["SB-102"], hits.map { it.bulletinId })
    }

    @Test
    fun `a blank place means everywhere`() {
        val everywhere = search.searchSightingBulletins(QUERY, AFTERNOON_FROM, AFTERNOON_TO, null)

        val blank = search.searchSightingBulletins(QUERY, AFTERNOON_FROM, AFTERNOON_TO, "")

        assertEquals(everywhere, blank)
    }

    @Test
    fun `rows of another corpus are never returned`() {
        val foreign = Document.builder()
            .id(FOREIGN_ID)
            .text("A kaiju sighting in the policy wording.")
            .metadata("corpus", "policy-wording")
            .metadata("bulletinId", "SB-FOREIGN")
            .metadata("place", "SALTMARSH")
            .metadata("observedAtEpochSecond", Instant.parse("2026-11-18T15:00:00Z").epochSecond)
            .build()
        vectorStore.upsert([EmbeddedDocument(foreign, embeddingModel.embed(foreign))])
        try {
            val hits = search.searchSightingBulletins(QUERY, AFTERNOON_FROM, AFTERNOON_TO, null)

            val bulletinIds = readSightingBulletins().map { it.id }
            assertTrue(hits.isNotEmpty())
            assertTrue(bulletinIds.containsAll(hits.map { it.bulletinId }), hits.toString())
        } finally {
            vectorStore.delete([FOREIGN_ID])
        }
    }

    @Test
    fun `hits carry what the model needs to cite`() {
        val saltmarsh = readSightingBulletins().single { it.id == "SB-102" }

        val hit = search.searchSightingBulletins(QUERY, AFTERNOON_FROM, AFTERNOON_TO, "SALTMARSH")
            .single { it.bulletinId == "SB-102" }

        assertEquals(
            SightingBulletinHit("SB-102", "SALTMARSH", Instant.parse("2026-11-18T14:45:00Z"), saltmarsh.text),
            hit,
        )
    }

    @Test
    fun `the tool result shows the observation time as an ISO-8601 UTC instant`() {
        val result: String = ToolCallbacks.from(search).single().call(
            """{"query":"$QUERY","from":"2026-11-18T14:45:00Z","to":"2026-11-18T14:45:00Z"}""",
        )

        assertTrue(result.contains("SB-102"), result)
        assertTrue(result.contains("2026-11-18T14:45:00Z"), result)
    }

    @Test
    fun `query and window are required, the place is optional`() {
        val schema = JsonMapper.builder().build().readTree(toolDefinition().inputSchema())

        val required = schema.get("required").values().map { it.asString() }.toSet()

        val expected: Set<String> = ["query", "from", "to"]
        assertEquals(expected, required)
    }

    @Test
    fun `the description invites a second search and lists the neighbouring town`() {
        val definition = toolDefinition()
        val schema = JsonMapper.builder().build().readTree(definition.inputSchema())

        val placeDescription: String = schema.get("properties").get("place").get("description").asString()

        val description: String = definition.description()
        assertTrue(description.contains("rephrased"), description)
        assertTrue(description.contains("another place"), description)
        assertTrue(placeDescription.contains("SALTMARSH"), placeDescription)
    }

    private fun toolDefinition() = ToolCallbacks.from(search).single().toolDefinition

    private companion object {
        const val QUERY = "kaiju sighting"
        const val FOREIGN_ID = "policy-wording/sighting-bulletin-search-test#0"
        val AFTERNOON_FROM: Instant = Instant.parse("2026-11-18T13:00:00Z")
        val AFTERNOON_TO: Instant = Instant.parse("2026-11-18T17:00:00Z")
    }
}
