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

/**
 * After the session turn advisor (`HIGHEST_PRECEDENCE + 200`), before the tool-calling advisor (`+ 300`): retrieval
 * runs once per user turn. The session memory advisor sits inside the tool loop (`+ 1000`), after retrieval, so it
 * stores the user message as retrieval left it.
 */
private const val RAG_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50

private const val CITE_LABELS = "cite the [labels] of the clauses you rely on"

private const val OTHERWISE = "Otherwise answer normally."

/**
 * Wraps the claimant's message (`{query}`) with the retrieved clauses (`{context}`). Conditional, because every
 * message retrieves clauses: a damage description or a greeting is answered as usual.
 */
private const val CITING_TEMPLATE = """Policy wording clauses, each labelled [source - title]:
---------------------
{context}
---------------------
If the message asks about coverage, answer from the policy wording above and $CITE_LABELS; if the wording does not
answer it, say so. $OTHERWISE

Message: {query}"""

/** Retrieval over the policy wording, shared by the coverage service and the claimant chat. */
@Configuration(proxyBeanMethods = false)
class RagConfiguration {
    /**
     * The four policy wording clauses closest to a question. No similarity threshold: a value tuned for real
     * embeddings would filter out everything under the test embedding model, and tests share this configuration.
     */
    @Bean
    fun policyWordingRetriever(vectorStore: VectorStore): VectorStoreDocumentRetriever =
        VectorStoreDocumentRetriever.builder()
            .vectorStore(vectorStore)
            .topK(TOP_K)
            .filterExpression(FilterExpressionBuilder().eq("corpus", POLICY_WORDING_CORPUS).build())
            .build()

    /**
     * Adds the retrieved clauses, each labelled `[source - title]`, to the user message. One bean for every chat
     * client: the advisor owns a thread pool for the blocking retrieval.
     */
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
