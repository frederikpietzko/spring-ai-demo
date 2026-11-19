package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Opens the demo window. The desktop application does not talk to the backend yet. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "spring-ai-demo",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        App()
    }
}
