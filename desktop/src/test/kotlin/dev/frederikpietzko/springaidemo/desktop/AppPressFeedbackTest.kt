package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.frederikpietzko.springaidemo.desktop.chat.ChatViewModel
import dev.frederikpietzko.springaidemo.desktop.control.PressFeedback
import dev.frederikpietzko.springaidemo.desktop.control.PressTarget
import dev.frederikpietzko.springaidemo.desktop.fakes.FakeChatApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@OptIn(ExperimentalTestApi::class)
class AppPressFeedbackTest {
    private val feedback = PressFeedback()
    private val viewModel = ChatViewModel(FakeChatApi())

    @Test
    fun `a pressed control is shown as pressed for the hold time and not afterwards`() = runDesktopComposeUiTest {
        setContent { App(viewModel, feedback) }
        waitForIdle()
        val newConversation = hasText("New conversation") and isPressed
        val started = TimeSource.Monotonic.markNow()

        val press = CoroutineScope(Dispatchers.Default).launch { feedback.press(PressTarget.NewConversation, HOLD) }

        waitUntil(timeoutMillis = WAIT_MS) { onAllNodes(newConversation).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = WAIT_MS) { onAllNodes(newConversation).fetchSemanticsNodes().isEmpty() }
        press.join()
        assertTrue(started.elapsedNow() >= HOLD, "released after ${started.elapsedNow()}")
    }

    @Test
    fun `nothing is shown as pressed without a press`() = runDesktopComposeUiTest {
        setContent { App(viewModel, feedback) }
        waitForIdle()

        onNode(isPressed).assertDoesNotExist()
    }

    private val isPressed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "pressed")

    private companion object {
        val HOLD = 400.milliseconds
        const val WAIT_MS = 5_000L
    }
}
