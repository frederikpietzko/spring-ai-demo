package dev.frederikpietzko.springaidemo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.frederikpietzko.springaidemo.desktop.components.DChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.TodoItem
import dev.frederikpietzko.springaidemo.chatprotocol.TodoStatus
import dev.frederikpietzko.springaidemo.chatprotocol.formatMinor
import dev.frederikpietzko.springaidemo.desktop.components.DButton
import dev.frederikpietzko.springaidemo.desktop.components.DButtonStyle
import dev.frederikpietzko.springaidemo.desktop.components.DCard
import dev.frederikpietzko.springaidemo.desktop.components.DText
import dev.frederikpietzko.springaidemo.desktop.theme.Demo

/** A prepared claim to check: its facts, the damage items with their amounts in minor units, the total and Confirm. */
@Composable
internal fun ClaimCard(card: ChatEvent.ClaimCard, canConfirm: Boolean, onConfirm: () -> Unit) {
    DCard(Modifier.fillMaxWidth(), highlighted = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DText("Claim to confirm", style = Demo.typography.title, modifier = Modifier.weight(1f))
            DChip("Class ${card.kaijuClass}", warning = true)
        }
        Fact("Policy", card.policyNumber)
        Fact("District", card.district)
        Fact("Incident", card.incidentAt)
        Fact("Kaiju class", card.kaijuClass.toString())
        DText(card.description, color = Demo.colors.inkMuted)
        Divider()
        SectionLabel("Damage")
        card.damageItems.forEach { item ->
            Fact(item.category, item.description, amount = formatMinor(item.amountMinor))
        }
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            DText("Total", style = Demo.typography.title, modifier = Modifier.weight(1f))
            DText(formatMinor(card.totalMinor), style = Demo.typography.title)
        }
        DButton(
            "Confirm",
            onClick = onConfirm,
            style = DButtonStyle.PRIMARY,
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Demo.colors.border))
}

/** One line of the claim card: a muted [label], its [value] and, right-aligned, an optional [amount]. */
@Composable
private fun Fact(label: String, value: String, amount: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DText(label, color = Demo.colors.inkMuted, modifier = Modifier.width(96.dp))
        DText(value, modifier = Modifier.weight(1f))
        amount?.let { DText(it, style = Demo.typography.mono) }
    }
}

/**
 * The adjuster's latest todo list, one row per item: a status marker, then the active form of the item in progress
 * and the content of every other item.
 */
@Composable
internal fun TodoPanel(todos: List<TodoItem>, modifier: Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DText("Todo list", style = Demo.typography.title)
        val done = todos.count { it.status == TodoStatus.COMPLETED }
        DText("$done of ${todos.size} done", style = Demo.typography.caption, color = Demo.colors.inkMuted)
        Spacer(Modifier.height(2.dp))
        todos.forEach { todo -> TodoRow(todo) }
    }
}

@Composable
private fun TodoRow(todo: TodoItem) {
    val colors = Demo.colors
    val active = todo.status == TodoStatus.IN_PROGRESS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) colors.surfaceRaised else Color.Transparent, Demo.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val [marker, markerColor] = when (todo.status) {
            TodoStatus.PENDING -> "○" to colors.inkMuted
            TodoStatus.IN_PROGRESS -> "●" to colors.accent
            TodoStatus.COMPLETED -> "✓" to colors.success
        }
        DText(marker, color = markerColor, style = Demo.typography.title)
        DText(
            if (active) todo.activeForm else todo.content,
            color = if (todo.status == TodoStatus.COMPLETED) colors.inkMuted else colors.ink,
            style = when (todo.status) {
                TodoStatus.COMPLETED -> Demo.typography.body.copy(textDecoration = TextDecoration.LineThrough)
                TodoStatus.IN_PROGRESS -> Demo.typography.body.copy(fontWeight = FontWeight.Medium)
                TodoStatus.PENDING -> Demo.typography.body
            },
        )
    }
}

/** A tool call that waits for the user: the tool, its arguments and the two choices. */
@Composable
internal fun ConfirmationPanel(pending: ChatEvent.ConfirmationRequest, onDecide: (Boolean) -> Unit) {
    DCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), highlighted = true) {
        DText("APPROVAL NEEDED", style = Demo.typography.caption, color = Demo.colors.warning)
        DText("Confirm ${pending.toolName}", style = Demo.typography.title)
        DText(
            pending.arguments,
            style = Demo.typography.mono,
            color = Demo.colors.inkMuted,
            modifier = Modifier
                .fillMaxWidth()
                .background(Demo.colors.surfaceRaised, Demo.shapes.small)
                .padding(10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DButton("Approve", onClick = { onDecide(true) }, style = DButtonStyle.PRIMARY)
            DButton("Deny", onClick = { onDecide(false) })
        }
    }
}
