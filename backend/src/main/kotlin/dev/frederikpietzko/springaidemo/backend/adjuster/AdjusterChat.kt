package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.fraud.SightingBulletinSearch
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.springaicommunity.agent.tools.TodoWriteTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneOffset

private val log = LoggerFactory.getLogger(AdjusterChat::class.java)

/**
 * Who calls the core tools. It travels in the tool context only, never in a message, and is forwarded to `core-mcp`
 * as MCP `_meta` ([identityMeta]). The adjuster connection's token proves the persona to the core (ADR-0009). The
 * adjuster's subagents ([AdjusterSubagents]) call the core tools with the same identity.
 */
val ADJUSTER_IDENTITY: Map<String, Any> = mapOf("actor" to "adjuster-agent")

/** What the model receives as the result of a payout the adjuster denied. */
private const val DENIED_PAYOUT = "The adjuster did not approve this payout."

/** What the model receives as the result of any other call the adjuster denied (approving a claim). */
private const val DENIED = "The adjuster did not approve this call."

/** The core tool that pays out a claim. */
private const val ISSUE_PAYOUT = "issuePayout"

/** The answer to a decision on an unknown id, or on one that was decided already. */
private const val NOTHING_WAITING = "Nothing is waiting for this decision."

/**
 * The adjuster agent: answers one adjuster message with the core tools, the sighting bulletin search, its skills, the
 * `Task` tool that delegates to its subagents and its own todo list, as a stream of chat events: text deltas, a
 * [ChatEvent.TodoUpdate] for every todo write, an [ChatEvent.AnalyticsTable] for each successful `payoutStats` call of
 * a turn (not one in a batch [decide] resumes), and a [ChatEvent.ConfirmationRequest] when the reply calls a tool
 * needing confirmation (`issuePayout`, or `updateClaimStatus` to `APPROVED`); the paused reply waits in
 * [PendingConfirmations] until [decide] runs or skips its tool calls and continues the reply.
 */
@Service
class AdjusterChat(
    @Qualifier("adjusterChatClient") private val adjusterChatClient: ChatClient,
    @Qualifier("adjusterCoreTools") private val coreTools: ToolCallbackProvider,
    @Qualifier("adjusterSkills") private val adjusterSkills: ToolCallback,
    @Qualifier("adjusterTask") private val adjusterTask: ToolCallback,
    private val toolCallingManager: ToolCallingManager,
    private val pendingConfirmations: PendingConfirmations,
    private val sightingBulletinSearch: SightingBulletinSearch,
    private val json: JsonMapper,
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
     * bulletin search and `Task`) and the tool context again: a tool missing here fails with "No ToolCallback found".
     */
    private fun runToolCalls(paused: PausedTurn, approved: Boolean, todoWrite: TodoWriteTool): List<Message> {
        val reply = checkNotNull(paused.reply.result) { "paused reply" }.output
        val keys = ToolCallDecisions.toolCallKeys(reply)
        val waiting = ToolCallDecisions.awaitingDecision(paused.reply)
        val decisions = keys.mapIndexed { index, key ->
            when {
                approved || key !in waiting -> ToolCallDecision.allow(key)
                reply.toolCalls[index].name() == ISSUE_PAYOUT -> ToolCallDecision.deny(key, DENIED_PAYOUT)
                else -> ToolCallDecision.deny(key, DENIED)
            }
        }
        val options = ToolCallingChatOptions.builder()
            .toolCallbacks(
                *coreTools.toolCallbacks,
                *ToolCallbacks.from(todoWrite, sightingBulletinSearch),
                adjusterSkills,
                adjusterTask,
            )
            .toolContext(ADJUSTER_IDENTITY)
            .build()
        return toolCallingManager
            .executeToolCalls(Prompt(paused.conversation, options), paused.reply, ToolCallDecisions(decisions))
            .conversationHistory()
    }

    /**
     * One model turn over [conversation], first turn or resumed; the system prompt gets today's date (UTC). The whole
     * request is built inside the flow: `.stream()` lists the MCP tools right away (the first connection to the core)
     * and checks the tool names, and a failure there must reach the caller as a failed flow, not as an exception of
     * this call. `chatResponse()`, not `content()`: only the response metadata tells a paused reply apart, and its last
     * chunk carries the tool calls but no text. A `channelFlow`, because tool calls never reach this stream: the turn's
     * own todo tool sends each todo update, and the wrapped `payoutStats` ([coreToolsWithTables]) its table, into the
     * channel from the thread the tool runs on (the unlimited buffer means `trySend` never has to wait). The tool runs
     * before the next model round streams, so its table comes before the text the model writes after the call. The
     * skills, the bulletin search and `Task` are default tools of the client; the core tools and the todo tool come
     * with each request.
     */
    private fun turn(conversation: List<Message>): Flow<ChatEvent> =
        channelFlow {
            val rounds = RoundCapture()
            adjusterChatClient.prompt()
                .system { it.param("today", LocalDate.now(ZoneOffset.UTC).toString()) }
                .messages(conversation)
                .tools(coreToolsWithTables(), todoWriteTool(channel))
                .toolContext(ADJUSTER_IDENTITY)
                .advisors(rounds)
                .stream()
                .chatResponse()
                .asFlow()
                .collect { response ->
                    val text = response.result?.output?.text
                    when {
                        ToolCallDecisions.isAwaitingDecision(response) -> send(pause(rounds.conversation, response))
                        !text.isNullOrEmpty() -> send(ChatEvent.TextDelta(text))
                    }
                }
        }.buffer(Channel.UNLIMITED)

    /** A todo tool that sends the list of every valid todo write to [events]. */
    private fun todoWriteTool(events: SendChannel<ChatEvent>): TodoWriteTool =
        TodoWriteTool.builder().todoEventHandler { events.trySend(it.toTodoUpdate()).getOrThrow() }.build()

    /**
     * The core tools for one request, with `payoutStats` wrapped so that its result also becomes an analytics table of
     * this reply. A provider lambda, so the MCP tools are still listed only when the request is sent.
     */
    private fun ProducerScope<ChatEvent>.coreToolsWithTables(): ToolCallbackProvider =
        ToolCallbackProvider {
            coreTools.toolCallbacks
                .map { tool ->
                    if (tool.toolDefinition.name() == PAYOUT_STATS) TableCapture(tool) { sendTable(it) } else tool
                }
                .toTypedArray()
        }

    /** Sends the table of a `payoutStats` [result] to this reply; an unexpected result gives no table. */
    private fun ProducerScope<ChatEvent>.sendTable(result: String) {
        val table = payoutStatsTable(json, result)
        if (table == null) {
            log.warn("No analytics table: unexpected {} result", PAYOUT_STATS)
            return
        }
        trySend(table).onFailure { log.warn("The analytics table did not reach the reply", it) }
    }

    /**
     * Keeps the paused [reply] with the [conversation] it answers (the tool rounds of the turn before the pause
     * included) and names the first tool call that waits for a decision.
     */
    private fun pause(conversation: List<Message>, reply: ChatResponse): ChatEvent.ConfirmationRequest {
        val toolCalls = checkNotNull(reply.result) { "a paused reply has a generation" }.output
        val waiting = ToolCallDecisions.toolCallKeys(toolCalls)
            .indexOf(ToolCallDecisions.awaitingDecision(reply).first())
        val call = toolCalls.toolCalls[waiting]
        val confirmationId = pendingConfirmations.add(PausedTurn(conversation, reply))
        return ChatEvent.ConfirmationRequest(confirmationId, call.name(), call.arguments())
    }
}

/**
 * Records what the model is asked in each round of one adjuster turn. It sits inside the tool-calling loop, which runs
 * every round through the rest of the chain with the conversation so far: the user message, then each earlier reply
 * with its tool calls and their results. So the last round's messages are the conversation a paused reply answers;
 * without them a resumed turn would see only the original message and the paused calls, and the model would describe
 * the earlier tool rounds as never having happened. One instance per turn: it holds that turn's state.
 */
private class RoundCapture : StreamAdvisor {
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
