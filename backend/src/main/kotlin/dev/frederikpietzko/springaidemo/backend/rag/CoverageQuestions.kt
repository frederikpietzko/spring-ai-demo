package dev.frederikpietzko.springaidemo.backend.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.document.Document
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.stereotype.Service

/** A policy wording clause an answer rests on: the corpus file and the section title. */
data class Citation(val source: String, val title: String)

/** The model's answer to a coverage question and the clauses retrieved for it. */
data class CoverageAnswer(val text: String, val citations: List<Citation>)

/** The minimal RAG example: answers a coverage question from the policy wording, with citations. */
@Service
class CoverageQuestions(builder: ChatClient.Builder, policyWordingAdvisor: RetrievalAugmentationAdvisor) {
    private val chatClient = builder.defaultAdvisors(policyWordingAdvisor).build()

    /** The citations are the retrieved clauses, not necessarily the ones the model relied on. */
    fun answer(question: String): CoverageAnswer {
        val response = chatClient.prompt().user(question).call().chatClientResponse()
        val clauses = response.context()[RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT] as? List<*>
        return CoverageAnswer(
            text = response.chatResponse()?.result?.output?.text.orEmpty(),
            citations = clauses.orEmpty().filterIsInstance<Document>().map { it.citation() }.distinct(),
        )
    }

    private fun Document.citation(): Citation =
        Citation(source = metadata["source"].toString(), title = metadata["title"].toString())
}
