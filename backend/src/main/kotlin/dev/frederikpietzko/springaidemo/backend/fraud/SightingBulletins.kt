package dev.frederikpietzko.springaidemo.backend.fraud

import org.springaicommunity.agent.utils.MarkdownParser
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.time.Instant

private const val BULLETIN_FILES = "classpath:corpus/sighting-bulletins/*.md"

/** One official sighting bulletin: where a kaiju was seen at what time. The kaiju class is part of the [text]. */
internal data class SightingBulletin(val id: String, val observedAt: Instant, val place: String, val text: String)

/** Parses one bulletin file: frontmatter (`id`, `observedAt` as an ISO-8601 instant, `place`), then the text. */
internal fun parseSightingBulletin(fileName: String, content: String): SightingBulletin {
    val markdown = MarkdownParser(content)
    val header = markdown.frontMatter.mapValues { it.value.toString() }
    return SightingBulletin(
        id = requireNotNull(header["id"]?.ifBlank { null }) { "Sighting bulletin $fileName has no id" },
        observedAt = requireNotNull(header["observedAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() }) {
            "Sighting bulletin $fileName has no ISO-8601 observedAt"
        },
        place = requireNotNull(header["place"]?.ifBlank { null }) { "Sighting bulletin $fileName has no place" },
        text = markdown.content,
    )
}

/** Every bulletin of the classpath corpus, ordered by id. */
internal fun readSightingBulletins(): List<SightingBulletin> =
    PathMatchingResourcePatternResolver().getResources(BULLETIN_FILES)
        .map { parseSightingBulletin(it.filename.orEmpty(), it.getContentAsString(Charsets.UTF_8)) }
        .sortedBy { it.id }
