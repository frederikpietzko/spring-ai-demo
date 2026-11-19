package dev.frederikpietzko.springaidemo.backend.rag

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.ai.document.Document
import org.springframework.ai.reader.markdown.MarkdownDocumentReader
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The policy wording as the ingestion sees it: the Markdown reader's sections of the classpath corpus. */
class PolicyWordingCorpusTest {
    private val sections: List<Document> =
        MarkdownDocumentReader(
            PathMatchingResourcePatternResolver().getResources("classpath:corpus/policy-wording/*.md").toList(),
            MarkdownDocumentReaderConfig.defaultConfig(),
        ).get()

    @Test
    fun `the Act of Kaiju exclusion names itself and excludes class 5 events`() {
        val section = sections.single { it.text.orEmpty().contains("Act of Kaiju exclusion") }

        assertTrue(section.text.orEmpty().contains("damage from a class 5 kaiju event is not covered"), section.text)
    }

    @Test
    fun `the provoking exclusion names itself and excludes damage caused by provoking the kaiju`() {
        val section = sections.single { it.text.orEmpty().contains("provoking the kaiju exclusion") }

        val text = section.text.orEmpty()
        assertTrue(text.contains("damage caused by the policyholder provoking the kaiju is not covered"), text)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Vehicle cover", "Building cover", "Contents cover", "Garden cover"])
    fun `each coverage is described in a section of its own`(coverage: String) {
        val titles = sections.map { it.metadata["title"] }

        assertEquals(1, titles.count { it == coverage }, titles.toString())
    }
}
