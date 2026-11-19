package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.desktop.components.DCard
import dev.frederikpietzko.springaidemo.desktop.components.DText
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/** A table a tool computed: the title, a header line and one line per row, every cell as text in an equal column. */
@Composable
internal fun AnalyticsTable(table: ChatEvent.AnalyticsTable) {
    DCard(Modifier.fillMaxWidth()) {
        DText(table.title, style = Demo.typography.label)
        TableRow(table.columns, color = Demo.colors.inkMuted)
        table.rows.forEach { TableRow(it) }
    }
}

@Composable
private fun TableRow(cells: List<String>, color: Color = Demo.colors.ink) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.forEach { DText(it, color = color, modifier = Modifier.weight(1f)) }
    }
}
