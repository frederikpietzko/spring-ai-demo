package dev.frederikpietzko.springai.kotlin

import dev.frederikpietzko.springai.kotlin.fakes.ScriptedChatModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamFlowTest {
    @Test
    suspend fun `content chunks as a flow`() {
        val model = ScriptedChatModel().apply { reply("Hel", "lo") }
        val client = ChatClient.builder(model).build()

        val chunks = client.prompt { user("U") }.stream().contentFlow().toList()

        assertEquals(["Hel", "lo"], chunks)
    }

    @Test
    suspend fun `chat response chunks as a flow`() {
        val model = ScriptedChatModel().apply { reply("Hel", "lo") }
        val client = ChatClient.builder(model).build()

        val chunks = client.prompt { user("U") }.stream().chatResponseFlow().map { it.result?.output?.text }.toList()

        assertEquals(["Hel", "lo"], chunks)
    }

    @Test
    suspend fun `taking the first chunk cancels the model stream before its last chunk`() {
        val model = CountingStreamModel(chunks = 10_000)
        val client = ChatClient.builder(model).build()

        val first = client.prompt { user("U") }.stream().contentFlow().first()

        assertEquals("chunk-1", first)
        assertTrue(model.cancelled.get())
        assertTrue(model.produced.get() < 10_000, "produced ${model.produced.get()} chunks")
    }

    /** Streams up to [chunks] chunks, each made only when requested; counts them and notices a cancel. */
    private class CountingStreamModel(private val chunks: Int) : ChatModel {
        val produced = AtomicInteger()
        val cancelled = AtomicBoolean()

        override fun call(prompt: Prompt): ChatResponse = error("stream only")

        override fun stream(prompt: Prompt): Flux<ChatResponse> =
            Flux.generate<ChatResponse> { sink ->
                val number = produced.incrementAndGet()
                sink.next(ChatResponse([Generation(AssistantMessage("chunk-$number"))]))
                if (number == chunks) sink.complete()
            }.doOnCancel { cancelled.set(true) }

        override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()
    }
}
