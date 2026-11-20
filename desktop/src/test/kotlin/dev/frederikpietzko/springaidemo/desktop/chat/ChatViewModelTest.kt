package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ClaimCardItem
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.chatprotocol.TodoItem
import dev.frederikpietzko.springaidemo.chatprotocol.TodoStatus
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
import kotlin.test.assertTrue
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

    @Test
    suspend fun `a claim card is kept on the reply and done ends it`() {
        val carded = ChatViewModel(FakeChatApi { flowOf(CARD, ChatEvent.Done) }, scriptedIds())

        carded.send("Hi")

        assertEquals(
            ChatState(
                conversationId = "c-1",
                messages = [user("Hi"), ChatMessage(ChatMessage.Author.ASSISTANT, "", claimCard = CARD)],
            ),
            carded.state.value,
        )
    }

    @Test
    suspend fun `an analytics table is kept on the reply next to the text and done ends it`() {
        val tabled = ChatViewModel(
            FakeChatApi { flowOf(TABLE, ChatEvent.TextDelta("Harbour leads"), ChatEvent.Done) },
            scriptedIds(),
        )

        tabled.send("Payouts per district?")

        assertEquals(
            ChatState(
                conversationId = "c-1",
                messages = [
                    user("Payouts per district?"),
                    ChatMessage(ChatMessage.Author.ASSISTANT, "Harbour leads", analyticsTable = TABLE),
                ],
            ),
            tabled.state.value,
        )
    }

    @Test
    suspend fun `a reply of only an analytics table ends at done without an error`() {
        val tabled = ChatViewModel(FakeChatApi { flowOf(TABLE, ChatEvent.Done) }, scriptedIds())

        tabled.send("Payouts per district?")

        assertEquals(
            [user("Payouts per district?"), ChatMessage(ChatMessage.Author.ASSISTANT, "", analyticsTable = TABLE)],
            tabled.state.value.messages,
        )
        assertEquals(false, tabled.state.value.sending)
    }

    @Test
    suspend fun `text before a claim card stays on the reply next to the card`() {
        val carded = ChatViewModel(
            FakeChatApi { flowOf(ChatEvent.TextDelta("Here is your claim"), CARD, ChatEvent.Done) },
            scriptedIds(),
        )

        carded.send("Hi")

        assertEquals(
            [user("Hi"), ChatMessage(ChatMessage.Author.ASSISTANT, "Here is your claim", claimCard = CARD)],
            carded.state.value.messages,
        )
        assertEquals(false, carded.state.value.sending)
    }

    @Test
    suspend fun `a prepare sends the prepare action with an empty message and shows the card`() {
        val api = FakeChatApi { flowOf(CARD, ChatEvent.Done) }
        val claims = ChatViewModel(api, scriptedIds())

        claims.prepareClaim()

        assertEquals([ChatRequest(Persona.CLAIMANT, "", "c-1", ChatAction.PREPARE_CLAIM)], api.requests)
        assertEquals(
            [user("Prepare my claim"), ChatMessage(ChatMessage.Author.ASSISTANT, "", claimCard = CARD)],
            claims.state.value.messages,
        )
    }

    @Test
    suspend fun `a confirm sends the confirm action and shows the answer below the card`() {
        val api = FakeChatApi { request ->
            when (request.action) {
                ChatAction.PREPARE_CLAIM -> flowOf(CARD, ChatEvent.Done)
                else -> flowOf(ChatEvent.TextDelta("Your claim number is 4714."), ChatEvent.Done)
            }
        }
        val claims = ChatViewModel(api, scriptedIds())

        claims.prepareClaim()
        claims.confirmClaim()

        assertEquals(ChatRequest(Persona.CLAIMANT, "", "c-1", ChatAction.CONFIRM_CLAIM), api.requests.last())
        assertEquals(
            [
                user("Prepare my claim"),
                ChatMessage(ChatMessage.Author.ASSISTANT, "", claimCard = CARD),
                user("Confirm claim"),
                assistant("Your claim number is 4714."),
            ],
            claims.state.value.messages,
        )
    }

    @Test
    suspend fun `message, prepare and confirm share one conversation id`() {
        val api = FakeChatApi { flowOf(ChatEvent.Done) }
        val claims = ChatViewModel(api, scriptedIds())

        claims.send("A kaiju sat on my car")
        claims.prepareClaim()
        claims.confirmClaim()

        val [message, prepare, confirm] = api.requests
        assertTrue(!message.conversationId.isNullOrBlank(), message.toString())
        assertEquals(message.conversationId, prepare.conversationId)
        assertEquals(message.conversationId, confirm.conversationId)
        assertEquals(
            [ChatAction.MESSAGE, ChatAction.PREPARE_CLAIM, ChatAction.CONFIRM_CLAIM],
            api.requests.map { it.action },
        )
    }

    @Test
    suspend fun `a prepare in a new conversation carries the new id`() {
        val api = FakeChatApi { flowOf(ChatEvent.Done) }
        val claims = ChatViewModel(api, scriptedIds())

        claims.newConversation()
        claims.prepareClaim()

        assertEquals(["c-2"], api.requests.map { it.conversationId })
    }

    @Test
    suspend fun `a prepare in a reopened conversation carries its id`() {
        val api = FakeChatApi { flowOf(ChatEvent.Done) }
        val claims = ChatViewModel(api, scriptedIds())

        claims.open(GNOME)
        claims.prepareClaim()

        assertEquals(["c-7"], api.requests.map { it.conversationId })
    }

    @Test
    suspend fun `no prepare or confirm is sent while an answer streams`() = coroutineScope {
        val first = launch { viewModel.send("Hi") }
        viewModel.state.await { it.sending }

        withTimeout(TIMEOUT) {
            viewModel.prepareClaim()
            viewModel.confirmClaim()
        }

        assertEquals([user("Hi"), assistant("")], viewModel.state.value.messages)
        assertEquals(1, api.requests.size)
        events.send(ChatEvent.Done)
        first.join()
    }

    @Test
    suspend fun `the list is requested again after a prepare`() {
        val listed = ChatViewModel(
            FakeChatApi(conversations = { [SHED] }) { flowOf(CARD, ChatEvent.Done) },
            scriptedIds(),
        )

        listed.prepareClaim()

        assertEquals([SHED], listed.state.value.conversations)
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

        assertEquals([ChatRequest(Persona.ADJUSTER, "Hi", "c-2")], api.requests)
    }

    @Test
    suspend fun `selecting the adjuster starts a new conversation and lists the adjuster's conversations`() {
        val listing = FakeChatApi()
        val chat = ChatViewModel(listing, scriptedIds())
        chat.send("Hello")

        chat.selectPersona(Persona.ADJUSTER)

        assertEquals("c-2", chat.state.value.conversationId)
        assertEquals([], chat.state.value.messages)
        assertEquals(Persona.ADJUSTER, listing.listedFor.last())
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

    @Test
    suspend fun `a confirmation request is kept as pending under the reply so far and ends sending`() {
        val paused = ChatViewModel(
            FakeChatApi { flowOf(ChatEvent.TextDelta("Ready to pay."), PAYOUT_CONFIRMATION) },
            scriptedIds(),
        )

        paused.send("Pay claim 4711")

        assertEquals(
            ChatState(
                conversationId = "c-1",
                messages = [user("Pay claim 4711"), assistant("Ready to pay.")],
                pendingConfirmation = PAYOUT_CONFIRMATION,
            ),
            paused.state.value,
        )
    }

    @Test
    suspend fun `no new message is sent while a confirmation is pending`() {
        val api = FakeChatApi { flowOf(PAYOUT_CONFIRMATION) }
        val paused = ChatViewModel(api, scriptedIds())
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
        val paused = ChatViewModel(api, scriptedIds())
        paused.send("Pay claim 4711")

        paused.decide(approved = true)

        assertEquals([ConfirmationDecision("c-1", approved = true)], api.decisions)
        assertEquals(
            ChatState(
                conversationId = "c-1",
                messages = [user("Pay claim 4711"), assistant("Ready to pay.\n\nPaid")],
            ),
            paused.state.value,
        )
    }

    @Test
    suspend fun `denying sends one decision and clears the confirmation`() {
        val api = FakeChatApi { flowOf(PAYOUT_CONFIRMATION) }
        val paused = ChatViewModel(api, scriptedIds())
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
        val paused = ChatViewModel(api, scriptedIds())
        paused.send("Pay claim 4711")

        paused.decide(approved = true)

        assertEquals(
            ChatState(
                conversationId = "c-1",
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
        val paused = ChatViewModel(api, scriptedIds())
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

        assertEquals(ChatState(conversationId = "c-1"), viewModel.state.value)
        assertEquals([], api.decisions)
    }

    @Test
    suspend fun `a new conversation and an opened one are ignored while a confirmation is pending`() {
        val api = FakeChatApi(conversations = { [SHED] }) { flowOf(PAYOUT_CONFIRMATION) }
        val paused = ChatViewModel(api, scriptedIds())
        paused.send("Pay claim 4711")
        val before = paused.state.value

        paused.newConversation()
        paused.open(SHED)

        assertEquals(before, paused.state.value)
        assertEquals([], api.openedIds)
    }

    @Test
    suspend fun `todo updates fill the todo list and neither end nor change the reply`() {
        val chat = ChatViewModel(
            FakeChatApi { flowOf(ChatEvent.TodoUpdate(PLAN), ChatEvent.TextDelta("Working."), ChatEvent.Done) },
            scriptedIds(),
        )

        chat.send("Review claim 4711")

        val state = chat.state.value
        assertEquals(PLAN, state.todos)
        assertEquals([user("Review claim 4711"), assistant("Working.")], state.messages)
        assertEquals(false, state.sending)
    }

    @Test
    suspend fun `a later todo update replaces the list`() {
        val chat = ChatViewModel(
            FakeChatApi { flowOf(ChatEvent.TodoUpdate(PLAN), ChatEvent.TodoUpdate(PROGRESS), ChatEvent.Done) },
            scriptedIds(),
        )

        chat.send("Review claim 4711")

        assertEquals(PROGRESS, chat.state.value.todos)
    }

    @Test
    suspend fun `a new conversation clears the todo list`() {
        val chat = planningChat()
        chat.send("Review claim 4711")

        chat.newConversation()

        assertEquals([], chat.state.value.todos)
    }

    @Test
    suspend fun `selecting another persona clears the todo list`() {
        val chat = planningChat()
        chat.selectPersona(Persona.ADJUSTER)
        chat.send("Review claim 4711")

        chat.selectPersona(Persona.CLAIMANT)

        assertEquals([], chat.state.value.todos)
    }

    @Test
    suspend fun `opening a conversation clears the todo list and shows the transcript`() {
        val chat = planningChat(messages = { [ConversationMessage(MessageRole.USER, GNOME_TEXT)] })
        chat.send("Review claim 4711")

        chat.open(GNOME)

        assertEquals([], chat.state.value.todos)
        assertEquals([user(GNOME_TEXT)], chat.state.value.messages)
    }

    /** A chat whose every reply is one todo update of [PLAN] and done. */
    private fun planningChat(messages: suspend (String) -> List<ConversationMessage> = { [] }) = ChatViewModel(
        FakeChatApi(messages = messages) { flowOf(ChatEvent.TodoUpdate(PLAN), ChatEvent.Done) },
        scriptedIds(),
    )

    private fun user(text: String) = ChatMessage(ChatMessage.Author.USER, text)

    private fun assistant(text: String, error: String? = null) =
        ChatMessage(author = ChatMessage.Author.ASSISTANT, text = text, error = error)

    private suspend fun StateFlow<ChatState>.await(predicate: (ChatState) -> Boolean): ChatState =
        withTimeout(TIMEOUT) { first(predicate) }

    private companion object {
        /** Bounds every wait, so a missing guard fails the test instead of hanging it. */
        val TIMEOUT = 5.seconds

        const val GNOME_TEXT = "my garden gnome is gone"
        val TABLE = ChatEvent.AnalyticsTable("Payouts by district", ["District", "Payouts"], [["HARBOUR", "3"]])

        val CARD = ChatEvent.ClaimCard(
            policyNumber = "KP-1001",
            district = "HARBOUR",
            incidentAt = "2026-11-19T15:00:00Z",
            kaijuClass = 3,
            description = "Kaiju stepped on the car",
            damageItems = [ClaimCardItem("VEHICLE", "Car roof", 800000)],
        )
        val SHED = ConversationSummary("c-3", "Shed", "2026-11-20T10:00:00Z")
        val GNOME = ConversationSummary("c-7", "Gnome", "2026-11-20T09:00:00Z")
        val PAYOUT_CONFIRMATION = ChatEvent.ConfirmationRequest("c-1", "issuePayout", """{"claimNumber":4711}""")
        val PLAN: List<TodoItem> = [
            TodoItem("Verify the policy", TodoStatus.IN_PROGRESS, "Verifying the policy"),
            TodoItem("Check coverage", TodoStatus.PENDING, "Checking coverage"),
        ]
        val PROGRESS: List<TodoItem> = [
            TodoItem("Verify the policy", TodoStatus.COMPLETED, "Verifying the policy"),
            TodoItem("Check coverage", TodoStatus.IN_PROGRESS, "Checking coverage"),
        ]
    }
}
