package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class AppSemanticsTest {
    @Test
    fun `window shows the application name and the placeholder`() = runDesktopComposeUiTest {
        setContent { App() }
        waitForIdle()

        onNodeWithText("spring-ai-demo").assertIsDisplayed()
        onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun `empty screen has no controls`() = runDesktopComposeUiTest {
        setContent { App() }
        waitForIdle()

        onAllNodes(hasClickAction()).assertCountEquals(0)
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        onAllNodes(isToggleable()).assertCountEquals(0)
    }
}
