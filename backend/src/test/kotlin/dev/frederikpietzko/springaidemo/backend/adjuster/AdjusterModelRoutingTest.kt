package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Model routing (ADR-0008) through `POST /chat`: the adjuster orchestrator has its own model, the claimant none. */
@IntegrationTest
class AdjusterModelRoutingTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Test
    fun `the adjuster orchestrator runs on the configured adjuster model`() {
        fake.replyChunks("ok")

        client.postChat("""{"persona":"ADJUSTER","message":"Review claim 4711"}""")

        assertEquals("claude-sonnet-5-5", fake.prompts[0].options?.model)
    }

    @Test
    fun `the claimant keeps the default model`() {
        fake.replyChunks("ok")

        client.postChat(claimantJson("Hi"))

        assertNull(fake.prompts.last().options?.model)
    }
}
