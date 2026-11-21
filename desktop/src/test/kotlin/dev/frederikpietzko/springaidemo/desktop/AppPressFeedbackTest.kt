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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.components.DButton
import dev.frederikpietzko.springaidemo.desktop.components.DButtonStyle
import dev.frederikpietzko.springaidemo.desktop.control.pressFeedback
import dev.frederikpietzko.springaidemo.desktop.theme.Demo
import dev.frederikpietzko.springaidemo.desktop.theme.DemoTheme
import dev.frederikpietzko.springaidemo.desktop.theme.LightDemoColors
import dev.frederikpietzko.springaidemo.desktop.theme.ThemeMode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test
    fun `a pressed control is tinted inside and outlined in the accent colour just outside`() {
        val unpressed = renderScreenshot("press-feedback-unpressed", BUTTON_SCENE, BUTTON_SCENE) { PressableButton() }
        val holder = CoroutineScope(Dispatchers.Unconfined)
        val _ = holder.launch { feedback.holding(PressTarget.NewConversation) { awaitCancellation() } }

        val pressed = renderScreenshot("press-feedback-pressed", BUTTON_SCENE, BUTTON_SCENE) { PressableButton() }
        holder.cancel()

        val inside = [BUTTON_OFFSET + INSIDE, BUTTON_OFFSET + BUTTON_HEIGHT / 2]
        val outside = [BUTTON_OFFSET - OUTSIDE, BUTTON_OFFSET + BUTTON_HEIGHT / 2]
        assertNotEquals(unpressed.pixel(inside[0], inside[1]), pressed.pixel(inside[0], inside[1]), "no tint")
        assertEquals(LightDemoColors.accent.toArgb(), pressed.pixel(outside[0], outside[1]), "no outline")
        assertNotEquals(unpressed.pixel(outside[0], outside[1]), pressed.pixel(outside[0], outside[1]))
    }

    /** A secondary button at [BUTTON_OFFSET] (density 1, so dp are pixels) that [feedback] can press. */
    @androidx.compose.runtime.Composable
    private fun PressableButton() {
        DemoTheme(override = ThemeMode.LIGHT) {
            Box(Modifier.fillMaxSize().background(Demo.colors.background).padding(BUTTON_OFFSET.dp)) {
                DButton(
                    "New conversation",
                    onClick = {},
                    style = DButtonStyle.SECONDARY,
                    modifier = Modifier.size(BUTTON_WIDTH.dp, BUTTON_HEIGHT.dp)
                        .pressFeedback(feedback, PressTarget.NewConversation),
                )
            }
        }
    }

    private val isPressed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "pressed")

    private companion object {
        val HOLD = 400.milliseconds
        const val BUTTON_SCENE = 240
        const val BUTTON_OFFSET = 40
        const val BUTTON_WIDTH = 160
        const val BUTTON_HEIGHT = 40

        /** A point this far inside the button's left edge shows its fill, not its border or label. */
        const val INSIDE = 6

        /** A point this far outside the button's left edge lies on the outline (3 dp gap, then 3 dp of outline). */
        const val OUTSIDE = 5
        const val WAIT_MS = 5_000L
    }
}
