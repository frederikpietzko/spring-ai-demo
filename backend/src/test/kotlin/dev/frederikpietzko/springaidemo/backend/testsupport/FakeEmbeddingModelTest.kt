package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.EmbeddedDocument
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeEmbeddingModelTest {
    private val fake = FakeEmbeddingModel()

    @Test
    fun `the same text gets the same 768-dimensional vector`() {
        val first = fake.embed("Is my car covered after a class 5 kaiju event?")
        val second = fake.embed("Is my car covered after a class 5 kaiju event?")

        assertContentEquals(first, second)
        assertEquals(768, first.size)
        assertEquals(768, fake.dimensions())
    }

    @Test
    fun `a text sharing words with the question is more similar than one sharing none`() {
        val question = fake.embed("Is my car covered after a class 5 kaiju event?")
        val sharing = fake.embed("Damage from a class 5 kaiju event is not covered.")
        val unrelated = fake.embed("Garden furniture and greenhouses")

        assertTrue(cosine(question, sharing) > cosine(question, unrelated))
    }

    @Test
    fun `a text without words still gets a usable vector`() {
        val vector = fake.embed("")

        assertEquals(768, vector.size)
        assertTrue(vector.all { it.isFinite() })
        assertTrue(vector.any { it != 0f })
    }

    @Test
    fun `its vectors are accepted as embedded documents`() {
        val document = Document("The Act of Kaiju exclusion")

        val embedded = EmbeddedDocument(document, fake.embed(document))

        assertEquals(768, embedded.embedding().size)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        val dot = a.indices.sumOf { (a[it] * b[it]).toDouble() }
        val norms = sqrt(a.sumOf { (it * it).toDouble() }) * sqrt(b.sumOf { (it * it).toDouble() })
        return dot / norms
    }
}
