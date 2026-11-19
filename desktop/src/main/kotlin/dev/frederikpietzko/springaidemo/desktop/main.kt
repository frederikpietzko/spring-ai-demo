package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.frederikpietzko.springaidemo.desktop.chat.HttpChatApi
import dev.frederikpietzko.springaidemo.desktop.chat.backendBaseUrl

/** Opens the demo window. The one HTTP client is created here and closed when the window closes. */
fun main() {
    val chatApi = HttpChatApi(backendBaseUrl(System::getenv))
    application {
        Window(
            onCloseRequest = {
                chatApi.close()
                exitApplication()
            },
            title = "spring-ai-demo",
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
        ) {
            App(chatApi)
        }
    }
}
