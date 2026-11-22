package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.assertEquals

/**
 * The audit of a stream the client stops early. Asserted at service level: collecting [ClaimantChat.stream] with
 * `take(1)` cancels the upstream, which a client disconnect over MockMvc or a test socket cannot reproduce reliably.
 */
@IntegrationTest
class ClaimantChatAuditTest {
    @Autowired
    private lateinit var claimantChat: ClaimantChat

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var auditTrail: AuditTrail

    @Test
    suspend fun `a cancelled stream is audited as cancelled with the reply received so far`() {
        val message = "audit probe: cancelled stream"
        // The model is still writing after "Hel"; the collector stops after that first chunk.
        fake.replyChunksThenStall("Hel")

        val chunks = claimantChat.stream("audit-cancelled", message).take(1).toList()

        assertEquals(["Hel"], chunks)
        // The cancellation reaches the audit advisor on the thread that cancels, so wait for the entry.
        val (outcome, replyChars) = await().atMost(Duration.ofSeconds(5))
            .until({ auditTrail.entries.filter { it.userMessage == message } }, { it.isNotEmpty() })
            .single()
        assertEquals(AuditOutcome.CANCELLED, outcome)
        assertEquals(3, replyChars)
    }
}
