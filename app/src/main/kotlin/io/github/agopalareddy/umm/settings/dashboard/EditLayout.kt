package io.github.agopalareddy.umm.settings.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.stats.DashboardLayout
import io.github.agopalareddy.umm.core.stats.Layout as CardLayout
import io.github.agopalareddy.umm.settings.SwitchRow

/** The changes Edit mode makes to a layout, apart from how they are drawn. */
internal object LayoutEdit {
    /** What *Reset layout* saves: nothing, which reads back as the default order with every card shown. */
    val RESET_ORDER: List<String> = emptyList()
    val RESET_HIDDEN: Set<String> = emptySet()

    /** Hides or shows [id]; a hidden card keeps its place in the order. */
    fun setVisible(layout: CardLayout, id: String, visible: Boolean): CardLayout =
        layout.copy(hidden = if (visible) layout.hidden - id else layout.hidden + id)

    /** Moves [id] by [by] places (negative is up) through the whole order, hidden cards included. */
    fun move(layout: CardLayout, id: String, by: Int): CardLayout = layout.copy(order = DashboardLayout.move(layout.order, id, by))

    /** Whether [id] has room to move by [by]: not for a card already at that end, nor for one that is not listed. */
    fun canMove(layout: CardLayout, id: String, by: Int): Boolean {
        val at = layout.order.indexOf(id)
        return at >= 0 && at + by in layout.order.indices
    }
}

/**
 * One row per card, hidden or not: a switch for whether it shows, and buttons to move it up or down. Every change
 * goes out through [onChange] as the full order and hidden set; *Reset layout* sends both empty.
 */
@Composable
internal fun EditLayout(layout: CardLayout, onChange: (order: List<String>, hidden: Set<String>) -> Unit, onDone: () -> Unit) {
    fun change(next: CardLayout) = onChange(next.order, next.hidden)
    Column(Modifier.fillMaxWidth()) {
        layout.order.forEach { id ->
            val title = Cards.byId[id]?.title ?: id
            key(id) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SwitchRow(checked = id !in layout.hidden, onCheckedChange = { change(LayoutEdit.setVisible(layout, id, it)) }) {
                            Text(title, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    IconButton(onClick = { change(LayoutEdit.move(layout, id, -1)) }, enabled = LayoutEdit.canMove(layout, id, -1)) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $title up")
                    }
                    IconButton(onClick = { change(LayoutEdit.move(layout, id, 1)) }, enabled = LayoutEdit.canMove(layout, id, 1)) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $title down")
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(LayoutEdit.RESET_ORDER, LayoutEdit.RESET_HIDDEN) }) { Text("Reset layout") }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
