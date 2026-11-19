package dev.frederikpietzko.springaidemo.backend.rag

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.ai.session.advisor.SessionTurnAdvisor
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Retrieval must run once per user turn: after the session turn advisor and outside the tool loop, where it would run
 * again on every tool iteration. The session memory advisor sits inside the loop, after retrieval. The order value
 * itself is pinned.
 */
@IntegrationTest
class RagOrderTest {
    @Autowired
    private lateinit var policyWordingAdvisor: RetrievalAugmentationAdvisor

    @Test
    fun `retrieval is ordered between the session turn advisor and the tool-calling advisor`() {
        val order = policyWordingAdvisor.order

        assertTrue(order > SessionTurnAdvisor.DEFAULT_ORDER, "order $order")
        assertTrue(order < ToolCallingAdvisor.DEFAULT_ORDER, "order $order")
    }
}
