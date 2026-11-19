package dev.frederikpietzko.springaidemo.backend.rag

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.reader.markdown.MarkdownDocumentReader
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.EmbeddedDocument
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(PolicyWordingIngestion::class.java)

private const val CORPUS_FILES = "classpath:corpus/policy-wording/*.md"

/**
 * Loads the policy wording into the vector store at every start (ETL): Markdown sections, token chunks, one embedding
 * call for all chunks, then an upsert with stable ids, so a re-run replaces rows instead of adding them. A failing
 * embedding call aborts startup; nothing is written before all chunks are embedded.
 */
@Component
@MustUseReturnValues
class PolicyWordingIngestion(
    private val vectorStore: VectorStore,
    private val embeddingModel: EmbeddingModel,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        log.info("Ingested {} policy wording chunks", ingest())
    }

    /** Reads, splits, embeds and upserts the policy wording; returns the number of chunks stored. */
    fun ingest(): Int {
        val chunks = TokenTextSplitter.builder().build().apply(sections()).map { chunk ->
            chunk.mutate().id("${chunk.metadata["parent_document_id"]}-${chunk.metadata["chunk_index"]}").build()
        }
        val vectors = embeddingModel.embed(chunks.map { it.text.orEmpty() })
        vectorStore.upsert(chunks.zip(vectors) { chunk, vector -> EmbeddedDocument(chunk, vector) })
        return chunks.size
    }

    /** One document per `##` section, with the heading as `title` and a stable id `policy-wording/<file>#<n>`. */
    private fun sections(): List<Document> =
        PathMatchingResourcePatternResolver().getResources(CORPUS_FILES).flatMap { file ->
            val source = checkNotNull(file.filename) { "corpus resource without a file name: $file" }
            MarkdownDocumentReader(file, readerConfig(source)).get().mapIndexed { index, section ->
                section.mutate().id("$POLICY_WORDING_CORPUS/$source#$index").build()
            }
        }

    private fun readerConfig(source: String): MarkdownDocumentReaderConfig =
        MarkdownDocumentReaderConfig.builder()
            .withAdditionalMetadata("corpus", POLICY_WORDING_CORPUS)
            .withAdditionalMetadata("source", source)
            .build()
}
