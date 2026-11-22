package dev.frederikpietzko.springai.kotlin.fakes

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

/**
 * Scripted chat model: replies are queued and consumed in order, every received [Prompt] is recorded. A streamed reply
 * is one response per chunk, a blocking reply the chunks joined. An empty script fails loudly.
 */
class ScriptedChatModel : ChatModel {
    private val script = ConcurrentLinkedQueue<Step>()
    private val received = CopyOnWriteArrayList<Prompt>()

    /** Every prompt received so far, in order. */
    val prompts: List<Prompt> get() = received.toList()

    /** Queues one reply, streamed as the given chunks. */
    fun reply(vararg chunks: String) {
        script.add(Step(chunks.toList()))
    }

    /** Queues a reply that streams [chunks] and then fails with [failure]; a blocking call throws it. */
    fun replyThenFail(failure: Exception, vararg chunks: String) {
        script.add(Step(chunks.toList(), failure))
    }

    /** Queues a reply that streams [chunks] and then neither ends nor fails, like a model still thinking. */
    fun replyThenStall(vararg chunks: String) {
        script.add(Step(chunks.toList(), stalls = true))
    }

    /** Queues a blocking answer that asks for one execution of tool [name] with the JSON [arguments]. */
    fun requestTool(name: String, arguments: String) {
        script.add(Step([], toolCall = AssistantMessage.ToolCall("call-${script.size}", "function", name, arguments)))
    }

    override fun call(prompt: Prompt): ChatResponse {
        received.add(prompt)
        (val chunks, val failure, val toolCall) = next(prompt)
        failure?.let { throw it }
        toolCall?.let { return ChatResponse([Generation(AssistantMessage.builder().toolCalls([it]).build())]) }
        return response(chunks.joinToString(""))
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> = Flux.defer {
        received.add(prompt)
        val step = next(prompt)
        val chunks = Flux.fromIterable(step.chunks).map(::response)
        when {
            step.failure != null -> chunks.concatWith(Flux.error(step.failure))
            step.stalls -> chunks.concatWith(Flux.never())
            else -> chunks
        }
    }

    /** A [ToolCallingChatOptions], so the chat client runs its tool loop and passes tools and tool context. */
    override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

    private fun next(prompt: Prompt): Step =
        script.poll() ?: error("Script exhausted, unexpected prompt: ${prompt.contents}")

    private fun response(text: String) = ChatResponse([Generation(AssistantMessage(text))])

    private data class Step(
        val chunks: List<String>,
        val failure: Exception? = null,
        val toolCall: AssistantMessage.ToolCall? = null,
        val stalls: Boolean = false,
    )
}
