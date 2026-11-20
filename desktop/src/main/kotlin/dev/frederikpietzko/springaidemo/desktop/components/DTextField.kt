package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/**
 * A single-line text field with an optional [label] above and [supportingText] below. [fieldModifier] applies to the
 * editable field itself (not the label), so its semantics land on the field's node.
 */
@Composable
fun DTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    numeric: Boolean = false,
    monospace: Boolean = false,
    fieldModifier: Modifier = Modifier,
) {
    val colors = Demo.colors
    val shape = Demo.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val textStyle = (if (monospace) Demo.typography.mono else Demo.typography.body).copy(color = colors.ink)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { DText(it, style = Demo.typography.label, color = colors.inkMuted) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accent),
            interactionSource = interaction,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            modifier = fieldModifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceRaised, shape)
                        .demoOutline(focused, shape, error = isError)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    if (value.isEmpty()) DText(placeholder, style = textStyle, color = colors.inkMuted, maxLines = 1)
                    inner()
                }
            },
        )
        supportingText?.let {
            DText(it, style = Demo.typography.caption, color = if (isError) colors.danger else colors.inkMuted)
        }
    }
}
