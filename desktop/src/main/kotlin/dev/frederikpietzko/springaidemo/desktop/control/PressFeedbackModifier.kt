package dev.frederikpietzko.springaidemo.desktop.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/** The state description of a pressed control, which is also how tests find it. */
const val PRESSED = "pressed"

private val OUTLINE_WIDTH = 3.dp

/** Opacity of the accent tint over a pressed control. */
private const val TINT_ALPHA = 0.15f

/** Gap between the control and its outline, so the outline also shows around accent-filled buttons. */
private val OUTLINE_GAP = 3.dp

private val OUTLINE_CORNER = 8.dp

/**
 * Draws a 3 dp accent outline just outside the control and a translucent accent tint over it, and marks it [PRESSED]
 * for accessibility while [feedback] presses [target]; otherwise leaves the control unchanged. The outline is drawn
 * outside the bounds, so the layout does not move.
 */
@Composable
fun Modifier.pressFeedback(feedback: PressFeedback, target: PressTarget): Modifier {
    val pressed by feedback.pressed.collectAsState()
    if (pressed != target) return this
    val color = Demo.colors.accent
    return drawWithContent {
        drawContent()
        drawRoundRect(
            color = color.copy(alpha = TINT_ALPHA),
            cornerRadius = CornerRadius(OUTLINE_CORNER.toPx()),
        )
        val inset = OUTLINE_GAP.toPx() + OUTLINE_WIDTH.toPx() / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(-inset, -inset),
            size = Size(size.width + 2 * inset, size.height + 2 * inset),
            cornerRadius = CornerRadius(OUTLINE_CORNER.toPx()),
            style = Stroke(OUTLINE_WIDTH.toPx()),
        )
    }.semantics { stateDescription = PRESSED }
}
