package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Each test starts clean and then leaves an unconsumed reply and a recorded prompt behind. Whichever runs second
 * would see the leftovers of the first if the shared fake were not reset between tests, so the order does not matter.
 */
@IntegrationTest
class FakeChatModelIsolationTest {
    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var builder: ChatClient.Builder

    @Test
    fun `first test starts clean and leaves state behind`() = startsCleanThenLeavesState()

    @Test
    fun `second test starts clean and leaves state behind`() = startsCleanThenLeavesState()

    private fun startsCleanThenLeavesState() {
        assertTrue(fake.prompts.isEmpty(), "prompts leaked from an earlier test: ${fake.prompts}")
        val error = assertFailsWith<IllegalStateException> {
            builder.build().prompt().user("probe").call().content()
        }
        assertTrue(error.message.orEmpty().contains("script exhausted"), "script leaked from an earlier test")
        assertEquals(1, fake.prompts.size)

        fake.reply("left behind")
        fake.reply("also left behind")
    }
}
