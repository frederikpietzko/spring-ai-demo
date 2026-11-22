package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springai.kotlin.FlowAdvisor
import dev.frederikpietzko.springai.kotlin.FlowAdvisorChain
import dev.frederikpietzko.springai.kotlin.flowAdvisor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import java.util.concurrent.atomic.AtomicBoolean

/** What separates the text of two model rounds of one turn. */
private const val ROUND_BREAK = "\n\n"

/**
 * Records what the model is asked in each round of one adjuster turn. Inside the tool-calling loop every round carries
 * the conversation so far (earlier replies, their tool calls and results), so the last round's messages are the
 * conversation a paused reply answers. One instance per turn.
 */
internal class RoundCapture : FlowAdvisor(ToolCallingAdvisor.DEFAULT_ORDER + 1) {
    @Volatile
    private var instructions: List<Message> = []

    /** The messages of the latest round without the system message, which every turn adds again itself. */
    val conversation: List<Message> get() = instructions.filterNot { it is SystemMessage }

    override fun adviseFlow(request: ChatClientRequest, chain: FlowAdvisorChain): Flow<ChatClientResponse> {
        instructions = request.prompt().instructions
        return chain.next(request)
    }
}

/** Sends a paragraph break before the first text of a round when an earlier round already wrote text. One per turn. */
internal fun roundBreaks(): FlowAdvisor {
    // The rounds of a turn run one after another, possibly on different threads.
    val wroteText = AtomicBoolean(false)
    return flowAdvisor(ToolCallingAdvisor.DEFAULT_ORDER + 2, "RoundBreaks") { request, chain ->
        flow {
            var firstText = true
            chain.next(request).collect { response ->
                val hasText = !response.chatResponse()?.result?.output?.text.isNullOrEmpty()
                if (hasText && firstText && wroteText.get()) emit(roundBreak(response))
                if (hasText) {
                    firstText = false
                    wroteText.set(true)
                }
                emit(response)
            }
        }
    }
}

private fun roundBreak(next: ChatClientResponse): ChatClientResponse =
    next.mutate().chatResponse(ChatResponse([Generation(AssistantMessage(ROUND_BREAK))])).build()
