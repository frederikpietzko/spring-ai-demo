package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.desktop.control.ControlFixture
import dev.frederikpietzko.springaidemo.desktop.control.ControlReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Commands run against the same view model and press feedback the screen shows, and the screen shows their effect. */
@OptIn(ExperimentalTestApi::class)
class AppControlTest {
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun close() = commands.cancel()

    @Test
    fun `typed text fills the input character by character and the input is empty after Send`() =
        withApp(ControlFixture(typingSpeed = SLOW, reply = { DONE })) { fixture ->
            val send = fixture.run("""send "Hi"""")

            waitUntilExists(hasSetTextAction() and hasEditableText("H"))
            waitUntilExists(hasSetTextAction() and hasEditableText("Hi"))
            waitUntil(timeoutMillis = WAIT_MS) { send.isCompleted }
            waitUntilExists(hasSetTextAction() and hasEditableText(""))
            onNodeWithText("Hi").assertIsDisplayed()
        }

    @Test
    fun `the input is shown as pressed while typing, then Send`() =
        withApp(ControlFixture(typingSpeed = SLOW, highlight = SLOW, reply = { DONE })) { fixture ->
            val send = fixture.run("""send "Hi"""")

            waitUntilExists(hasSetTextAction() and isPressed)
            waitUntilExists(hasText("Send") and isPressed)
            waitUntil(timeoutMillis = WAIT_MS) { send.isCompleted }
            waitUntilGone(isPressed)
        }

    @Test
    fun `persona adjuster shows on the persona switch and in the state`() = withApp { fixture ->
        val persona = fixture.run("persona adjuster")

        waitUntil(timeoutMillis = WAIT_MS) { persona.isCompleted }
        waitForIdle()
        onNode(isPersonaSwitch).assertTextContains("Adjuster")
        val state = fixture.stateBody()
        assertTrue(""""persona":"ADJUSTER"""" in state, state)
    }

    @Test
    fun `open shows the transcript and marks the conversation's row`() = withApp { fixture ->
        waitUntilExists(hasText("Gnome"))

        val open = fixture.run("open Gnome")

        waitUntil(timeoutMillis = WAIT_MS) { open.isCompleted }
        waitUntilExists(hasText("my garden gnome is gone"))
        onNodeWithText("Gnome").assertIsSelected()
    }

    @Test
    fun `approve decides the confirmation card on screen`() = withApp { fixture ->
        fixture.events.send(ControlFixture.PAYOUT)
        val _ = fixture.run("send Pay")
        waitUntilExists(hasText("Approve"))

        val approve = fixture.run("approve")

        waitUntil(timeoutMillis = WAIT_MS) { approve.isCompleted }
        waitUntilExists(hasText("Paid", substring = true))
        onNodeWithText("Approve").assertDoesNotExist()
    }

    @Test
    fun `while an answer streams Send and Prepare claim are disabled on screen and in the state`() =
        withApp { fixture ->
            val send = fixture.run("send Hi")

            waitUntil(timeoutMillis = WAIT_MS) { send.isCompleted }
            waitForIdle()
            onNodeWithText("Send").assertIsNotEnabled()
            onNodeWithText("Prepare claim").assertIsNotEnabled()
            val state = fixture.stateBody()
            assertTrue(""""send":false""" in state && """"prepareClaim":false""" in state, state)
        }

    /** Shows the app over [fixture]'s view model and press feedback and runs [test]; closes [fixture] afterwards. */
    private fun withApp(
        fixture: ControlFixture = ControlFixture(),
        test: suspend ComposeUiTest.(ControlFixture) -> Unit,
    ) = fixture.use {
        runDesktopComposeUiTest {
            setContent { App(fixture.viewModel, fixture.feedback) }
            waitForIdle()
            test(fixture)
        }
    }

    private fun ControlFixture.run(line: String): Deferred<ControlReply> = commands.async { control.execute(line) }

    private suspend fun ControlFixture.stateBody(): String = assertIs<ControlReply.Ok>(control.execute("state")).body

    private fun ComposeUiTest.waitUntilExists(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = WAIT_MS) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.waitUntilGone(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = WAIT_MS) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    private fun hasEditableText(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    private val isPressed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "pressed")

    private val isPersonaSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)

    private companion object {
        val SLOW = 300.milliseconds
        val DONE = flowOf(ChatEvent.TextDelta("Hello"), ChatEvent.Done)
        const val WAIT_MS = 5_000L
    }
}
