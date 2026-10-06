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
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.stats.DashboardLayout
import io.github.agopalareddy.umm.core.stats.Layout as CardLayout
import io.github.agopalareddy.umm.settings.SwitchRow

/** The changes Edit mode makes to a layout, apart from how they are drawn. */
object LayoutEdit {
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

    /** What *Reset layout* does: whatever the layout was, both empty. */
    @Suppress("UNUSED_PARAMETER")
    fun reset(layout: CardLayout): CardLayout = CardLayout(RESET_ORDER, RESET_HIDDEN)

    /**
     * A settings update that applies [edit] to the layout read from the settings it is handed, not to one seen
     * earlier, so two quick edits both land.
     */
    fun editOf(edit: (CardLayout) -> CardLayout): (UmmSettings) -> UmmSettings = { s ->
        val next = edit(DashboardLayout.merge(s.dashboardOrder, s.dashboardHidden))
        s.copy(dashboardOrder = next.order, dashboardHidden = next.hidden)
    }
}

/**
 * One row per card, hidden or not: a switch for whether it shows, and buttons to move it up or down. Every change
 * goes out through [onEdit] as a function of the layout, to be applied to the latest saved one; *Reset layout*
 * sends both empty.
 */
@Composable
fun EditLayout(layout: CardLayout, onEdit: ((CardLayout) -> CardLayout) -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        layout.order.forEach { id ->
            val title = Cards.byId[id]?.title ?: id
            key(id) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SwitchRow(checked = id !in layout.hidden, onCheckedChange = { on -> onEdit { LayoutEdit.setVisible(it, id, on) } }) {
                            Text(title, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    IconButton(onClick = { onEdit { LayoutEdit.move(it, id, -1) } }, enabled = LayoutEdit.canMove(layout, id, -1)) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $title up")
                    }
                    IconButton(onClick = { onEdit { LayoutEdit.move(it, id, 1) } }, enabled = LayoutEdit.canMove(layout, id, 1)) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $title down")
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onEdit(LayoutEdit::reset) }) { Text("Reset layout") }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
