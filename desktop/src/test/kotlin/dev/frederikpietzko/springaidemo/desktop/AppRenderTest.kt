package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.ui.graphics.toArgb
import dev.frederikpietzko.springaidemo.desktop.fakes.FakeChatApi
import dev.frederikpietzko.springaidemo.desktop.theme.DarkDemoColors
import dev.frederikpietzko.springaidemo.desktop.theme.LightDemoColors
import dev.frederikpietzko.springaidemo.desktop.theme.ThemeMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AppRenderTest {
    @Test
    fun `forced dark appearance renders the dark background`() {
        val dark = renderScreenshot("app-dark", width = 480, height = 320) { App(FakeChatApi(), ThemeMode.DARK) }

        assertEquals(DarkDemoColors.background.toArgb(), dark.backgroundPixel())
    }

    @Test
    fun `forced light appearance renders the light background`() {
        val light = renderScreenshot("app-light", width = 480, height = 320) { App(FakeChatApi(), ThemeMode.LIGHT) }

        assertEquals(LightDemoColors.background.toArgb(), light.backgroundPixel())
    }

    @Test
    fun `forced appearances produce different backgrounds`() {
        val light = renderScreenshot("app-light-compare", width = 480, height = 320) {
            App(FakeChatApi(), ThemeMode.LIGHT)
        }
        val dark = renderScreenshot("app-dark-compare", width = 480, height = 320) {
            App(FakeChatApi(), ThemeMode.DARK)
        }

        assertNotEquals(light.backgroundPixel(), dark.backgroundPixel())
    }

    @Test
    fun `the themed background shows around the input row`() {
        val dark = renderScreenshot("app-dark-input-row", width = 480, height = 320) {
            App(FakeChatApi(), ThemeMode.DARK)
        }

        // Both bottom corners lie in the input row's transparent margin: left of the field and right of the button.
        assertEquals(DarkDemoColors.background.toArgb(), dark.backgroundPixel())
        assertEquals(DarkDemoColors.background.toArgb(), dark.pixel(x = 480 - CORNER_INSET, y = 320 - CORNER_INSET))
    }

    private companion object {
        const val CORNER_INSET = 5
    }
}
