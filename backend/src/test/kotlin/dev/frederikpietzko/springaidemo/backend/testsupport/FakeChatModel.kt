package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scripted chat model for tests: replies are queued with [reply] and consumed in order, every received [Prompt] is
 * recorded. An empty script fails loudly. State is thread-safe because the tool loop and streaming run on other
 * threads.
 */
class FakeChatModel : ChatModel {
    private val script = ConcurrentLinkedQueue<Step>()
    private val received = CopyOnWriteArrayList<Prompt>()
    private val toolCallIds = AtomicInteger()

    /** Every prompt received so far, in order. */
    val prompts: List<Prompt> get() = received.toList()

    /** Queues one text reply. */
    fun reply(text: String) {
        script.add(Step(AssistantMessage(text), [text]))
    }

    /** Queues an answer that asks for one tool execution (arguments as a JSON string). */
    fun requestTool(name: String, argumentsJson: String) {
        val call = AssistantMessage.ToolCall("call-${toolCallIds.incrementAndGet()}", "function", name, argumentsJson)
        script.add(Step(AssistantMessage.builder().toolCalls([call]).build(), []))
    }

    /** Queues one text reply that is delivered as several chunks when streamed and joined when called. */
    fun replyChunks(vararg chunks: String) {
        script.add(Step(AssistantMessage(chunks.joinToString("")), chunks.toList()))
    }

    /**
     * Queues a reply that fails with an [IllegalStateException] carrying [error]: a stream emits [chunks] first and
     * then the error, a blocking call throws it. Unlike an exhausted script this is an intended, scripted failure.
     */
    fun replyChunksThenFail(error: String, vararg chunks: String) =
        replyChunksThenFail(IllegalStateException(error), *chunks)

    /** Queues a reply that emits [chunks] and then fails with [failure], for any throwable, JVM errors included. */
    fun replyChunksThenFail(failure: Throwable, vararg chunks: String) {
        script.add(Step(AssistantMessage(chunks.joinToString("")), chunks = chunks.toList(), failure = failure))
    }

    /** Queues one reply that is bare JSON text, for structured output. */
    fun replyJson(json: String) = reply(json)

    /** Empties the script, the recorded prompts and the tool-call id counter. */
    fun reset() {
        script.clear()
        received.clear()
        toolCallIds.set(0)
    }

    override fun call(prompt: Prompt): ChatResponse {
        received.add(prompt)
        (val message, val failure) = script.poll() ?: error(exhaustedMessage(prompt))
        failure?.let { throw it }
        return ChatResponse([Generation(message)])
    }

    /**
     * Streamed text is one response per chunk. Chunk shape tolerated by the streaming tool loop (verified by
     * FakeChatModelToolsTest): a tool-call step is a single response whose AssistantMessage carries the tool calls,
     * with no text and no finish metadata. The advisor aggregates the stream, runs the tool, and calls stream() again.
     */
    override fun stream(prompt: Prompt): Flux<ChatResponse> =
        Flux.defer {
            received.add(prompt)
            val step = script.poll() ?: return@defer Flux.error(IllegalStateException(exhaustedMessage(prompt)))
            if (step.message.hasToolCalls()) {
                Flux.just(ChatResponse([Generation(step.message)]))
            } else {
                val chunks = Flux.fromIterable(step.chunks).map { ChatResponse([Generation(AssistantMessage(it))]) }
                step.failure?.let { chunks.concatWith(Flux.error(it)) } ?: chunks
            }
        }

    /**
     * Must be a [ToolCallingChatOptions]: otherwise the chat client skips its tool loop and returns tool-call
     * requests as the final answer.
     */
    override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

    private fun exhaustedMessage(prompt: Prompt) =
        "Fake chat model script exhausted, unexpected prompt: ${prompt.contents}"

    /**
     * One scripted answer: the full message for blocking calls and the text chunks for streamed calls, optionally
     * followed by the [failure].
     */
    private data class Step(val message: AssistantMessage, val chunks: List<String>, val failure: Throwable? = null)
}
