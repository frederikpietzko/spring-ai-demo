package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

private const val DISABLED_ALPHA = 0.45f

/** A button in the demo theme. [DButtonStyle.PRIMARY] fills with the accent gradient. */
@Composable
fun DButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DButtonStyle = DButtonStyle.SECONDARY,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = Demo.colors
    val shape = Demo.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background = when (style) {
        DButtonStyle.PRIMARY -> Modifier.background(colors.accentGradient, shape)
        DButtonStyle.SECONDARY -> Modifier.background(if (hovered) colors.border else colors.surfaceRaised, shape)
        DButtonStyle.GHOST -> Modifier.background(if (hovered) colors.surfaceRaised else Color.Transparent, shape)
        DButtonStyle.DANGER -> Modifier.background(colors.danger, shape)
    }
    val content = when (style) {
        DButtonStyle.PRIMARY, DButtonStyle.DANGER -> colors.onAccent
        DButtonStyle.SECONDARY, DButtonStyle.GHOST -> colors.ink
    }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .then(background)
            .then(if (focused) Modifier.demoOutline(focused = true, shape = shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 4.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        DText(text, style = Demo.typography.label, color = content, maxLines = 1)
    }
}
