package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import dev.frederikpietzko.springaidemo.desktop.theme.KaijuMascot
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.desktop.chat.ChatApi
import dev.frederikpietzko.springaidemo.desktop.chat.ChatMessage
import dev.frederikpietzko.springaidemo.desktop.chat.ChatState
import dev.frederikpietzko.springaidemo.desktop.chat.ChatViewModel
import dev.frederikpietzko.springaidemo.desktop.chat.markdown
import dev.frederikpietzko.springaidemo.desktop.components.DButton
import dev.frederikpietzko.springaidemo.desktop.components.DButtonStyle
import dev.frederikpietzko.springaidemo.desktop.components.DSelect
import dev.frederikpietzko.springaidemo.desktop.components.DText
import dev.frederikpietzko.springaidemo.desktop.components.DTextField
import dev.frederikpietzko.springaidemo.desktop.components.SelectOption
import dev.frederikpietzko.springaidemo.desktop.control.PersonaMenu
import dev.frederikpietzko.springaidemo.desktop.control.PressFeedback
import dev.frederikpietzko.springaidemo.desktop.control.PressTarget
import dev.frederikpietzko.springaidemo.desktop.control.pressFeedback
import dev.frederikpietzko.springaidemo.desktop.theme.Demo
import dev.frederikpietzko.springaidemo.desktop.theme.DemoTheme
import dev.frederikpietzko.springaidemo.desktop.theme.ThemeMode
import kotlinx.coroutines.launch

/** The message input grows up to this many lines, then scrolls inside. */
private const val INPUT_MAX_LINES = 5

/** Both personas can be chosen; each is answered by its own agent. */
private val personaOptions: List<SelectOption<Persona>> = [
    SelectOption(Persona.CLAIMANT, "Claimant"),
    SelectOption(Persona.ADJUSTER, "Adjuster"),
]

/**
 * The chat screen: a header with the mascot, the application name and the persona switch, the conversation list with
 * "New conversation" on the left, the conversation, the pending confirmation (if any) and an input row in the middle,
 * and the adjuster's todo list on the right while it has items.
 * [chatApi] talks to the backend (a fake in tests); [themeOverride] forces the light or dark appearance, `null`
 * follows the system. [titleBar] lets the window blend the header into its title bar: the room to leave for the
 * window controls and a wrapper that makes the header drag the window.
 */
@Composable
fun App(chatApi: ChatApi, themeOverride: ThemeMode? = null, titleBar: TitleBar = TitleBar()) {
    val viewModel = remember(chatApi) { ChatViewModel(chatApi) }
    App(viewModel, themeOverride = themeOverride, titleBar = titleBar)
}

/**
 * The chat screen over a prepared [viewModel], so `main` can share it with the control endpoint, and [feedback], which
 * shows the control the endpoint presses (by default one of its own that nothing presses), and [personaMenu], the open
 * state of the persona drop-down that the endpoint opens too (by default its own). The other parameters are
 * those of the `App(chatApi)` overload; the enablement of every control comes from the [ChatState] rules.
 */
@Composable
fun App(
    viewModel: ChatViewModel,
    feedback: PressFeedback = remember { PressFeedback() },
    personaMenu: PersonaMenu = remember { PersonaMenu() },
    themeOverride: ThemeMode? = null,
    titleBar: TitleBar = TitleBar(),
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.refreshConversations() }
    DemoTheme(override = themeOverride) {
        Column(Modifier.fillMaxSize().background(Demo.colors.background)) {
            titleBar.container {
                Header(
                    persona = state.persona,
                    enabled = state.canSelectPersona,
                    startInset = titleBar.startInset,
                    onPersona = { persona -> scope.launch { viewModel.selectPersona(persona) } },
                    feedback = feedback,
                    personaMenu = personaMenu,
                )
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ConversationList(
                    state = state,
                    onNew = viewModel::newConversation,
                    onOpen = { conversation -> scope.launch { viewModel.open(conversation) } },
                    feedback = feedback,
                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                )
                VerticalDivider()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Conversation(
                        messages = state.messages,
                        persona = state.persona,
                        canConfirm = state.canConfirmClaim,
                        onConfirm = { scope.launch { viewModel.confirmClaim() } },
                        feedback = feedback,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    state.pendingConfirmation?.let { pending ->
                        ConfirmationPanel(
                            pending,
                            enabled = state.canDecide,
                            onDecide = { approved -> scope.launch { viewModel.decide(approved) } },
                            feedback = feedback,
                        )
                    }
                    InputRow(
                        state = state,
                        feedback = feedback,
                        onInput = viewModel::setInput,
                        onSubmit = { scope.launch { viewModel.submitInput() } },
                        onPrepare = { scope.launch { viewModel.prepareClaim() } },
                    )
                }
                if (state.todos.isNotEmpty()) {
                    VerticalDivider()
                    TodoPanel(state.todos, Modifier.width(280.dp).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * How the header blends into the window's title bar: [startInset] leaves room for window controls drawn over the
 * header, [container] wraps the header (e.g. to make it drag the window). The default is a plain header.
 */
class TitleBar(
    val startInset: Dp = 0.dp,
    val container: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
)

@Composable
private fun VerticalDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Demo.colors.border))
}

/**
 * "New conversation" and the stored conversations by title, newest first; a click opens one. The open conversation
 * is marked with the accent bar (and as selected for accessibility).
 */
@Composable
private fun ConversationList(
    state: ChatState,
    onNew: () -> Unit,
    onOpen: (ConversationSummary) -> Unit,
    feedback: PressFeedback,
    modifier: Modifier,
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DButton(
            "New conversation",
            onClick = onNew,
            modifier = Modifier.fillMaxWidth().pressFeedback(feedback, PressTarget.NewConversation),
            style = DButtonStyle.PRIMARY,
            enabled = state.canStartNewConversation,
        )
        SectionLabel("Conversations", Modifier.padding(start = 4.dp, top = 8.dp))
        if (state.conversations.isEmpty()) {
            DText(
                "No conversations yet.",
                style = Demo.typography.caption,
                color = Demo.colors.inkMuted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.conversations) { conversation ->
                ConversationRow(
                    title = conversation.title,
                    selected = conversation.id == state.conversationId,
                    enabled = state.canOpenConversation,
                    modifier = Modifier.pressFeedback(feedback, PressTarget.Conversation(conversation.id)),
                ) { onOpen(conversation) }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = Demo.colors
    val shape = Demo.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected || hovered) colors.surfaceRaised else Color.Transparent, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .pointerHoverIcon(PointerIcon.Hand)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) colors.accentGradient else SolidColor(Color.Transparent)),
        )
        DText(
            title,
            maxLines = 1,
            color = if (selected) colors.ink else colors.inkMuted,
            style = if (selected) Demo.typography.body.copy(fontWeight = FontWeight.Medium) else Demo.typography.body,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** The mascot, the application name with the desk being served, and the persona switch; underlined by the accent. */
@Composable
private fun Header(
    persona: Persona,
    enabled: Boolean,
    startInset: Dp,
    onPersona: (Persona) -> Unit,
    feedback: PressFeedback,
    personaMenu: PersonaMenu,
) {
    val menuOpen by personaMenu.isOpen.collectAsState()
    Column(Modifier.fillMaxWidth().background(Demo.colors.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp + startInset, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(Demo.colors.surfaceRaised, Demo.shapes.medium).padding(4.dp),
            ) {
                KaijuMascot(Modifier.fillMaxSize())
            }
            Column {
                DText(APP_NAME, style = Demo.typography.display)
                DText(
                    when (persona) {
                        Persona.CLAIMANT -> "Claims desk · we cover the unthinkable"
                        Persona.ADJUSTER -> "Adjuster workbench · claims, payouts, fraud checks"
                    },
                    style = Demo.typography.caption,
                    color = Demo.colors.inkMuted,
                )
            }
            Spacer(Modifier.weight(1f))
            DText("Acting as", style = Demo.typography.label, color = Demo.colors.inkMuted)
            DSelect(
                options = personaOptions,
                selectedLabel = personaOptions.first { it.value == persona }.label,
                onSelect = onPersona,
                modifier = Modifier.width(160.dp),
                enabled = enabled,
                fieldModifier = Modifier.pressFeedback(feedback, PressTarget.Persona),
                expanded = menuOpen,
                onExpandedChange = personaMenu::setOpen,
                optionModifier = { option -> Modifier.pressFeedback(feedback, PressTarget.PersonaOption(option)) },
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Demo.colors.accentGradient))
    }
}

/**
 * The messages, scrolled to the newest one whenever the conversation changes (not covered by a test). Only the claim
 * card of the last message can be confirmed: the backend files only the latest prepared claim.
 */
@Composable
private fun Conversation(
    messages: List<ChatMessage>,
    persona: Persona,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    feedback: PressFeedback,
    modifier: Modifier,
) {
    if (messages.isEmpty()) {
        EmptyConversation(persona, modifier)
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages) { listState.animateScrollToItem(messages.lastIndex) }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(messages) { message ->
            val newest = message === messages.last()
            MessageBubble(
                message = message,
                canConfirm = canConfirm && newest,
                onConfirm = onConfirm,
                confirmModifier = if (newest) Modifier.pressFeedback(feedback, PressTarget.ConfirmClaim) else Modifier,
            )
        }
    }
}

/** The mascot, a greeting and the persona's hint while nothing has been said yet. */
@Composable
private fun EmptyConversation(persona: Persona, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KaijuMascot(Modifier.size(112.dp))
            Spacer(Modifier.height(4.dp))
            DText(
                when (persona) {
                    Persona.CLAIMANT -> "Stomped, scorched or swallowed?"
                    Persona.ADJUSTER -> "Ready when you are, adjuster."
                },
                style = Demo.typography.display,
            )
            val hint = when (persona) {
                Persona.CLAIMANT -> "Describe the kaiju damage to get started."
                Persona.ADJUSTER -> "Ask about a claim, for example: Process claim #4711."
            }
            DText(hint, color = Demo.colors.inkMuted)
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    confirmModifier: Modifier,
) {
    val colors = Demo.colors
    val fromUser = message.author == ChatMessage.Author.USER
    val corner = 16.dp
    val tail = 4.dp
    val shape = if (fromUser) {
        RoundedCornerShape(topStart = corner, topEnd = tail, bottomEnd = corner, bottomStart = corner)
    } else {
        RoundedCornerShape(topStart = tail, topEnd = corner, bottomEnd = corner, bottomStart = corner)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, if (fromUser) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.Top,
    ) {
        if (!fromUser) Avatar()
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .then(
                    if (fromUser) {
                        Modifier.background(colors.accentGradient, shape)
                    } else {
                        Modifier.background(colors.surface, shape).border(1.dp, colors.border, shape)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hasContent = message.claimCard != null || message.analyticsTable != null
            when {
                message.text.isNotEmpty() && fromUser -> DText(message.text, color = colors.onAccent)
                message.text.isNotEmpty() -> DText(markdown(message.text))
                message.error == null && !hasContent -> DText("…", color = colors.inkMuted)
            }
            message.analyticsTable?.let { AnalyticsTable(it) }
            message.claimCard?.let { ClaimCard(it, canConfirm, onConfirm, confirmModifier) }
            message.error?.let { DText(it, style = Demo.typography.caption, color = colors.danger) }
        }
    }
}

/** The assistant's avatar: the mascot on a round raised surface. */
@Composable
private fun Avatar() {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Demo.colors.surfaceRaised)
            .border(1.dp, Demo.colors.border, CircleShape)
            .padding(5.dp),
    ) {
        KaijuMascot(Modifier.fillMaxSize())
    }
}

/**
 * The message input with "Send" and "Prepare claim". The input is a text area that grows up to [INPUT_MAX_LINES]
 * lines; Enter sends (and never inserts a newline), Shift+Enter inserts one. The input text is the [state]'s, so the
 * control endpoint's typing shows here. Keeps a 16 dp transparent margin, so the window background shows around it
 * (see the render tests).
 */
@Composable
private fun InputRow(
    state: ChatState,
    feedback: PressFeedback,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onPrepare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DTextField(
            value = state.input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                val enter = event.key == Key.Enter && !event.isShiftPressed
                val send = enter && event.type == KeyEventType.KeyDown && state.canSend && state.input.isNotBlank()
                if (send) onSubmit()
                enter
            },
            placeholder = "Type a message and press Enter",
            fieldModifier = Modifier.pressFeedback(feedback, PressTarget.Input),
            maxLines = INPUT_MAX_LINES,
        )
        DButton(
            "Send",
            onClick = onSubmit,
            style = DButtonStyle.PRIMARY,
            enabled = state.canSend,
            modifier = Modifier.pressFeedback(feedback, PressTarget.Send),
        )
        DButton(
            "Prepare claim",
            onClick = onPrepare,
            style = DButtonStyle.SECONDARY,
            enabled = state.canPrepareClaim,
            modifier = Modifier.pressFeedback(feedback, PressTarget.PrepareClaim),
        )
    }
}
