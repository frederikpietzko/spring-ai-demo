package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.fraud.SightingBulletinSearch
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.springaicommunity.agent.tools.TodoWriteTool
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
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Who calls the core tools. It travels in the tool context only, never in a message, and is forwarded to `core-mcp`
 * as MCP `_meta`. A fixed adjuster identity until the claimant id and OAuth arrive (`mcp-security`). The adjuster's
 * subagents ([AdjusterSubagents]) call the core tools with the same identity.
 */
val ADJUSTER_IDENTITY: Map<String, Any> = mapOf("actor" to "adjuster-agent")

/** What the model receives as the result of a payout the adjuster denied. */
private const val DENIED = "The adjuster did not approve this payout."

/** The answer to a decision on an unknown id, or on one that was decided already. */
private const val NOTHING_WAITING = "Nothing is waiting for this decision."

/**
 * The adjuster agent: answers one adjuster message with the core tools, its skills, the sighting bulletin search and
 * its own todo list, as a stream of chat events. Every todo write becomes a [ChatEvent.TodoUpdate]. A reply that calls
 * a tool needing confirmation (`issuePayout`) ends with a [ChatEvent.ConfirmationRequest]; the paused reply waits in
 * [PendingConfirmations] until [decide] runs or skips its tool calls and continues the reply.
 */
@Service
class AdjusterChat(
    @Qualifier("adjusterChatClient") private val adjusterChatClient: ChatClient,
    @Qualifier("mcpToolCallbacks") private val coreTools: ToolCallbackProvider,
    @Qualifier("adjusterSkills") private val adjusterSkills: ToolCallback,
    @Qualifier("adjusterTask") private val adjusterTask: ToolCallback,
    private val toolCallingManager: ToolCallingManager,
    private val pendingConfirmations: PendingConfirmations,
    private val sightingBulletinSearch: SightingBulletinSearch,
) {
    /** Streams the reply to [message]: text deltas, todo updates, and a confirmation request if the reply pauses. */
    fun stream(message: String): Flow<ChatEvent> {
        val conversation: List<Message> = [UserMessage(message)]
        return turn(conversation)
    }

    /**
     * Carries out a [decision] on a paused reply and streams the continued reply, which may pause again. Approve runs
     * every tool call of the paused reply once; deny runs none of the calls that wait for a decision, gives the model a
     * denial text as their result and runs the other calls normally (a todo write, a claim lookup, a skill). The paused
     * reply is taken out of [PendingConfirmations] first, so the same decision cannot run the tools twice; an unknown
     * id answers one error event. A todo write among the paused calls sends its todo update on this stream.
     */
    fun decide(decision: ConfirmationDecision): Flow<ChatEvent> =
        channelFlow {
            val paused = pendingConfirmations.take(decision.confirmationId)
            if (paused == null) {
                send(ChatEvent.Error(NOTHING_WAITING))
                return@channelFlow
            }
            val todoWrite = todoWriteTool(channel)
            // The tool calls block (the MCP client waits for the core), so they run off the request thread.
            val conversation = withContext(Dispatchers.IO) { runToolCalls(paused, decision.approved, todoWrite) }
            turn(conversation).collect { send(it) }
        }.buffer(Channel.UNLIMITED)

    /**
     * Runs the tool calls of the [paused] reply and returns the conversation with their results: the calls that wait
     * for a decision run only when [approved], every other call runs as it would have without the pause. The loop
     * keeps no state, so the prompt carries every adjuster tool (the core tools, [todoWrite], the skills, the
     * bulletin search and `Task`) and the tool context again: a tool missing here fails the decision.
     */
    private fun runToolCalls(paused: PausedTurn, approved: Boolean, todoWrite: TodoWriteTool): List<Message> {
        val keys = ToolCallDecisions.toolCallKeys(checkNotNull(paused.reply.result) { "paused reply" }.output)
        val waiting = ToolCallDecisions.awaitingDecision(paused.reply)
        val decisions = keys.map { key ->
            if (approved || key !in waiting) ToolCallDecision.allow(key) else ToolCallDecision.deny(key, DENIED)
        }
        val options = ToolCallingChatOptions.builder()
            .toolCallbacks(
                *coreTools.toolCallbacks,
                *ToolCallbacks.from(todoWrite),
                adjusterSkills,
                *ToolCallbacks.from(sightingBulletinSearch),
                adjusterTask,
            )
            .toolContext(ADJUSTER_IDENTITY)
            .build()
        return toolCallingManager
            .executeToolCalls(Prompt(paused.conversation, options), paused.reply, ToolCallDecisions(decisions))
            .conversationHistory()
    }

    /**
     * One model turn over [conversation]. The whole request is built inside the flow: `.stream()` lists the MCP tools
     * right away (the first connection to the core) and checks the tool names, and a failure there must reach the
     * caller as a failed flow, not as an exception of this call. `chatResponse()`, not `content()`: only the response
     * metadata tells a paused reply apart, and its last chunk carries the tool calls but no text. The turn has its own
     * todo tool: tool calls never reach this stream, so the tool itself sends each todo update into the channel, from
     * the thread the tool runs on (the unlimited buffer means `trySend` never has to wait).
     */
    private fun turn(conversation: List<Message>): Flow<ChatEvent> =
        channelFlow {
            adjusterChatClient.prompt()
                .messages(conversation)
                .tools(todoWriteTool(channel))
                .toolContext(ADJUSTER_IDENTITY)
                .stream()
                .chatResponse()
                .asFlow()
                .collect { response ->
                    val text = response.result?.output?.text
                    when {
                        ToolCallDecisions.isAwaitingDecision(response) -> send(pause(conversation, response))
                        !text.isNullOrEmpty() -> send(ChatEvent.TextDelta(text))
                    }
                }
        }.buffer(Channel.UNLIMITED)

    /** A todo tool that sends the list of every valid todo write to [events]. */
    private fun todoWriteTool(events: SendChannel<ChatEvent>): TodoWriteTool =
        TodoWriteTool.builder().todoEventHandler { events.trySend(it.toTodoUpdate()).getOrThrow() }.build()

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
