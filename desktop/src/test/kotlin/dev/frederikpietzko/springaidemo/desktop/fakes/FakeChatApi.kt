package dev.frederikpietzko.springaidemo.desktop.fakes

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.desktop.chat.ChatApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chat API for tests: records every request and answers with the flow from [reply], by default the deltas "Hel" and
 * "lo" and done. A `Channel`-backed [reply] lets a test observe the states between events.
 */
class FakeChatApi(
    private val reply: (ChatRequest) -> Flow<ChatEvent> = {
        flowOf(ChatEvent.TextDelta("Hel"), ChatEvent.TextDelta("lo"), ChatEvent.Done)
    },
) : ChatApi {
    private val received = CopyOnWriteArrayList<ChatRequest>()

    /** Every request sent so far, in order. */
    val requests: List<ChatRequest> get() = received.toList()

    override fun send(request: ChatRequest): Flow<ChatEvent> {
        received.add(request)
        return reply(request)
    }
}
