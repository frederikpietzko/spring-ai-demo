package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ClaimCardItem
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import dev.frederikpietzko.springaidemo.desktop.fakes.FakeChatApi
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppSemanticsTest {
    @Test
    fun `window shows the application name, the conversation column, the hint, an input and a send button`() =
        runDesktopComposeUiTest {
            setContent { App(FakeChatApi()) }
            waitForIdle()

            onNodeWithText("spring-ai-demo").assertIsDisplayed()
            onNodeWithText("New conversation").assertIsDisplayed()
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

    @Test
    fun `the stored conversations are listed at start`() = runDesktopComposeUiTest {
        setContent { App(FakeChatApi(conversations = { [SHED, GNOME] })) }
        waitForIdle()

        onNodeWithText("Shed").assertIsDisplayed()
        onNodeWithText("Gnome").assertIsDisplayed()
    }

    @Test
    fun `choosing a conversation shows its transcript`() = runDesktopComposeUiTest {
        val api = FakeChatApi(
            conversations = { [SHED, GNOME] },
            messages = { id ->
                if (id == GNOME.id) {
                    [
                        ConversationMessage(MessageRole.USER, "my garden gnome is gone"),
                        ConversationMessage(MessageRole.ASSISTANT, "Noted."),
                    ]
                } else {
                    []
                }
            },
        )
        setContent { App(api) }
        waitForIdle()

        onNodeWithText("Gnome").performClick()
        waitForIdle()

        onNodeWithText("my garden gnome is gone").assertIsDisplayed()
        onNodeWithText("Noted.").assertIsDisplayed()
    }

    @Test
    fun `a new conversation clears the screen`() = runDesktopComposeUiTest {
        setContent { App(FakeChatApi()) }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Hi")
        onNodeWithText("Send").performClick()
        waitForIdle()

        onNodeWithText("New conversation").performClick()
        waitForIdle()

        onNodeWithText("Hello").assertDoesNotExist()
        onNodeWithText("Describe the kaiju damage to get started.").assertIsDisplayed()
    }

    @Test
    fun `Prepare claim sends the prepare action and shows the claim card with its items and total`() =
        runDesktopComposeUiTest {
            val api = FakeChatApi { flowOf(CARD, ChatEvent.Done) }
            setContent { App(api) }
            waitForIdle()

            onNodeWithText("Prepare claim").performClick()
            waitForIdle()

            val request = api.requests.single()
            assertEquals(Persona.CLAIMANT to "", request.persona to request.message)
            assertEquals(ChatAction.PREPARE_CLAIM, request.action)
            assertTrue(!request.conversationId.isNullOrBlank(), request.toString())
            onNodeWithText("Prepare my claim").assertIsDisplayed()
            onNodeWithText("KP-1001").assertIsDisplayed()
            onNodeWithText("Car roof").assertIsDisplayed()
            onNodeWithText("800000").assertIsDisplayed()
            onNodeWithText("Garden gnome").assertIsDisplayed()
            onNodeWithText("25000").assertIsDisplayed()
            onNodeWithText("825000").assertIsDisplayed()
            onNodeWithText("Confirm").assertIsEnabled()
        }

    @Test
    fun `Confirm on the card sends the confirm action and shows the answer`() = runDesktopComposeUiTest {
        val api = claimsApi()
        setContent { App(api) }
        waitForIdle()
        onNodeWithText("Prepare claim").performClick()
        waitForIdle()

        onNodeWithText("Confirm").performClick()
        waitForIdle()

        assertEquals([ChatAction.PREPARE_CLAIM, ChatAction.CONFIRM_CLAIM], api.requests.map { it.action })
        assertEquals("", api.requests.last().message)
        onNodeWithText("Confirm claim").assertIsDisplayed()
        onNodeWithText(FILED).assertIsDisplayed()
    }

    @Test
    fun `only the newest claim card can be confirmed`() = runDesktopComposeUiTest(height = TALL) {
        setContent { App(claimsApi()) }
        waitForIdle()
        onNodeWithText("Prepare claim").performClick()
        waitForIdle()
        onNodeWithText("Prepare claim").performClick()
        waitForIdle()

        val confirms = onAllNodesWithText("Confirm")
        confirms[0].assertIsNotEnabled()
        confirms[1].assertIsEnabled()
        confirms[1].performClick()
        waitForIdle()

        onAllNodesWithText("Confirm")[1].assertIsNotEnabled()
    }

    private val isPersonaSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)

    private val hasNoText = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))

    /** Answers a prepare with [CARD] and a confirm with [FILED]. */
    private fun claimsApi() = FakeChatApi { request ->
        when (request.action) {
            ChatAction.PREPARE_CLAIM -> flowOf(CARD, ChatEvent.Done)
            else -> flowOf(ChatEvent.TextDelta(FILED), ChatEvent.Done)
        }
    }

    private companion object {
        val SHED = ConversationSummary("c-3", "Shed", "2026-11-20T10:00:00Z")
        val GNOME = ConversationSummary("c-7", "Gnome", "2026-11-20T09:00:00Z")

        const val FILED = "Your claim has been filed. Your claim number is 4714."

        /** A window tall enough for two claim cards, so the first one stays composed in the lazy list. */
        const val TALL = 1600

        val CARD = ChatEvent.ClaimCard(
            policyNumber = "KP-1001",
            district = "HARBOUR",
            incidentAt = "2026-11-19T15:00:00Z",
            kaijuClass = 3,
            description = "Kaiju stepped on the car",
            damageItems = [
                ClaimCardItem("VEHICLE", "Car roof", 800000),
                ClaimCardItem("GARDEN", "Garden gnome", 25000),
            ],
        )
    }
}
