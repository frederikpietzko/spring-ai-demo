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
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
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
    fun `Adjuster can be chosen and the next message is sent as the adjuster`() = runDesktopComposeUiTest {
        val api = FakeChatApi()
        setContent { App(api) }
        waitForIdle()

        onNode(isPersonaSwitch).performClick()
        waitForIdle()
        onNodeWithText("Adjuster").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Hi")
        onNodeWithText("Send").performClick()
        waitForIdle()

        onNode(isPersonaSwitch).assertTextContains("Adjuster")
        assertEquals([ChatRequest(Persona.ADJUSTER, "Hi")], api.requests)
    }

    @Test
    fun `a pending confirmation shows the tool, its arguments and the approve and deny buttons`() =
        runDesktopComposeUiTest {
            setContent { App(FakeChatApi { flowOf(ChatEvent.TextDelta("Ready to pay."), PAYOUT_CONFIRMATION) }) }
            waitForIdle()

            onNode(hasSetTextAction()).performTextInput("Pay claim 4711")
            onNodeWithText("Send").performClick()
            waitForIdle()

            onNodeWithText("Ready to pay.").assertIsDisplayed()
            onNodeWithText("issuePayout", substring = true).assertIsDisplayed()
            onNodeWithText("""{"claimNumber":4711}""", substring = true).assertIsDisplayed()
            onNodeWithText("Approve").assertIsDisplayed()
            onNodeWithText("Deny").assertIsDisplayed()
        }

    @Test
    fun `clicking Approve sends the approving decision and the confirmation disappears`() = runDesktopComposeUiTest {
        val api = FakeChatApi(decisionReply = { flowOf(ChatEvent.TextDelta("Paid"), ChatEvent.Done) }) {
            flowOf(PAYOUT_CONFIRMATION)
        }
        setContent { App(api) }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Pay claim 4711")
        onNodeWithText("Send").performClick()
        waitForIdle()

        onNodeWithText("Approve").performClick()
        waitForIdle()

        assertEquals([ConfirmationDecision("c-1", approved = true)], api.decisions)
        onNodeWithText("Paid").assertIsDisplayed()
        onNodeWithText("Approve").assertDoesNotExist()
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

    private companion object {
        val PAYOUT_CONFIRMATION = ChatEvent.ConfirmationRequest("c-1", "issuePayout", """{"claimNumber":4711}""")
    }
}
