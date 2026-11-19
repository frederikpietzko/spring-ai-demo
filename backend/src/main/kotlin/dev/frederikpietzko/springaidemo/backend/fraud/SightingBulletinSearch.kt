package dev.frederikpietzko.springaidemo.backend.fraud

import org.springframework.ai.document.Document
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.stereotype.Component
import java.time.Instant

private const val TOP_K = 5

private const val SEARCH_DESCRIPTION = "Searches the official kaiju sighting bulletins: where a kaiju was seen at " +
    "what time. Returns at most five bulletins observed within the time window (both bounds included), at the place " +
    "if one is given, most similar to the query first. If nothing useful is found, search again with a rephrased " +
    "query or at another place, for example the neighbouring town."

private const val PLACE_DESCRIPTION = "Place of the sighting, exactly one of HARBOUR, OLD_TOWN, NEON_MARKET, " +
    "RIVERSIDE, HILLTOP or SALTMARSH (the neighbouring town, about 40 km up the coast). Leave it out to search " +
    "every place."

/** One bulletin found by the search: what the model needs to cite it as evidence. */
data class SightingBulletinHit(val bulletinId: String, val place: String, val observedAt: Instant, val text: String)

/**
 * Agentic RAG: retrieval as a tool. The model decides when to search, writes the query itself and picks the time
 * window and place, which become metadata filters on the vector store; it may search as often as it needs.
 */
@Component
class SightingBulletinSearch(private val vectorStore: VectorStore) {
    /** The bulletins observed between [from] and [to] (inclusive), at [place] unless it is missing or blank. */
    @Tool(description = SEARCH_DESCRIPTION)
    fun searchSightingBulletins(
        @ToolParam(description = "What to look for, in your own words, for example: kaiju sighting at the harbour")
        query: String,
        @ToolParam(description = "Start of the time window, an ISO-8601 UTC instant such as 2026-11-18T13:00:00Z")
        from: Instant,
        @ToolParam(description = "End of the time window, an ISO-8601 UTC instant such as 2026-11-18T17:00:00Z")
        to: Instant,
        @ToolParam(required = false, description = PLACE_DESCRIPTION)
        place: String?,
    ): List<SightingBulletinHit> {
        val filter = FilterExpressionBuilder()
        val inWindow = filter.and(
            filter.and(
                filter.eq("corpus", SIGHTING_BULLETIN_CORPUS),
                filter.gte(OBSERVED_AT_EPOCH_SECOND, from.epochSecond),
            ),
            filter.lte(OBSERVED_AT_EPOCH_SECOND, to.epochSecond),
        )
        val where = if (place.isNullOrBlank()) inWindow else filter.and(inWindow, filter.eq("place", place))
        val request = SearchRequest.builder().query(query).topK(TOP_K).filterExpression(where.build()).build()
        return vectorStore.similaritySearch(request).map { it.toHit() }
    }

    private fun Document.toHit(): SightingBulletinHit =
        SightingBulletinHit(
            bulletinId = metadata["bulletinId"].toString(),
            place = metadata["place"].toString(),
            // pgvector reads small JSON numbers back as Integer.
            observedAt = Instant.ofEpochSecond((metadata[OBSERVED_AT_EPOCH_SECOND] as Number).toLong()),
            text = text.orEmpty(),
        )
}
