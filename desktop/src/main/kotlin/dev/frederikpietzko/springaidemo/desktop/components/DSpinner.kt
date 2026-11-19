package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/** An indeterminate loading indicator: a rotating arc in the accent gradient over a faint track. */
@Composable
fun DSpinner(modifier: Modifier = Modifier, size: Dp = 28.dp, strokeWidth: Dp = 3.dp) {
    val colors = Demo.colors
    val rotation by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        drawArc(color = colors.border, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke)
        rotate(rotation) {
            drawArc(
                brush = colors.accentGradient,
                startAngle = -90f,
                sweepAngle = 100f,
                useCenter = false,
                style = stroke,
            )
        }
    }
}
