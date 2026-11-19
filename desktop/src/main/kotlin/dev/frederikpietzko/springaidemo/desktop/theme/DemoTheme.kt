package dev.frederikpietzko.springaidemo.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** A saved appearance override. No override means the theme follows the system. */
enum class ThemeMode { LIGHT, DARK }

/** Whether the dark theme applies: the [override] wins, otherwise the system appearance decides. */
fun resolveDarkTheme(override: ThemeMode?, systemDark: Boolean): Boolean = when (override) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    null -> systemDark
}

val LocalDemoColors = staticCompositionLocalOf { LightDemoColors }
val LocalDemoTypography = staticCompositionLocalOf { DefaultDemoTypography }
val LocalDemoShapes = staticCompositionLocalOf { DemoShapes() }

/** Provides the demo design tokens: the dark or light colours, typography and shapes. */
@Composable
fun DemoTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDemoColors provides if (dark) DarkDemoColors else LightDemoColors,
        LocalDemoTypography provides DefaultDemoTypography,
        LocalDemoShapes provides DemoShapes(),
        content = content,
    )
}

/** Provides the demo theme that follows the system appearance unless [override] is set. */
@Composable
fun DemoTheme(override: ThemeMode?, content: @Composable () -> Unit) {
    DemoTheme(dark = resolveDarkTheme(override, isSystemInDarkTheme()), content = content)
}

/** Shorthand for the current demo tokens. */
object Demo {
    val colors: DemoColors
        @Composable @ReadOnlyComposable get() = LocalDemoColors.current
    val typography: DemoTypography
        @Composable @ReadOnlyComposable get() = LocalDemoTypography.current
    val shapes: DemoShapes
        @Composable @ReadOnlyComposable get() = LocalDemoShapes.current
}
