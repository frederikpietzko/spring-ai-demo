package dev.frederikpietzko.springaidemo.backend.rag

import org.springframework.ai.chat.client.advisor.api.Advisor
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.document.Document
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The `corpus` metadata value of the policy wording chunks in the shared vector table (ADR-0006). */
const val POLICY_WORDING_CORPUS = "policy-wording"

private const val TOP_K = 4

/** After the session turn advisor (+200), before the tool loop (+300): retrieval runs once per user turn. */
private const val RAG_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50

/**
 * Wraps the claimant's message (`{query}`) with the retrieved clauses (`{context}`). Conditional, because every
 * message retrieves clauses: a damage description or a greeting is answered as usual.
 */
private const val CITING_TEMPLATE = """Policy wording clauses, each labelled [source - title]:
---------------------
{context}
---------------------
If the message asks about coverage, answer from the policy wording above and
cite the [labels] of the clauses you rely on; if the wording does not answer it, say so. Otherwise answer normally.

Message: {query}"""

/** Retrieval over the policy wording, shared by the coverage service and the claimant chat. */
@Configuration(proxyBeanMethods = false)
class RagConfiguration {
    /** The four closest policy wording clauses. No similarity threshold: the tests' fake embeddings would fail it. */
    @Bean
    fun policyWordingRetriever(vectorStore: VectorStore): VectorStoreDocumentRetriever =
        VectorStoreDocumentRetriever.builder()
            .vectorStore(vectorStore)
            .topK(TOP_K)
            .filterExpression(FilterExpressionBuilder().eq("corpus", POLICY_WORDING_CORPUS).build())
            .build()

    /** Adds the retrieved clauses, each labelled `[source - title]`, to the user message. */
    @Bean
    fun policyWordingAdvisor(policyWordingRetriever: VectorStoreDocumentRetriever): RetrievalAugmentationAdvisor =
        RetrievalAugmentationAdvisor.builder()
            .documentRetriever(policyWordingRetriever)
            .queryAugmenter(
                ContextualQueryAugmenter.builder()
                    .promptTemplate(PromptTemplate(CITING_TEMPLATE))
                    .documentFormatter { clauses -> clauses.joinToString("\n\n") { labelled(it) } }
                    // Nothing retrieved: send the bare message instead of the library's "outside your knowledge base".
                    .allowEmptyContext(true)
                    .build(),
            )
            .order(RAG_ORDER)
            .build()

    private fun labelled(clause: Document): String =
        "[${clause.metadata["source"]} - ${clause.metadata["title"]}]\n${clause.text}"
}
