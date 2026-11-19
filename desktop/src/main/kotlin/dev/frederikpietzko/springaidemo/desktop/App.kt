package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.components.DText
import dev.frederikpietzko.springaidemo.desktop.theme.Demo
import dev.frederikpietzko.springaidemo.desktop.theme.DemoTheme
import dev.frederikpietzko.springaidemo.desktop.theme.ThemeMode

/**
 * The empty screen: a header with the application name and a muted placeholder in the content area.
 * [themeOverride] forces the light or dark appearance; `null` follows the system.
 */
@Composable
fun App(themeOverride: ThemeMode? = null) {
    DemoTheme(override = themeOverride) {
        Column(Modifier.fillMaxSize().background(Demo.colors.background)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(Demo.colors.surface)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                DText("spring-ai-demo", style = Demo.typography.display)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DText("Nothing here yet", style = Demo.typography.body, color = Demo.colors.inkMuted)
            }
        }
    }
}
