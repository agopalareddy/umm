package io.github.agopalareddy.umm.core.stats

/** Card order and visibility on the stats dashboard. */
data class Layout(val order: List<String>, val hidden: Set<String>) {
    val visible: List<String> get() = order.filter { it !in hidden }
}

object DashboardLayout {
    val DEFAULT_ORDER = listOf(
        "summary", "streak_calendar", "milestones", "fun_facts", "words_per_day", "time_saved", "speaking_pace",
        "spend_per_day", "spend_by_model", "budget", "when_you_dictate", "where_you_dictate", "cleanup_levels",
        "reliability",
    )

    /** Keeps the saved order for known ids (first occurrence wins), then appends any missing defaults. */
    fun merge(savedOrder: List<String>, savedHidden: Set<String>): Layout {
        val known = DEFAULT_ORDER.toSet()
        val kept = savedOrder.filter { it in known }.distinct()
        return Layout(kept + DEFAULT_ORDER.filter { it !in kept }, savedHidden.filterTo(mutableSetOf()) { it in known })
    }

    /** Moves [id] by [by] places (negative is up), stopping at either end. */
    fun move(order: List<String>, id: String, by: Int): List<String> {
        val from = order.indexOf(id)
        if (from < 0) return order
        val to = (from + by).coerceIn(0, order.lastIndex)
        if (to == from) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }
}
