package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/**
 * A pill-shaped chip. A [selected] chip gets the accent gradient outline; [onClick] makes it a toggle chip
 * and [warning] highlights the chip.
 */
@Composable
fun DChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    warning: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = Demo.colors
    val shape = Demo.shapes.pill
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.surfaceRaised, shape)
            .then(
                when {
                    warning -> Modifier.border(1.dp, colors.warning, shape)
                    selected -> Modifier.border(1.5.dp, colors.accentGradient, shape)
                    else -> Modifier.border(1.dp, colors.border, shape)
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Checkbox, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected && onClick != null) DText("✓", style = Demo.typography.caption, color = colors.accent)
        DText(
            text,
            style = Demo.typography.caption,
            color = when {
                warning -> colors.warning
                selected -> colors.accent
                else -> colors.ink
            },
            maxLines = 1,
        )
    }
}
