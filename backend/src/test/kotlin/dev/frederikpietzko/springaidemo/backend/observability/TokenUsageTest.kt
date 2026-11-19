package dev.frederikpietzko.springaidemo.backend.observability

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.RecordingObservationHandler
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model observation feeds the token usage counter. The fake chat model reports a fixed usage of 7 input and 3
 * output tokens per blocking call; the meter registry is shared, so the test reads the change of the counters.
 */
@IntegrationTest
class TokenUsageTest {
    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var builder: ChatClient.Builder

    @Autowired
    private lateinit var recorder: RecordingObservationHandler

    @Test
    fun `one call adds its input, output and total tokens to the token usage counter`() {
        val before = TOKEN_TYPES.associateWith(::chatTokens)
        fake.reply("ok")

        builder.build().prompt().user("hello").call().content()

        val added = TOKEN_TYPES.associateWith { chatTokens(it) - before.getValue(it) }
        assertEquals(mapOf("input" to 7.0, "output" to 3.0, "total" to 10.0), added)
    }

    @Test
    fun `the model call is observed`() {
        fake.reply("ok")

        builder.build().prompt().user("hello").call().content()

        val names = recorder.contexts.map { it.name }
        assertTrue("gen_ai.client.operation" in names, names.toString())
    }

    /** Chat tokens of one type so far; embedding calls report to the same counter with operation `embedding`. */
    private fun chatTokens(type: String): Double =
        meterRegistry.find("gen_ai.client.token.usage")
            .tag("gen_ai.operation.name", "chat")
            .tag("gen_ai.token.type", type)
            .counters()
            .sumOf { it.count() }

    private companion object {
        val TOKEN_TYPES: List<String> = ["input", "output", "total"]
    }
}
