package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.frederikpietzko.springaidemo.desktop.chat.ChatViewModel
import dev.frederikpietzko.springaidemo.desktop.chat.HttpChatApi
import dev.frederikpietzko.springaidemo.desktop.chat.backendBaseUrl
import dev.frederikpietzko.springaidemo.desktop.control.DesktopControl
import dev.frederikpietzko.springaidemo.desktop.control.PressFeedback
import dev.frederikpietzko.springaidemo.desktop.control.startControlServer
import dev.frederikpietzko.springaidemo.desktop.theme.KaijuPainter
import dev.frederikpietzko.springaidemo.desktop.theme.kaijuIconImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Taskbar

/** The name of the application, shown in the window title and the header. */
const val APP_NAME = "Kaiju Insurance"

private const val DOCK_ICON_SIZE = 512

/** The room the macOS traffic lights take at the start of the header. */
private val MAC_WINDOW_CONTROLS = 64.dp

/**
 * Opens the demo window. The one HTTP client, the one view model and the press feedback are created here and shared
 * with the control endpoint, which is started only when `DESKTOP_CONTROL_PORT` is a valid port. The client and the
 * endpoint are closed when the window closes.
 */
fun main() {
    val chatApi = HttpChatApi(backendBaseUrl(System::getenv))
    val viewModel = ChatViewModel(chatApi)
    val feedback = PressFeedback()
    val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controlServer = startControlServer(System::getenv, DesktopControl(viewModel, feedback, controlScope))
    showDockIcon()
    application {
        Window(
            onCloseRequest = {
                controlServer?.close()
                controlScope.cancel()
                chatApi.close()
                exitApplication()
            },
            title = APP_NAME,
            icon = KaijuPainter(),
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
        ) {
            val macOs = remember { System.getProperty("os.name").startsWith("Mac") }
            if (macOs) {
                LaunchedEffect(window) {
                    // The content extends under a transparent title bar, so the header is the app bar.
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            val titleBar = if (macOs) {
                TitleBar(startInset = MAC_WINDOW_CONTROLS) { header -> WindowDraggableArea { header() } }
            } else {
                TitleBar()
            }
            App(viewModel, feedback, titleBar = titleBar)
        }
    }
}

/** The window icon covers Windows and Linux; the macOS dock takes its icon from the AWT taskbar. */
private fun showDockIcon() {
    if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
        Taskbar.getTaskbar().iconImage = kaijuIconImage(DOCK_ICON_SIZE)
    }
}
