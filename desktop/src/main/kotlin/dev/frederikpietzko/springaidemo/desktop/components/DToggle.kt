package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/** An on/off switch. When on, the track fills with the accent gradient. */
@Composable
fun DToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Demo.colors
    val shape = Demo.shapes.pill
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val thumbOffset by animateDpAsState(if (checked) 16.dp else 0.dp)
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 20.dp)
            .clip(shape)
            .then(
                if (checked) {
                    Modifier.background(colors.accentGradient, shape)
                } else {
                    Modifier.background(colors.surfaceRaised, shape)
                },
            )
            .then(if (focused || !checked) Modifier.demoOutline(focused, shape) else Modifier)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(16.dp)
                .background(if (checked) colors.onAccent else colors.inkMuted, CircleShape)
                .border(0.5.dp, colors.border.copy(alpha = 0.3f), CircleShape),
        )
    }
}
