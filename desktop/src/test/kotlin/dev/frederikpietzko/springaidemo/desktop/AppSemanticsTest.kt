package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.desktop.fakes.FakeChatApi
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppSemanticsTest {
    @Test
    fun `window shows the application name, the hint, an input and a send button`() = runDesktopComposeUiTest {
        setContent { App(FakeChatApi()) }
        waitForIdle()

        onNodeWithText("spring-ai-demo").assertIsDisplayed()
        onNodeWithText("Describe the kaiju damage to get started.").assertIsDisplayed()
        onNode(hasSetTextAction()).assertIsDisplayed()
        onNodeWithText("Send").assertIsDisplayed()
    }

    @Test
    fun `a message typed and sent shows up with the streamed reply and clears the input`() = runDesktopComposeUiTest {
        setContent { App(FakeChatApi()) }
        waitForIdle()

        onNode(hasSetTextAction()).performTextInput("Hi")
        onNodeWithText("Send").performClick()
        waitForIdle()

        onNodeWithText("Hi").assertIsDisplayed()
        onNodeWithText("Hello").assertIsDisplayed()
        onNode(hasSetTextAction()).assert(hasNoText)
    }

    @Test
    fun `the persona switch shows Claimant by default`() = runDesktopComposeUiTest {
        setContent { App(FakeChatApi()) }
        waitForIdle()

        onNode(isPersonaSwitch).assertTextContains("Claimant")
    }

    @Test
    fun `Adjuster cannot be chosen`() = runDesktopComposeUiTest {
        val api = FakeChatApi()
        setContent { App(api) }
        waitForIdle()

        onNode(isPersonaSwitch).performClick()
        waitForIdle()
        onNodeWithText("Adjuster").performClick()
        waitForIdle()

        // The popup stays open after a click on a disabled option, so only the switch itself is asserted.
        onNode(isPersonaSwitch).assertTextContains("Claimant")
        assertEquals([], api.requests)
    }

    @Test
    fun `an error event is shown under the partial reply and the send button is usable again`() =
        runDesktopComposeUiTest {
            val failing = FakeChatApi {
                flowOf(ChatEvent.TextDelta("Hel"), ChatEvent.Error("The claims desk is unavailable."))
            }
            setContent { App(failing) }
            waitForIdle()

            onNode(hasSetTextAction()).performTextInput("Hi")
            onNodeWithText("Send").performClick()
            waitForIdle()

            onNodeWithText("Hel").assertIsDisplayed()
            onNodeWithText("The claims desk is unavailable.").assertIsDisplayed()
            onNodeWithText("Send").assertIsEnabled()
        }

    private val isPersonaSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)

    private val hasNoText = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
}
