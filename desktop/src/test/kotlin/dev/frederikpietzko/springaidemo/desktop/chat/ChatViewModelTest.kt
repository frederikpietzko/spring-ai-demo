package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.desktop.fakes.FakeChatApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ChatViewModelTest {
    private val events = Channel<ChatEvent>(Channel.UNLIMITED)
    private val api = FakeChatApi { events.receiveAsFlow() }
    private val viewModel = ChatViewModel(api)

    @Test
    suspend fun `the reply grows with each delta and the conversation is ready after done`() = coroutineScope {
        val sending = launch { viewModel.send("Hi") }

        val started = viewModel.state.await { it.sending }
        assertEquals([user("Hi"), assistant("")], started.messages)

        events.send(ChatEvent.TextDelta("Hel"))
        val firstDelta = viewModel.state.await { it.messages.last().text.isNotEmpty() }
        assertEquals([user("Hi"), assistant("Hel")], firstDelta.messages)

        events.send(ChatEvent.TextDelta("lo"))
        val secondDelta = viewModel.state.await { it.messages.last().text.length > "Hel".length }
        assertEquals([user("Hi"), assistant("Hello")], secondDelta.messages)

        events.send(ChatEvent.Done)
        val done = viewModel.state.await { !it.sending }
        assertEquals([user("Hi"), assistant("Hello")], done.messages)
        sending.join()
    }

    @Test
    suspend fun `the request carries the selected persona and the text`() {
        events.send(ChatEvent.Done)

        viewModel.send("Hi")

        assertEquals([ChatRequest(Persona.CLAIMANT, "Hi")], api.requests)
    }

    @Test
    suspend fun `an error event keeps the partial reply, shows the error and ends sending`() {
        val failing = ChatViewModel(
            FakeChatApi { flowOf(ChatEvent.TextDelta("Hel"), ChatEvent.Error("The claims desk is unavailable.")) },
        )

        failing.send("Hi")

        assertEquals(
            ChatState(messages = [user("Hi"), assistant("Hel", error = "The claims desk is unavailable.")]),
            failing.state.value,
        )
    }

    @Test
    suspend fun `a stream without a terminal event is shown as a closed connection and the next message works`() {
        val cutOff = ChatViewModel(
            FakeChatApi { request ->
                if (request.message == "first") flowOf(ChatEvent.TextDelta("Hel")) else flowOf(ChatEvent.Done)
            },
        )

        cutOff.send("first")
        cutOff.send("second")

        assertEquals(
            [
                user("first"),
                assistant("Hel", error = "The connection closed before the answer was complete."),
                user("second"),
                assistant(""),
            ],
            cutOff.state.value.messages,
        )
        assertEquals(false, cutOff.state.value.sending)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    suspend fun `a blank message is ignored`(text: String) {
        withTimeout(TIMEOUT) { viewModel.send(text) }

        assertEquals(ChatState(), viewModel.state.value)
        assertEquals([], api.requests)
    }

    @Test
    suspend fun `no second message is sent while an answer streams`() = coroutineScope {
        val first = launch { viewModel.send("Hi") }
        viewModel.state.await { it.sending }

        withTimeout(TIMEOUT) { viewModel.send("again") }

        assertEquals([user("Hi"), assistant("")], viewModel.state.value.messages)
        assertEquals(1, api.requests.size)
        events.send(ChatEvent.Done)
        first.join()
    }

    @Test
    suspend fun `the next request carries the selected persona`() {
        events.send(ChatEvent.Done)
        viewModel.selectPersona(Persona.ADJUSTER)

        viewModel.send("Hi")

        assertEquals([ChatRequest(Persona.ADJUSTER, "Hi")], api.requests)
    }

    @Test
    suspend fun `a confirmation request is kept as pending under the reply so far and ends sending`() {
        val paused = ChatViewModel(FakeChatApi { flowOf(ChatEvent.TextDelta("Ready to pay."), PAYOUT_CONFIRMATION) })

        paused.send("Pay claim 4711")

        assertEquals(
            ChatState(
                messages = [user("Pay claim 4711"), assistant("Ready to pay.")],
                pendingConfirmation = PAYOUT_CONFIRMATION,
            ),
            paused.state.value,
        )
    }

    @Test
    suspend fun `no new message is sent while a confirmation is pending`() {
        val api = FakeChatApi { flowOf(PAYOUT_CONFIRMATION) }
        val paused = ChatViewModel(api)
        paused.send("Pay claim 4711")
        val before = paused.state.value

        withTimeout(TIMEOUT) { paused.send("Something else") }

        assertEquals(before, paused.state.value)
        assertEquals(1, api.requests.size)
    }

    @Test
    suspend fun `approving sends one decision and appends the continuation to the same reply`() {
        val api = FakeChatApi(decisionReply = { flowOf(ChatEvent.TextDelta("Paid"), ChatEvent.Done) }) {
            flowOf(ChatEvent.TextDelta("Ready to pay. "), PAYOUT_CONFIRMATION)
        }
        val paused = ChatViewModel(api)
        paused.send("Pay claim 4711")

        paused.decide(approved = true)

        assertEquals([ConfirmationDecision("c-1", approved = true)], api.decisions)
        assertEquals(
            ChatState(messages = [user("Pay claim 4711"), assistant("Ready to pay. Paid")]),
            paused.state.value,
        )
    }

    @Test
    suspend fun `denying sends one decision and clears the confirmation`() {
        val api = FakeChatApi { flowOf(PAYOUT_CONFIRMATION) }
        val paused = ChatViewModel(api)
        paused.send("Pay claim 4711")

        paused.decide(approved = false)

        assertEquals([ConfirmationDecision("c-1", approved = false)], api.decisions)
        assertEquals(null, paused.state.value.pendingConfirmation)
    }

    @Test
    suspend fun `a failed decision shows the error under the reply and the conversation is ready again`() {
        val api = FakeChatApi(decisionReply = { flowOf(ChatEvent.Error("The claims desk is unavailable.")) }) {
            flowOf(ChatEvent.TextDelta("Ready to pay."), PAYOUT_CONFIRMATION)
        }
        val paused = ChatViewModel(api)
        paused.send("Pay claim 4711")

        paused.decide(approved = true)

        assertEquals(
            ChatState(
                messages = [
                    user("Pay claim 4711"),
                    assistant("Ready to pay.", error = "The claims desk is unavailable."),
                ],
            ),
            paused.state.value,
        )
    }

    @Test
    suspend fun `a continuation can end in a second confirmation, which is decided like the first`() {
        val second = ChatEvent.ConfirmationRequest("c-2", "issuePayout", """{"claimNumber":4712}""")
        val api = FakeChatApi(
            decisionReply = { decision ->
                if (decision.confirmationId == "c-1") flowOf(second) else flowOf(ChatEvent.Done)
            },
        ) { flowOf(PAYOUT_CONFIRMATION) }
        val paused = ChatViewModel(api)
        paused.send("Pay claims 4711 and 4712")

        paused.decide(approved = true)
        val secondPending = paused.state.value.pendingConfirmation
        paused.decide(approved = true)

        assertEquals(second, secondPending)
        assertEquals(
            [ConfirmationDecision("c-1", approved = true), ConfirmationDecision("c-2", approved = true)],
            api.decisions,
        )
        assertEquals(null, paused.state.value.pendingConfirmation)
    }

    @Test
    suspend fun `a decision without a pending confirmation is ignored`() {
        withTimeout(TIMEOUT) { viewModel.decide(approved = true) }

        assertEquals(ChatState(), viewModel.state.value)
        assertEquals([], api.decisions)
    }

    private fun user(text: String) = ChatMessage(ChatMessage.Author.USER, text)

    private fun assistant(text: String, error: String? = null) =
        ChatMessage(author = ChatMessage.Author.ASSISTANT, text = text, error = error)

    private suspend fun StateFlow<ChatState>.await(predicate: (ChatState) -> Boolean): ChatState =
        withTimeout(TIMEOUT) { first(predicate) }

    private companion object {
        /** Bounds every wait, so a missing guard fails the test instead of hanging it. */
        val TIMEOUT = 5.seconds

        val PAYOUT_CONFIRMATION = ChatEvent.ConfirmationRequest("c-1", "issuePayout", """{"claimNumber":4711}""")
    }
}
