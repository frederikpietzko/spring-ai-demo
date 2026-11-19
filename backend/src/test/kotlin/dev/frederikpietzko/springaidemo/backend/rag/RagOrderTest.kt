package dev.frederikpietzko.springaidemo.backend.rag

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.client.advisor.api.Advisor
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Retrieval must run once per user turn: after the memory advisors and outside the tool loop, where it would run again
 * on every tool iteration. Wave 1 has no tools, so the order value itself is pinned.
 */
@IntegrationTest
class RagOrderTest {
    @Autowired
    private lateinit var policyWordingAdvisor: RetrievalAugmentationAdvisor

    @Test
    fun `retrieval is ordered between the memory advisors and the tool-calling advisor`() {
        val order = policyWordingAdvisor.order

        assertTrue(order > Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER, "order $order")
        assertTrue(order < ToolCallingAdvisor.DEFAULT_ORDER, "order $order")
    }
}
