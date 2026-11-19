package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.desktop.chat.ChatApi
import dev.frederikpietzko.springaidemo.desktop.chat.ChatMessage
import dev.frederikpietzko.springaidemo.desktop.chat.ChatViewModel
import dev.frederikpietzko.springaidemo.desktop.components.DButton
import dev.frederikpietzko.springaidemo.desktop.components.DButtonStyle
import dev.frederikpietzko.springaidemo.desktop.components.DCard
import dev.frederikpietzko.springaidemo.desktop.components.DSelect
import dev.frederikpietzko.springaidemo.desktop.components.DText
import dev.frederikpietzko.springaidemo.desktop.components.DTextField
import dev.frederikpietzko.springaidemo.desktop.components.SelectOption
import dev.frederikpietzko.springaidemo.desktop.theme.Demo
import dev.frederikpietzko.springaidemo.desktop.theme.DemoTheme
import dev.frederikpietzko.springaidemo.desktop.theme.ThemeMode
import kotlinx.coroutines.launch

/** Both personas can be chosen; each is answered by its own agent. */
private val personaOptions: List<SelectOption<Persona>> = [
    SelectOption(Persona.CLAIMANT, "Claimant"),
    SelectOption(Persona.ADJUSTER, "Adjuster"),
]

/**
 * The chat screen: a header with the application name and the persona switch, the conversation list with "New
 * conversation" on the left, the conversation, the pending confirmation (if any) and an input row on the right.
 * [chatApi] talks to the backend (a fake in tests); [themeOverride] forces the light or dark appearance, `null`
 * follows the system.
 */
@Composable
fun App(chatApi: ChatApi, themeOverride: ThemeMode? = null) {
    val viewModel = remember(chatApi) { ChatViewModel(chatApi) }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.refreshConversations() }
    DemoTheme(override = themeOverride) {
        Column(Modifier.fillMaxSize().background(Demo.colors.background)) {
            Header(persona = state.persona, onPersona = viewModel::selectPersona)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ConversationList(
                    conversations = state.conversations,
                    onNew = viewModel::newConversation,
                    onOpen = { conversation -> scope.launch { viewModel.open(conversation) } },
                    modifier = Modifier.width(240.dp).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Conversation(
                        messages = state.messages,
                        sending = state.sending,
                        onConfirm = { scope.launch { viewModel.confirmClaim() } },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    state.pendingConfirmation?.let { pending ->
                        ConfirmationPanel(
                            pending,
                            onDecide = { approved -> scope.launch { viewModel.decide(approved) } },
                        )
                    }
                    InputRow(
                        canSend = !state.sending && state.pendingConfirmation == null,
                        onSend = { text -> scope.launch { viewModel.send(text) } },
                        onPrepare = { scope.launch { viewModel.prepareClaim() } },
                    )
                }
            }
        }
    }
}

/** "New conversation" and the stored conversations by title, newest first; a click opens one. */
@Composable
private fun ConversationList(
    conversations: List<ConversationSummary>,
    onNew: () -> Unit,
    onOpen: (ConversationSummary) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DButton("New conversation", onClick = onNew, modifier = Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(conversations) { conversation ->
                DButton(
                    conversation.title,
                    onClick = { onOpen(conversation) },
                    modifier = Modifier.fillMaxWidth(),
                    style = DButtonStyle.GHOST,
                )
            }
        }
    }
}

@Composable
private fun Header(persona: Persona, onPersona: (Persona) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(Demo.colors.surface)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DText("spring-ai-demo", style = Demo.typography.display)
        Spacer(Modifier.weight(1f))
        DSelect(
            options = personaOptions,
            selectedLabel = personaOptions.first { it.value == persona }.label,
            onSelect = onPersona,
            modifier = Modifier.width(160.dp),
        )
    }
}

/**
 * The messages, scrolled to the newest one whenever the conversation changes (not covered by a test). Only the claim
 * card of the last message can be confirmed: the backend files only the latest prepared claim.
 */
@Composable
private fun Conversation(messages: List<ChatMessage>, sending: Boolean, onConfirm: () -> Unit, modifier: Modifier) {
    if (messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            DText("Describe the kaiju damage to get started.", color = Demo.colors.inkMuted)
        }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages) { listState.animateScrollToItem(messages.lastIndex) }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { message ->
            MessageBubble(
                message = message,
                canConfirm = message === messages.last() && !sending,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, canConfirm: Boolean, onConfirm: () -> Unit) {
    val fromUser = message.author == ChatMessage.Author.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .background(if (fromUser) Demo.colors.surfaceRaised else Demo.colors.surface, Demo.shapes.medium)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val hasContent = message.claimCard != null || message.analyticsTable != null
            when {
                message.text.isNotEmpty() -> DText(message.text)
                message.error == null && !hasContent -> DText("…", color = Demo.colors.inkMuted)
            }
            message.analyticsTable?.let { AnalyticsTable(it) }
            message.claimCard?.let { ClaimCard(it, canConfirm, onConfirm) }
            message.error?.let { DText(it, style = Demo.typography.caption, color = Demo.colors.danger) }
        }
    }
}

/** A tool call that waits for the user: the tool, its arguments and the two choices. */
@Composable
private fun ConfirmationPanel(pending: ChatEvent.ConfirmationRequest, onDecide: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Demo.colors.surfaceRaised, Demo.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DText("Confirm ${pending.toolName}", style = Demo.typography.title)
        DText(pending.arguments, style = Demo.typography.caption, color = Demo.colors.inkMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DButton("Approve", onClick = { onDecide(true) }, style = DButtonStyle.PRIMARY)
            DButton("Deny", onClick = { onDecide(false) })
        }
    }
}

/** A prepared claim to check: its facts, the damage items with their amounts in minor units, the total and Confirm. */
@Composable
private fun ClaimCard(card: ChatEvent.ClaimCard, canConfirm: Boolean, onConfirm: () -> Unit) {
    DCard(Modifier.fillMaxWidth(), highlighted = true) {
        DText("Claim to confirm", style = Demo.typography.label)
        Fact("Policy", card.policyNumber)
        Fact("District", card.district)
        Fact("Incident", card.incidentAt)
        Fact("Kaiju class", card.kaijuClass.toString())
        DText(card.description)
        card.damageItems.forEach { item -> Fact(item.category, item.description, item.amountMinor.toString()) }
        Fact("Total", card.totalMinor.toString())
        DButton("Confirm", onClick = onConfirm, style = DButtonStyle.PRIMARY, enabled = canConfirm)
    }
}

/** A table a tool computed: the title, a header line and one line per row, every cell as text in an equal column. */
@Composable
private fun AnalyticsTable(table: ChatEvent.AnalyticsTable) {
    DCard(Modifier.fillMaxWidth()) {
        DText(table.title, style = Demo.typography.label)
        TableRow(table.columns, color = Demo.colors.inkMuted)
        table.rows.forEach { TableRow(it) }
    }
}

@Composable
private fun TableRow(cells: List<String>, color: Color = Demo.colors.ink) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.forEach { DText(it, color = color, modifier = Modifier.weight(1f)) }
    }
}

/** One line of the claim card: a muted [label] and its [values]. */
@Composable
private fun Fact(label: String, vararg values: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DText(label, color = Demo.colors.inkMuted, modifier = Modifier.width(96.dp))
        values.forEach { DText(it) }
    }
}

/**
 * The message input with "Send" and "Prepare claim". Keeps a 16 dp transparent margin, so the window background shows
 * around it (see the render tests).
 */
@Composable
private fun InputRow(canSend: Boolean, onSend: (String) -> Unit, onPrepare: () -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            placeholder = "Type a message",
        )
        DButton(
            "Send",
            onClick = {
                onSend(input)
                input = ""
            },
            style = DButtonStyle.PRIMARY,
            enabled = canSend,
        )
        DButton("Prepare claim", onClick = onPrepare, style = DButtonStyle.SECONDARY, enabled = canSend)
    }
}
