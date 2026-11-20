package dev.frederikpietzko.springaidemo.desktop.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The Kotlin purple at the start of the accent gradient. */
val KotlinPurple = Color(0xFF7954F6)

/** The Kotlin pink at the end of the accent gradient. */
val KotlinPink = Color(0xFFEB55E6)

/** The colour tokens of the demo theme. */
@Immutable
data class DemoColors(
    val isDark: Boolean,
    /** The window background. */
    val background: Color,
    /** Panels and cards. */
    val surface: Color,
    /** Inputs, hovered rows and nested surfaces. */
    val surfaceRaised: Color,
    val border: Color,
    val ink: Color,
    val inkMuted: Color,
    /** The solid accent for text and thin strokes. */
    val accent: Color,
    val accentStart: Color,
    val accentEnd: Color,
    /** Content drawn on top of the accent gradient. */
    val onAccent: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
) {
    /** The Kotlin purple-to-pink gradient for active and focused states. */
    val accentGradient: Brush
        get() = Brush.linearGradient([accentStart, accentEnd], start = Offset.Zero, end = Offset.Infinite)
}

val LightDemoColors = DemoColors(
    isDark = false,
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEFF0F3),
    border = Color(0xFFD1D3D6),
    ink = Color(0xFF1F2023),
    inkMuted = Color(0xFF6C707E),
    accent = KotlinPurple,
    accentStart = KotlinPurple,
    accentEnd = KotlinPink,
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFDB3B4B),
    warning = Color(0xFFC77D0A),
    success = Color(0xFF208A3C),
)

val DarkDemoColors = DemoColors(
    isDark = true,
    background = Color(0xFF1E1F22),
    surface = Color(0xFF2B2D30),
    surfaceRaised = Color(0xFF393B40),
    border = Color(0xFF515151),
    ink = Color(0xFFF4F2F7),
    inkMuted = Color(0xFFA8ADBD),
    accent = Color(0xFFA571E6),
    accentStart = KotlinPurple,
    accentEnd = KotlinPink,
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFF2777A),
    warning = Color(0xFFE5B94E),
    success = Color(0xFF5FB865),
)

/** The palette of the pixel-art kaiju mascot; the same in both appearances. */
@Immutable
data class KaijuPalette(
    val outline: Color,
    val body: Color,
    val belly: Color,
    val eye: Color,
    val pupil: Color,
    val tooth: Color,
    val horn: Color,
)

val DefaultKaijuPalette = KaijuPalette(
    outline = Color(0xFF173B22),
    body = Color(0xFF4FB862),
    belly = Color(0xFFCDEBA7),
    eye = Color(0xFFFFFFFF),
    pupil = Color(0xFF111316),
    tooth = Color(0xFFFFFFFF),
    horn = KotlinPink,
)
