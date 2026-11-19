package dev.frederikpietzko.springaidemo.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/** A surface card. A [highlighted] card gets the accent gradient outline. */
@Composable
fun DCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Demo.colors
    val shape = Demo.shapes.large
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface, shape)
            .then(
                if (highlighted) {
                    Modifier.border(1.5.dp, colors.accentGradient, shape)
                } else {
                    Modifier.border(1.dp, colors.border, shape)
                },
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
