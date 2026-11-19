package dev.frederikpietzko.springaidemo.backend.fraud

import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.time.Instant
import java.time.format.DateTimeParseException

private const val BULLETIN_FILES = "classpath:corpus/sighting-bulletins/*.md"

/** One official sighting bulletin: where a kaiju was seen at what time. The kaiju class is part of the [text]. */
internal data class SightingBulletin(val id: String, val observedAt: Instant, val place: String, val text: String)

/**
 * Parses one bulletin file: `key: value` header lines (`id`, `observedAt` as an ISO-8601 instant, `place`), a blank
 * line, then the text. A missing or unparseable header value fails with a message naming [fileName].
 */
internal fun parseSightingBulletin(fileName: String, content: String): SightingBulletin {
    val header = content.substringBefore("\n\n").lines()
        .map { it.split(":", limit = 2) }
        .filter { it.size == 2 }
        .associate { it[0].trim() to it[1].trim() }
    return SightingBulletin(
        id = requireNotNull(header["id"]?.ifBlank { null }) { "Sighting bulletin $fileName has no id" },
        observedAt = requireNotNull(header["observedAt"]?.toInstantOrNull()) {
            "Sighting bulletin $fileName has no ISO-8601 observedAt"
        },
        place = requireNotNull(header["place"]?.ifBlank { null }) { "Sighting bulletin $fileName has no place" },
        text = content.substringAfter("\n\n", "").trim(),
    )
}

/** Every bulletin of the classpath corpus, ordered by id. */
internal fun readSightingBulletins(): List<SightingBulletin> =
    PathMatchingResourcePatternResolver().getResources(BULLETIN_FILES)
        .map { file ->
            val fileName = checkNotNull(file.filename) { "corpus resource without a file name: $file" }
            parseSightingBulletin(fileName, file.getContentAsString(Charsets.UTF_8))
        }
        .sortedBy { it.id }

private fun String.toInstantOrNull(): Instant? =
    try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
