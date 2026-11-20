package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.chat.ADJUSTER_AGENT
import dev.frederikpietzko.springaidemo.backend.chat.Conversations
import dev.frederikpietzko.springaidemo.backend.fraud.ADJUSTER_IDENTITY
import dev.frederikpietzko.springaidemo.backend.fraud.SightingBulletinSearch
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springaicommunity.agent.tools.TodoWriteTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
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
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneOffset

private val log = LoggerFactory.getLogger(AdjusterChat::class.java)

/** What the model receives as the result of a tool call the adjuster denied. */
private const val DENIED = "The adjuster did not approve this call."

/** The answer to a decision on an unknown id, or on one that was decided already. */
private const val NOTHING_WAITING = "Nothing is waiting for this decision."

/**
 * The adjuster agent: answers with the core tools (MCP), the bulletin search, its skills, the `Task` tool and a todo
 * list, as a stream of chat events. A tool call that needs confirmation (`issuePayout`, approving a claim) pauses the
 * reply in [PendingConfirmations] until [decide] continues it. Each turn sees and extends the stored conversation.
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
    private val conversations: Conversations,
) {
    /** Streams the reply to [message]; the model sees the conversation's earlier messages before it. */
    fun stream(conversationId: String, message: String): Flow<ChatEvent> =
        turn(conversationId) {
            val user = UserMessage(message)
            conversations.history(conversationId)
                .also { conversations.remember(conversationId, ADJUSTER_AGENT, message, user) } + user
        }

    /**
     * Carries out a [decision] on a paused reply and streams the continued reply, which may pause again. The paused
     * reply is taken out of [PendingConfirmations] first, so a decision cannot run the tools twice.
     */
    fun decide(decision: ConfirmationDecision): Flow<ChatEvent> {
        val paused = pendingConfirmations.take(decision.confirmationId)
            ?: return flowOf(ChatEvent.Error(NOTHING_WAITING))
        return turn(paused.conversationId) { tools -> runToolCalls(paused, decision.approved, tools) }
    }

    /**
     * Runs the tool calls of the [paused] reply and returns the conversation with their results: approve runs them
     * all, deny answers [DENIED] for those that wait for a decision and runs the others normally.
     */
    private fun runToolCalls(paused: PausedTurn, approved: Boolean, tools: ToolCallbackProvider): List<Message> {
        val waiting = ToolCallDecisions.awaitingDecision(paused.reply)
        val decisions = ToolCallDecisions.toolCallKeys(checkNotNull(paused.reply.result).output).map { key ->
            if (approved || key !in waiting) ToolCallDecision.allow(key) else ToolCallDecision.deny(key, DENIED)
        }
        // The tool loop keeps no state: the resumed calls need the tools and the tool context again.
        val options = ToolCallingChatOptions.builder()
            .toolCallbacks(*tools.toolCallbacks)
            .toolContext(ADJUSTER_IDENTITY)
            .build()
        return toolCallingManager
            .executeToolCalls(Prompt(paused.conversation, options), paused.reply, ToolCallDecisions(decisions))
            .conversationHistory()
    }

    /**
     * One model turn over the messages [conversation] returns (it blocks, so it runs on the IO dispatcher). A
     * `channelFlow`, because tool results never reach the model stream: the todo tool and `payoutStats` send their
     * events into the channel from the thread the tool runs on. The turn's text is stored when the stream ends.
     */
    private fun turn(conversationId: String, conversation: (ToolCallbackProvider) -> List<Message>): Flow<ChatEvent> =
        channelFlow {
            val tools = tools(channel)
            val messages = withContext(Dispatchers.IO) { conversation(tools) }
            val rounds = RoundCapture()
            val reply = StringBuilder()
            adjusterChatClient.prompt()
                .system { it.param("today", LocalDate.now(ZoneOffset.UTC).toString()) }
                .messages(messages)
                .tools(tools)
                .toolContext(ADJUSTER_IDENTITY)
                .advisors(rounds, RoundBreaks())
                .stream()
                // Not content(): only the response metadata tells a paused reply apart.
                .chatResponse()
                .asFlow()
                .collect { response ->
                    val text = response.result?.output?.text
                    when {
                        ToolCallDecisions.isAwaitingDecision(response) ->
                            send(pause(conversationId, rounds.conversation, response))
                        !text.isNullOrEmpty() -> {
                            reply.append(text)
                            send(ChatEvent.TextDelta(text))
                        }
                    }
                }
            if (reply.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    conversations.remember(conversationId, ADJUSTER_AGENT, "$reply", AssistantMessage("$reply"))
                }
            }
        }.buffer(Channel.UNLIMITED) // so the tools' trySend never has to wait

    /**
     * Every tool of the adjuster, for one turn. The todo tool sends each todo write to [events], the wrapped
     * `payoutStats` its table. A provider, so the MCP tools are listed (the first connection to the core) only when
     * the request is sent.
     */
    private fun tools(events: SendChannel<ChatEvent>): ToolCallbackProvider {
        val todoWrite = TodoWriteTool.builder()
            .todoEventHandler { events.trySend(it.toTodoUpdate()).getOrThrow() }
            .build()
        return ToolCallbackProvider {
            val core = coreTools.toolCallbacks.map { tool ->
                if (tool.toolDefinition.name() == PAYOUT_STATS) TableCapture(tool) { events.sendTable(it) } else tool
            }
            (core + ToolCallbacks.from(todoWrite, sightingBulletinSearch) + adjusterSkills + adjusterTask)
                .toTypedArray()
        }
    }

    /** Sends the table of a `payoutStats` [result]; an unexpected result gives no table. */
    private fun SendChannel<ChatEvent>.sendTable(result: String) {
        val table = payoutStatsTable(json, result)
        if (table == null) {
            log.warn("No analytics table: unexpected {} result", PAYOUT_STATS)
            return
        }
        trySend(table).onFailure { log.warn("The analytics table did not reach the reply", it) }
    }

    /** Keeps the paused [reply] with the [conversation] it answers and names the first call that waits. */
    private fun pause(
        conversationId: String,
        conversation: List<Message>,
        reply: ChatResponse,
    ): ChatEvent.ConfirmationRequest {
        val toolCalls = checkNotNull(reply.result) { "a paused reply has a generation" }.output
        val waiting = ToolCallDecisions.toolCallKeys(toolCalls)
            .indexOf(ToolCallDecisions.awaitingDecision(reply).first())
        val call = toolCalls.toolCalls[waiting]
        val confirmationId = pendingConfirmations.add(PausedTurn(conversationId, conversation, reply))
        return ChatEvent.ConfirmationRequest(confirmationId, call.name(), call.arguments())
    }
}
