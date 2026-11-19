package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import kotlin.math.sqrt

/** Same dimension as `nomic-embed-text` and the `vector(768)` column. */
private const val DIMENSIONS = 768

private val WORD = Regex("[a-z0-9]+")

private val STOP_WORDS: Set<String> =
    ["a", "an", "the", "is", "are", "was", "my", "of", "if", "i", "do", "does", "to", "in", "after", "for", "and"]

/**
 * Deterministic, offline embedding model for tests: a hashed bag of words. Every word (lower-case, stop words
 * dropped) adds 1 to the bucket of its hash, and the vector is L2-normalised, so texts that share words have a higher
 * cosine similarity than texts that share none. It approximates relevance only; it knows no synonyms.
 */
class FakeEmbeddingModel : EmbeddingModel {
    override fun call(request: EmbeddingRequest): EmbeddingResponse =
        EmbeddingResponse(request.instructions.mapIndexed { index, text -> Embedding(vectorOf(text), index) })

    override fun embed(document: Document): FloatArray = vectorOf(document.text.orEmpty())

    /** Known up front; the default implementation would embed a probe text. */
    override fun dimensions(): Int = DIMENSIONS

    private fun vectorOf(text: String): FloatArray {
        val vector = FloatArray(DIMENSIONS)
        WORD.findAll(text.lowercase())
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .forEach { vector[Math.floorMod(it.hashCode(), DIMENSIONS)] += 1f }
        // An all-zero vector has no direction: pgvector's cosine distance would be NaN.
        if (vector.all { it == 0f }) vector[0] = 1f
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(DIMENSIONS) { vector[it] / norm }
    }
}
