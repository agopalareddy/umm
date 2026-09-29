package io.github.agopalareddy.umm.core.stats

/** How far back the stats dashboard looks; null days means all time. */
enum class DashboardRange(val days: Int?) { D7(7), D30(30), D90(90), ALL(null) }
