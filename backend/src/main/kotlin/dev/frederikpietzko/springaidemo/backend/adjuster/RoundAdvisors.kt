package dev.frederikpietzko.springaidemo.backend.adjuster

import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import reactor.core.publisher.Flux

/** What separates the text of two model rounds of one turn. */
private const val ROUND_BREAK = "\n\n"

/**
 * Records what the model is asked in each round of one adjuster turn. Inside the tool-calling loop every round carries
 * the conversation so far (earlier replies, their tool calls and results), so the last round's messages are the
 * conversation a paused reply answers. One instance per turn.
 */
internal class RoundCapture : StreamAdvisor {
    @Volatile
    private var instructions: List<Message> = []

    /** The messages of the latest round without the system message, which every turn adds again itself. */
    val conversation: List<Message> get() = instructions.filterNot { it is SystemMessage }

    override fun getName(): String = "RoundCapture"

    override fun getOrder(): Int = ToolCallingAdvisor.DEFAULT_ORDER + 1

    override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> {
        instructions = request.prompt().instructions
        return chain.nextStream(request)
    }
}

/** Sends a paragraph break before the first text of a round when an earlier round already wrote text. One per turn. */
internal class RoundBreaks : StreamAdvisor {
    @Volatile
    private var wroteText = false

    override fun getName(): String = "RoundBreaks"

    override fun getOrder(): Int = ToolCallingAdvisor.DEFAULT_ORDER + 2

    override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> {
        var firstText = true
        return chain.nextStream(request).concatMap { response ->
            val hasText = !response.chatResponse()?.result?.output?.text.isNullOrEmpty()
            val needsBreak = hasText && firstText && wroteText
            if (hasText) {
                firstText = false
                wroteText = true
            }
            if (needsBreak) Flux.just(roundBreak(response), response) else Flux.just(response)
        }
    }

    private fun roundBreak(next: ChatClientResponse): ChatClientResponse =
        next.mutate().chatResponse(ChatResponse([Generation(AssistantMessage(ROUND_BREAK))])).build()
}
