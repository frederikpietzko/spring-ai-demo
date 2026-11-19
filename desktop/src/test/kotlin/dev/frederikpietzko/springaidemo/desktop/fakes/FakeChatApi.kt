package dev.frederikpietzko.springaidemo.desktop.fakes

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.desktop.chat.ChatApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chat API for tests: records every request and decision. A request is answered with the flow from [reply], by
 * default the deltas "Hel" and "lo" and done; a decision with the flow from [decisionReply], by default done. A
 * `Channel`-backed reply lets a test observe the states between events. The conversation list comes from
 * [conversations] and a transcript from [messages], both empty by default; opened ids are recorded. [reply] comes
 * last so existing tests can pass it as a trailing lambda.
 */
class FakeChatApi(
    private val decisionReply: (ConfirmationDecision) -> Flow<ChatEvent> = { flowOf(ChatEvent.Done) },
    private val conversations: suspend () -> List<ConversationSummary> = { [] },
    private val messages: suspend (String) -> List<ConversationMessage> = { [] },
    private val reply: (ChatRequest) -> Flow<ChatEvent> = {
        flowOf(ChatEvent.TextDelta("Hel"), ChatEvent.TextDelta("lo"), ChatEvent.Done)
    },
) : ChatApi {
    private val received = CopyOnWriteArrayList<ChatRequest>()
    private val receivedDecisions = CopyOnWriteArrayList<ConfirmationDecision>()
    private val opened = CopyOnWriteArrayList<String>()

    /** Every request sent so far, in order. */
    val requests: List<ChatRequest> get() = received.toList()

    /** Every decision sent so far, in order. */
    val decisions: List<ConfirmationDecision> get() = receivedDecisions.toList()

    /** Every conversation id whose transcript was requested, in order. */
    val openedIds: List<String> get() = opened.toList()

    override fun send(request: ChatRequest): Flow<ChatEvent> {
        received.add(request)
        return reply(request)
    }

    override fun decide(decision: ConfirmationDecision): Flow<ChatEvent> {
        receivedDecisions.add(decision)
        return decisionReply(decision)
    }

    override suspend fun conversations(): List<ConversationSummary> = conversations.invoke()

    override suspend fun messages(id: String): List<ConversationMessage> {
        opened.add(id)
        return messages.invoke(id)
    }
}
