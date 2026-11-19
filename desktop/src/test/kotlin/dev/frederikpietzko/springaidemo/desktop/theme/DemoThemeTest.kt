package dev.frederikpietzko.springaidemo.desktop.theme

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DemoThemeTest {
    @Test
    fun `without an override the theme follows the system appearance`() {
        assertTrue(resolveDarkTheme(override = null, systemDark = true))
        assertFalse(resolveDarkTheme(override = null, systemDark = false))
    }

    @Test
    fun `an override wins over the system appearance`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }

    @Test
    fun `light and dark differ in background and ink`() {
        assertNotEquals(LightDemoColors.background, DarkDemoColors.background)
        assertNotEquals(LightDemoColors.ink, DarkDemoColors.ink)
        assertFalse(LightDemoColors.isDark)
        assertTrue(DarkDemoColors.isDark)
    }

    @Test
    fun `both themes share the Kotlin purple-to-pink accent gradient`() {
        for (colors in [LightDemoColors, DarkDemoColors]) {
            assertEquals(KotlinPurple, colors.accentStart)
            assertEquals(KotlinPink, colors.accentEnd)
        }
    }
}
