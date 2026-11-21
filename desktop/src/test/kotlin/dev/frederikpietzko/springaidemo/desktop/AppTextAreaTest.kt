package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture
import dev.frederikpietzko.springaidemo.desktop.fakes.FakeChatApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The message input is a text area: it grows with its content up to five lines; Enter sends, Shift+Enter breaks. */
@OptIn(ExperimentalTestApi::class)
class AppTextAreaTest {
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun close() = commands.cancel()

    @Test
    fun `a long prompt is laid out in full on several lines and the input grows`() = runDesktopComposeUiTest {
        setContent { App(FakeChatApi()) }
        waitForIdle()
        val oneLine = inputHeight()

        onNode(hasSetTextAction()).performTextInput(LONG_PROMPT)
        waitForIdle()

        val layout = inputLayout()
        assertTrue(layout.lineCount in 2..MAX_LINES, "lines: ${layout.lineCount}")
        assertEquals(LONG_PROMPT.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(inputHeight() > oneLine, "height ${inputHeight()} vs one line $oneLine")
        assertTrue(layout.size.height <= inputHeight(), "text ${layout.size.height} taller than ${inputHeight()}")
    }

    @Test
    fun `the input stops growing at five lines`() = runDesktopComposeUiTest {
        setContent { App(FakeChatApi()) }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput(lines(MAX_LINES))
        waitForIdle()
        val fiveLines = inputHeight()

        onNode(hasSetTextAction()).performTextInput("\n" + lines(3))
        waitForIdle()

        assertEquals(MAX_LINES + 3, inputLayout().lineCount)
        assertEquals(fiveLines, inputHeight())
    }

    @Test
    fun `the input is one line tall again after a long text was sent`() = runDesktopComposeUiTest {
        setContent { App(FakeChatApi()) }
        waitForIdle()
        val oneLine = inputHeight()
        onNode(hasSetTextAction()).performTextInput(LONG_PROMPT)

        onNodeWithText("Send").performClick()
        waitForIdle()

        assertEquals(oneLine, inputHeight())
    }

    @Test
    fun `Enter sends the message and leaves no newline`() = runDesktopComposeUiTest {
        val api = FakeChatApi()
        setContent { App(api) }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Hi")

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(["Hi"], api.requests.map { it.message })
        onNode(hasSetTextAction() and hasEditableText("")).assertExists()
    }

    @Test
    fun `Shift+Enter adds a line that is part of the message`() = runDesktopComposeUiTest {
        val api = FakeChatApi()
        setContent { App(api) }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Hi")

        onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        onNode(hasSetTextAction()).performTextInput("there")
        waitForIdle()

        assertEquals([], api.requests)
        onNode(hasSetTextAction() and hasEditableText("Hi\nthere")).assertExists()
        onNodeWithText("Send").performClick()
        waitForIdle()
        assertEquals(["Hi\nthere"], api.requests.map { it.message })
    }

    @Test
    fun `Enter while an answer streams sends nothing and keeps the text`() = runDesktopComposeUiTest {
        val api = FakeChatApi { flow { awaitCancellationForever() } }
        setContent { App(api) }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Hi")
        onNodeWithText("Send").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("More")

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(["Hi"], api.requests.map { it.message })
        onNode(hasSetTextAction() and hasEditableText("More")).assertExists()
    }

    @Test
    fun `Enter on a blank input sends nothing and adds no newline`() = runDesktopComposeUiTest {
        val api = FakeChatApi()
        setContent { App(api) }
        waitForIdle()

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals([], api.requests)
        onNode(hasSetTextAction() and hasEditableText("")).assertExists()
    }

    @Test
    fun `text typed through the control grows the text area as it wraps`() =
        ControlFixture(typingSpeed = 5.milliseconds, reply = { flowOf(ChatEvent.Done) }).use { fixture ->
            runDesktopComposeUiTest {
                setContent { App(fixture.viewModel, fixture.feedback) }
                waitForIdle()
                val oneLine = inputHeight()

                val send = commands.async { fixture.control.execute("send \"$LONG_PROMPT\"") }

                waitUntil(timeoutMillis = WAIT_MS) {
                    val typed = fixture.viewModel.state.value.input
                    typed.length in 1 until LONG_PROMPT.length && inputLayout().lineCount > 1 && inputHeight() > oneLine
                }
                assertTrue(LONG_PROMPT.startsWith(fixture.viewModel.state.value.input))
                waitUntil(timeoutMillis = WAIT_MS) { send.isCompleted }
            }
        }

    private fun ComposeUiTest.inputHeight(): Int =
        onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height.toInt()

    private fun ComposeUiTest.inputLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val getLayout = onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
        val _ = getLayout.action?.invoke(results)
        return results.single()
    }

    private fun hasEditableText(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    private suspend fun awaitCancellationForever(): Nothing = kotlinx.coroutines.awaitCancellation()

    private companion object {
        const val MAX_LINES = 5
        const val WAIT_MS = 10_000L

        /** About 250 characters: wraps onto several lines of the input but needs fewer than five. */
        const val LONG_PROMPT = "A kaiju stepped on my car roof in the harbour district last night and then sat on " +
            "my garden shed, which was full of tools. My policy is KP-1001. Is the car covered, is the shed covered, " +
            "and what do I need to send you to file a claim?"

        fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }
    }
}
