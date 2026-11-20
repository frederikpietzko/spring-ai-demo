package dev.frederikpietzko.springaidemo.backend.fraud

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The sighting bulletins as the ingestion reads them from the classpath, and the header parser on malformed files. */
class SightingBulletinCorpusTest {
    private val bulletins: List<SightingBulletin> = readSightingBulletins()

    @Test
    fun `the neighbouring-town bulletin contradicts claim 4711`() {
        val saltmarsh = bulletins.single { it.id == "SB-102" }

        assertEquals("SALTMARSH", saltmarsh.place)
        assertEquals(Instant.parse("2026-11-18T14:45:00Z"), saltmarsh.observedAt)
        assertTrue(saltmarsh.text.contains("40 km"), saltmarsh.text)
    }

    @Test
    fun `no harbour bulletin is observed in the incident window of claim 4711`() {
        val window = Instant.parse("2026-11-18T13:00:00Z")..Instant.parse("2026-11-18T17:00:00Z")

        val harbourInWindow = bulletins.filter { it.place == "HARBOUR" && it.observedAt in window }

        assertEquals([], harbourInWindow)
    }

    @Test
    fun `bulletins agree with the other seed claims`() {
        val oldTown = bulletins.single { it.place == "OLD_TOWN" }
        val riverside = bulletins.single { it.place == "RIVERSIDE" }

        assertEquals(Instant.parse("2026-11-16T09:30:00Z"), oldTown.observedAt)
        assertTrue(oldTown.text.contains("class 5"), oldTown.text)
        assertEquals(Instant.parse("2026-11-17T18:10:00Z"), riverside.observedAt)
        assertTrue(riverside.text.contains("class 1"), riverside.text)
    }

    @Test
    fun `every bulletin mentions a kaiju and has a header`() {
        val ids = bulletins.map { it.id }

        assertEquals(["SB-101", "SB-102", "SB-103", "SB-104", "SB-105", "SB-106", "SB-107"], ids)
        assertTrue(bulletins.all { it.text.contains("kaiju") && it.place.isNotBlank() }, bulletins.toString())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "---\nobservedAt: 2026-11-18T14:45:00Z\nplace: SALTMARSH\n---\n\nA class 3 kaiju.",
            "---\nid: SB-900\nplace: SALTMARSH\n---\n\nA class 3 kaiju.",
            "---\nid: SB-900\nobservedAt: yesterday afternoon\nplace: SALTMARSH\n---\n\nA class 3 kaiju.",
            "---\nid: SB-900\nobservedAt: 2026-11-18T14:45:00Z\n---\n\nA class 3 kaiju.",
        ],
    )
    fun `a malformed bulletin file is rejected by name`(content: String) {
        val failure = assertFailsWith<IllegalArgumentException> { parseSightingBulletin("broken.md", content) }

        assertTrue(failure.message.orEmpty().contains("broken.md"), failure.message)
    }
}
