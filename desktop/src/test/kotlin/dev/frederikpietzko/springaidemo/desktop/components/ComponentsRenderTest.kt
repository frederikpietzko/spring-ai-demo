package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.renderScreenshot
import dev.frederikpietzko.springaidemo.desktop.theme.DarkDemoColors
import dev.frederikpietzko.springaidemo.desktop.theme.Demo
import dev.frederikpietzko.springaidemo.desktop.theme.DemoTheme
import dev.frederikpietzko.springaidemo.desktop.theme.LightDemoColors
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val WIDTH = 560
private const val HEIGHT = 460
private const val GALLERY_PADDING = 16
private const val CARD_WIDTH = 300

// The card is the gallery's first child, so a point in the middle of its top padding is known.
private const val CARD_PADDING_X = GALLERY_PADDING + CARD_WIDTH / 2
private const val CARD_PADDING_Y = GALLERY_PADDING + 7

class ComponentsRenderTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `components draw with the active theme's colours`(dark: Boolean) {
        val screenshot = renderScreenshot(
            name = "components-${if (dark) "dark" else "light"}",
            width = WIDTH,
            height = HEIGHT,
        ) { Gallery(dark) }

        val expected = if (dark) DarkDemoColors else LightDemoColors
        assertEquals(expected.background.toArgb(), screenshot.backgroundPixel())
        assertEquals(
            expected.surface.toArgb(),
            screenshot.pixel(CARD_PADDING_X, CARD_PADDING_Y),
            "the DCard surface is not drawn",
        )
    }

    @Test
    fun `light and dark galleries differ`() {
        val light = renderScreenshot(name = "components-light-compare", width = WIDTH, height = HEIGHT) {
            Gallery(dark = false)
        }
        val dark = renderScreenshot(name = "components-dark-compare", width = WIDTH, height = HEIGHT) {
            Gallery(dark = true)
        }

        assertNotEquals(light.backgroundPixel(), dark.backgroundPixel())
    }

    @Test
    fun `the spinner renders without throwing`() {
        renderScreenshot("spinner", width = 120, height = 120) {
            DemoTheme(dark = false) {
                Row(Modifier.fillMaxSize().background(Demo.colors.background).padding(16.dp)) { DSpinner() }
            }
        }
    }
}

@Composable
private fun Gallery(dark: Boolean) {
    DemoTheme(dark = dark) {
        Column(
            Modifier.fillMaxSize().background(Demo.colors.background).padding(GALLERY_PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DCard(highlighted = true, modifier = Modifier.width(CARD_WIDTH.dp)) {
                DText("Card", style = Demo.typography.title)
                DTextField(value = "Value", onValueChange = {}, label = "Name", modifier = Modifier.width(220.dp))
                DSelect(
                    options = [SelectOption("a", "Option A")],
                    selectedLabel = "Option A",
                    onSelect = {},
                    label = "Choice",
                    modifier = Modifier.width(220.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DButton("Start", onClick = {}, style = DButtonStyle.PRIMARY)
                DButton("Refresh", onClick = {})
                DButton("Cancel", onClick = {}, style = DButtonStyle.GHOST)
                DButton("Delete", onClick = {}, style = DButtonStyle.DANGER)
                DButton("Disabled", onClick = {}, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DToggle(checked = true, onCheckedChange = {})
                DToggle(checked = false, onCheckedChange = {})
                DChip("Selected", selected = true, onClick = {})
                DChip("Plain", onClick = {})
                DChip("Warning", warning = true)
                DIconButton(
                    icon = DIcons.strokeIcon("dot") { moveTo(12f, 12f) },
                    description = "Dot",
                    onClick = {},
                )
                DSpinner()
            }
        }
    }
}
