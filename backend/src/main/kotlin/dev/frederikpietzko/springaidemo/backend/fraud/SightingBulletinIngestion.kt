package dev.frederikpietzko.springaidemo.backend.fraud

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.EmbeddedDocument
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** The `corpus` metadata value of the sighting bulletins in the shared vector table (ADR-0006). */
const val SIGHTING_BULLETIN_CORPUS = "sighting-bulletin"

/** The bulletin's observation time as epoch seconds: a number, so the time-window filter compares numerically. */
const val OBSERVED_AT_EPOCH_SECOND = "observedAtEpochSecond"

private val log = LoggerFactory.getLogger(SightingBulletinIngestion::class.java)

/**
 * Loads the sighting bulletins into the vector store at every start: one document per bulletin with its place and
 * observation time as metadata, one embedding call for all, then an upsert with stable ids.
 */
@Component
@MustUseReturnValues
class SightingBulletinIngestion(
    private val vectorStore: VectorStore,
    private val embeddingModel: EmbeddingModel,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        log.info("Ingested {} sighting bulletins", ingest())
    }

    /** Reads, embeds and upserts the bulletins; returns the number of bulletins stored. */
    fun ingest(): Int {
        val documents = readSightingBulletins().map { it.toDocument() }
        val vectors = embeddingModel.embed(documents.map { it.text.orEmpty() })
        vectorStore.upsert(documents.zip(vectors) { document, vector -> EmbeddedDocument(document, vector) })
        return documents.size
    }

    private fun SightingBulletin.toDocument(): Document =
        Document.builder()
            .id("$SIGHTING_BULLETIN_CORPUS/$id")
            .text(text)
            .metadata("corpus", SIGHTING_BULLETIN_CORPUS)
            .metadata("bulletinId", id)
            .metadata("place", place)
            .metadata(OBSERVED_AT_EPOCH_SECOND, observedAt.epochSecond)
            .build()
}
