package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.fraud.SightingBulletinSearch
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallDecision
import org.springframework.ai.model.tool.ToolCallDecisions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Who calls the core tools. It travels in the tool context only, never in a message, and is forwarded to `core-mcp`
 * as MCP `_meta`. A fixed adjuster identity until the claimant id and OAuth arrive (`mcp-security`).
 */
private val ADJUSTER_IDENTITY: Map<String, Any> = mapOf("actor" to "adjuster-agent")

/** What the model receives as the result of a payout the adjuster denied. */
private const val DENIED = "The adjuster did not approve this payout."

/** The answer to a decision on an unknown id, or on one that was decided already. */
private const val NOTHING_WAITING = "Nothing is waiting for this decision."

/**
 * The adjuster agent: answers one adjuster message with the core tools, as a stream of chat events. A reply that
 * calls a tool needing confirmation (`issuePayout`) ends with a [ChatEvent.ConfirmationRequest]; the paused reply
 * waits in [PendingConfirmations] until [decide] runs or skips its tool calls and continues the reply.
 */
@Service
class AdjusterChat(
    @Qualifier("adjusterChatClient") private val adjusterChatClient: ChatClient,
    @Qualifier("mcpToolCallbacks") private val coreTools: ToolCallbackProvider,
    private val toolCallingManager: ToolCallingManager,
    private val pendingConfirmations: PendingConfirmations,
    private val sightingBulletinSearch: SightingBulletinSearch,
) {
    /** Streams the reply to [message]: text deltas, and a confirmation request if the reply pauses. */
    fun stream(message: String): Flow<ChatEvent> {
        val conversation: List<Message> = [UserMessage(message)]
        return turn(conversation)
    }

    /**
     * Carries out a [decision] on a paused reply and streams the continued reply, which may pause again. Approve runs
     * every tool call of the paused reply once; deny runs none and gives the model a denial text as their result. The
     * paused reply is taken out of [PendingConfirmations] first, so the same decision cannot run the tools twice; an
     * unknown id answers one error event.
     */
    fun decide(decision: ConfirmationDecision): Flow<ChatEvent> =
        flow {
            val paused = pendingConfirmations.take(decision.confirmationId)
            if (paused == null) {
                emit(ChatEvent.Error(NOTHING_WAITING))
                return@flow
            }
            // The tool calls block (the MCP client waits for the core), so they run off the request thread.
            val conversation = withContext(Dispatchers.IO) { runToolCalls(paused, decision.approved) }
            emitAll(turn(conversation))
        }

    /**
     * Runs or denies the tool calls of the [paused] reply and returns the conversation with their results. The loop
     * keeps no state, so the prompt carries every tool of the adjuster (the core tools and the bulletin search) and the
     * tool context again: a tool missing here fails with "No ToolCallback found".
     */
    private fun runToolCalls(paused: PausedTurn, approved: Boolean): List<Message> {
        val keys = ToolCallDecisions.toolCallKeys(checkNotNull(paused.reply.result) { "paused reply" }.output)
        val decisions = keys.map { key ->
            if (approved) ToolCallDecision.allow(key) else ToolCallDecision.deny(key, DENIED)
        }
        val options = ToolCallingChatOptions.builder()
            .toolCallbacks(*coreTools.toolCallbacks, *ToolCallbacks.from(sightingBulletinSearch))
            .toolContext(ADJUSTER_IDENTITY)
            .build()
        return toolCallingManager
            .executeToolCalls(Prompt(paused.conversation, options), paused.reply, ToolCallDecisions(decisions))
            .conversationHistory()
    }

    /**
     * One model turn over [conversation]. The whole request is built inside the `flow`: `.stream()` lists the MCP
     * tools right away (the first connection to the core) and checks the tool names, and a failure there must reach
     * the caller as a failed flow, not as an exception of this call. `chatResponse()`, not `content()`: only the
     * response metadata tells a paused reply apart, and its last chunk carries the tool calls but no text.
     */
    private fun turn(conversation: List<Message>): Flow<ChatEvent> =
        flow {
            adjusterChatClient.prompt()
                .messages(conversation)
                .toolContext(ADJUSTER_IDENTITY)
                .stream()
                .chatResponse()
                .asFlow()
                .collect { response ->
                    val text = response.result?.output?.text
                    when {
                        ToolCallDecisions.isAwaitingDecision(response) -> emit(pause(conversation, response))
                        !text.isNullOrEmpty() -> emit(ChatEvent.TextDelta(text))
                    }
                }
        }

    /** Keeps the paused [reply] and names the first tool call that waits for a decision. */
    private fun pause(conversation: List<Message>, reply: ChatResponse): ChatEvent.ConfirmationRequest {
        val toolCalls = checkNotNull(reply.result) { "a paused reply has a generation" }.output
        val waiting = ToolCallDecisions.toolCallKeys(toolCalls)
            .indexOf(ToolCallDecisions.awaitingDecision(reply).first())
        val call = toolCalls.toolCalls[waiting]
        val confirmationId = pendingConfirmations.add(PausedTurn(conversation, reply))
        return ChatEvent.ConfirmationRequest(confirmationId, call.name(), call.arguments())
    }
}
