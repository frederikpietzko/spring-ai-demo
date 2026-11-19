package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/** Text in the demo theme; the colour defaults to the theme's ink. */
@Composable
fun DText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Demo.typography.body,
    color: Color = Demo.colors.ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Draws the accent-gradient focus ring while [focused], otherwise the theme's border. */
@Composable
fun Modifier.demoOutline(focused: Boolean, shape: Shape, error: Boolean = false): Modifier {
    val colors = Demo.colors
    return when {
        focused -> border(2.dp, colors.accentGradient, shape)
        error -> border(1.dp, colors.danger, shape)
        else -> border(1.dp, colors.border, shape)
    }
}
