package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One destination in the desktop sidebar. */
data class SidebarItem(val icon: ImageVector, val label: String, val route: String)

/** The item for [currentRoute]: the one whose route equals it or is its longest parent, or null. */
fun sidebarSelection(items: List<SidebarItem>, currentRoute: String?): String? {
    if (currentRoute == null) return null
    return items.map { it.route }
        .filter { currentRoute == it || currentRoute.startsWith("$it/") }
        .maxByOrNull { it.length }
}

/**
 * The desktop window's frame: a sidebar of destinations and the current page in a centered column. With no [items]
 * the sidebar is hidden and pages keep their back arrows; the page stays mounted either way.
 */
@Composable
fun SidebarFrame(
    items: List<SidebarItem>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val selected = sidebarSelection(items, currentRoute)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val labels = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            if (items.isNotEmpty()) {
                NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                    items.forEach { item ->
                        NavigationRailItem(
                            selected = item.route == selected,
                            onClick = { onSelect(item.route) },
                            icon = { Icon(item.icon, contentDescription = if (labels) null else item.label) },
                            label = if (labels) ({ Text(item.label) }) else null,
                            alwaysShowLabel = labels,
                        )
                    }
                }
                VerticalDivider()
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    CompositionLocalProvider(LocalShowBack provides items.isEmpty()) { content() }
                }
            }
        }
    }
}
