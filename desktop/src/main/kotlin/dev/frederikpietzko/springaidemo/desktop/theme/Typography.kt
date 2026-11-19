package dev.frederikpietzko.springaidemo.desktop.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The text styles of the demo theme; colours come from [DemoColors]. */
@Immutable
data class DemoTypography(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    /** Monospaced text, e.g. code and identifiers. */
    val mono: TextStyle,
)

val DefaultDemoTypography = DemoTypography(
    display = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    title = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    mono = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
)

/** The corner shapes of the demo theme. */
@Immutable
data class DemoShapes(
    val small: RoundedCornerShape = RoundedCornerShape(6.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(10.dp),
    val large: RoundedCornerShape = RoundedCornerShape(16.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
)
