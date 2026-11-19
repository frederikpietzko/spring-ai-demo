package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.desktop.chat.ChatApi
import dev.frederikpietzko.springaidemo.desktop.chat.ChatMessage
import dev.frederikpietzko.springaidemo.desktop.chat.ChatViewModel
import dev.frederikpietzko.springaidemo.desktop.components.DButton
import dev.frederikpietzko.springaidemo.desktop.components.DButtonStyle
import dev.frederikpietzko.springaidemo.desktop.components.DSelect
import dev.frederikpietzko.springaidemo.desktop.components.DText
import dev.frederikpietzko.springaidemo.desktop.components.DTextField
import dev.frederikpietzko.springaidemo.desktop.components.SelectOption
import dev.frederikpietzko.springaidemo.desktop.theme.Demo
import dev.frederikpietzko.springaidemo.desktop.theme.DemoTheme
import dev.frederikpietzko.springaidemo.desktop.theme.ThemeMode
import kotlinx.coroutines.launch

/** Both personas are listed; the adjuster arrives in a later wave. */
private val personaOptions: List<SelectOption<Persona>> = [
    SelectOption(Persona.CLAIMANT, "Claimant"),
    SelectOption(Persona.ADJUSTER, "Adjuster", enabled = false, hint = "later"),
]

/**
 * The chat screen: a header with the application name and the persona switch, the conversation, and an input row.
 * [chatApi] talks to the backend (a fake in tests); [themeOverride] forces the light or dark appearance, `null`
 * follows the system.
 */
@Composable
fun App(chatApi: ChatApi, themeOverride: ThemeMode? = null) {
    val viewModel = remember(chatApi) { ChatViewModel(chatApi) }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    DemoTheme(override = themeOverride) {
        Column(Modifier.fillMaxSize().background(Demo.colors.background)) {
            Header(persona = state.persona, onPersona = viewModel::selectPersona)
            Conversation(state.messages, Modifier.weight(1f).fillMaxWidth())
            InputRow(sending = state.sending, onSend = { text -> scope.launch { viewModel.send(text) } })
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

/** The messages, scrolled to the newest one whenever the conversation changes (not covered by a test). */
@Composable
private fun Conversation(messages: List<ChatMessage>, modifier: Modifier) {
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
        items(messages) { MessageBubble(it) }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.author == ChatMessage.Author.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .background(if (fromUser) Demo.colors.surfaceRaised else Demo.colors.surface, Demo.shapes.medium)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                message.text.isNotEmpty() -> DText(message.text)
                message.error == null -> DText("…", color = Demo.colors.inkMuted)
            }
            message.error?.let { DText(it, style = Demo.typography.caption, color = Demo.colors.danger) }
        }
    }
}

/** Keeps a 16 dp transparent margin, so the window background shows around it (see the render tests). */
@Composable
private fun InputRow(sending: Boolean, onSend: (String) -> Unit) {
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
            enabled = !sending,
        )
    }
}
