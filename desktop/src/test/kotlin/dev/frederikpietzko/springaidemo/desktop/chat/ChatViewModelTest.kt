package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.desktop.fakes.FakeChatApi
import kotlinx.coroutines.CompletableDeferred
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
    private val viewModel = ChatViewModel(api, scriptedIds())

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

        assertEquals([ChatRequest(Persona.CLAIMANT, "Hi", "c-1")], api.requests)
    }

    @Test
    suspend fun `an error event keeps the partial reply, shows the error and ends sending`() {
        val failing = ChatViewModel(
            FakeChatApi { flowOf(ChatEvent.TextDelta("Hel"), ChatEvent.Error("The claims desk is unavailable.")) },
            scriptedIds(),
        )

        failing.send("Hi")

        assertEquals(
            ChatState(
                conversationId = "c-1",
                messages = [user("Hi"), assistant("Hel", error = "The claims desk is unavailable.")],
            ),
            failing.state.value,
        )
    }

    @Test
    suspend fun `a stream without a terminal event is shown as a closed connection and the next message works`() {
        val cutOff = ChatViewModel(
            FakeChatApi { request ->
                if (request.message == "first") flowOf(ChatEvent.TextDelta("Hel")) else flowOf(ChatEvent.Done)
            },
            scriptedIds(),
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

        assertEquals(ChatState(conversationId = "c-1"), viewModel.state.value)
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

        assertEquals([ChatRequest(Persona.ADJUSTER, "Hi", "c-1")], api.requests)
    }

    @Test
    suspend fun `the requests of one conversation share its id`() {
        events.send(ChatEvent.Done)
        events.send(ChatEvent.Done)

        viewModel.send("Hi")
        viewModel.send("Again")

        assertEquals(["c-1", "c-1"], api.requests.map { it.conversationId })
    }

    @Test
    suspend fun `a new conversation clears the messages and the next request carries a new id`() {
        events.send(ChatEvent.Done)
        events.send(ChatEvent.Done)
        viewModel.send("Hi")

        viewModel.newConversation()
        val cleared = viewModel.state.value.messages
        viewModel.send("Hello")

        assertEquals([], cleared)
        assertEquals([user("Hello"), assistant("")], viewModel.state.value.messages)
        assertEquals(["c-1", "c-2"], api.requests.map { it.conversationId })
    }

    @Test
    suspend fun `no new conversation is started while an answer streams`() = coroutineScope {
        val sending = launch { viewModel.send("Hi") }
        viewModel.state.await { it.sending }

        viewModel.newConversation()

        assertEquals([user("Hi"), assistant("")], viewModel.state.value.messages)
        assertEquals("c-1", viewModel.state.value.conversationId)
        events.send(ChatEvent.Done)
        sending.join()
    }

    @Test
    suspend fun `refreshing fills the conversation list`() {
        val listed = ChatViewModel(FakeChatApi(conversations = { [SHED, GNOME] }), scriptedIds())

        listed.refreshConversations()

        assertEquals([SHED, GNOME], listed.state.value.conversations)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    suspend fun `the list is requested again after an answer ends`(succeeds: Boolean) {
        val terminal = if (succeeds) ChatEvent.Done else ChatEvent.Error("The claims desk is unavailable.")
        val listed = ChatViewModel(
            FakeChatApi(conversations = { [SHED] }) { flowOf(ChatEvent.TextDelta("Hel"), terminal) },
            scriptedIds(),
        )

        listed.send("Hi")

        assertEquals([SHED], listed.state.value.conversations)
    }

    @Test
    suspend fun `the input is usable again while the list is still being refreshed`() = coroutineScope {
        val refreshed = CompletableDeferred<List<ConversationSummary>>()
        val listed = ChatViewModel(
            FakeChatApi(conversations = { refreshed.await() }) { flowOf(ChatEvent.Done) },
            scriptedIds(),
        )

        val sending = launch { listed.send("Hi") }
        val unblocked = listed.state.await { !it.sending && it.messages.isNotEmpty() }
        refreshed.complete([SHED])
        sending.join()

        assertEquals([], unblocked.conversations)
        assertEquals([SHED], listed.state.value.conversations)
    }

    @Test
    suspend fun `opening a conversation shows its transcript and continues it`() {
        val opened = FakeChatApi(
            messages = {
                [
                    ConversationMessage(MessageRole.USER, GNOME_TEXT),
                    ConversationMessage(MessageRole.ASSISTANT, "Noted."),
                ]
            },
        ) { flowOf(ChatEvent.Done) }
        val chat = ChatViewModel(opened, scriptedIds())

        chat.open(GNOME)
        val shown = chat.state.value.messages
        chat.send("when was that?")

        assertEquals([user(GNOME_TEXT), assistant("Noted.")], shown)
        assertEquals(["c-7"], opened.openedIds)
        assertEquals([ChatRequest(Persona.CLAIMANT, "when was that?", "c-7")], opened.requests)
    }

    @Test
    suspend fun `a transcript that cannot be loaded shows no messages`() {
        val chat = ChatViewModel(FakeChatApi(), scriptedIds())

        chat.open(GNOME)

        assertEquals([], chat.state.value.messages)
        assertEquals("c-7", chat.state.value.conversationId)
    }

    @Test
    suspend fun `opening a conversation while an answer streams does nothing`() = coroutineScope {
        val sending = launch { viewModel.send("Hi") }
        viewModel.state.await { it.sending }

        viewModel.open(GNOME)

        assertEquals([user("Hi"), assistant("")], viewModel.state.value.messages)
        assertEquals("c-1", viewModel.state.value.conversationId)
        assertEquals([], api.openedIds)
        events.send(ChatEvent.Done)
        sending.join()
    }

    /** Ids `c-1`, `c-2`, ... in order, counted per view model. */
    private fun scriptedIds(): () -> String {
        var next = 0
        return { "c-${++next}" }
    }

    private fun user(text: String) = ChatMessage(ChatMessage.Author.USER, text)

    private fun assistant(text: String, error: String? = null) =
        ChatMessage(author = ChatMessage.Author.ASSISTANT, text = text, error = error)

    private suspend fun StateFlow<ChatState>.await(predicate: (ChatState) -> Boolean): ChatState =
        withTimeout(TIMEOUT) { first(predicate) }

    private companion object {
        /** Bounds every wait, so a missing guard fails the test instead of hanging it. */
        val TIMEOUT = 5.seconds

        const val GNOME_TEXT = "my garden gnome is gone"
        val SHED = ConversationSummary("c-3", "Shed", "2026-11-20T10:00:00Z")
        val GNOME = ConversationSummary("c-7", "Gnome", "2026-11-20T09:00:00Z")
    }
}
