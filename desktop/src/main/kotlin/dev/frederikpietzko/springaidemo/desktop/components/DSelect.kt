package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

private const val DISABLED_FIELD_ALPHA = 0.55f
private const val DISABLED_OPTION_ALPHA = 0.45f
private const val POPUP_GAP_PX = 4

/**
 * A dropdown select. [selectedLabel] is the text in the closed field; [supportingText] is shown below the
 * field, in the error colour when [isError] is true. [fieldModifier] applies to the clickable field itself (not the
 * label), so its semantics land on the field's node.
 */
@Composable
fun <T> DSelect(
    options: List<SelectOption<T>>,
    selectedLabel: String?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "Choose…",
    enabled: Boolean = true,
    supportingText: String? = null,
    isError: Boolean = false,
    fieldModifier: Modifier = Modifier,
) {
    val colors = Demo.colors
    val shape = Demo.shapes.small
    var expanded by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableIntStateOf(0) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { DText(it, style = Demo.typography.label, color = colors.inkMuted) }
        Box {
            Row(
                modifier = fieldModifier
                    .fillMaxWidth()
                    .onSizeChanged { anchorHeight = it.height; anchorWidth = it.width }
                    .alpha(if (enabled) 1f else DISABLED_FIELD_ALPHA)
                    .clip(shape)
                    .background(colors.surfaceRaised, shape)
                    .demoOutline(focused || expanded, shape, error = isError)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        role = Role.DropdownList,
                    ) {
                        expanded = !expanded
                    }
                    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DText(
                    selectedLabel ?: placeholder,
                    modifier = Modifier.weight(1f),
                    color = if (selectedLabel == null) colors.inkMuted else colors.ink,
                    maxLines = 1,
                )
                DText(if (expanded) "▴" else "▾", color = colors.inkMuted)
            }
            if (expanded && enabled) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, anchorHeight + POPUP_GAP_PX),
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    SelectMenu(options, width = with(LocalDensity.current) { anchorWidth.toDp() }) {
                        onSelect(it)
                        expanded = false
                    }
                }
            }
        }
        supportingText?.let {
            DText(it, style = Demo.typography.caption, color = if (isError) colors.danger else colors.inkMuted)
        }
    }
}

@Composable
private fun <T> SelectMenu(options: List<SelectOption<T>>, width: androidx.compose.ui.unit.Dp, onPick: (T) -> Unit) {
    val colors = Demo.colors
    val shape = Demo.shapes.medium
    Column(
        modifier = Modifier
            .width(width)
            .heightIn(max = 320.dp)
            .shadow(12.dp, shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
    ) {
        var previousGroup: String? = null
        if (options.isEmpty()) {
            DText("Nothing to choose", modifier = Modifier.padding(8.dp), color = colors.inkMuted)
        }
        options.forEach { option ->
            if (option.group != null && option.group != previousGroup) {
                DText(
                    option.group.uppercase(),
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
                    style = Demo.typography.caption,
                    color = colors.inkMuted,
                )
            }
            previousGroup = option.group
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Demo.shapes.small)
                    .clickable(enabled = option.enabled) { onPick(option.value) }
                    .alpha(if (option.enabled) 1f else DISABLED_OPTION_ALPHA)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DText(option.label, modifier = Modifier.weight(1f), maxLines = 1)
                option.hint?.let { DText(it, style = Demo.typography.caption, color = colors.inkMuted) }
            }
        }
    }
}
