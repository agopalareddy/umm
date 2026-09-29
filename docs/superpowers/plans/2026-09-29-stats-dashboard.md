# Stats Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Home's 13 stat tiles with a chart dashboard (14 cards, range switcher, hide and reorder) and add Settings controls to hide stats, stop recording, and delete data.

**Architecture:** Pure aggregators in `:core` turn `StatsEntry` rows plus a `DashboardRange` into a `DashboardStats`. `:app` draws it with Compose `Canvas` charts inside a card registry whose order and hidden set live in DataStore. `StatsRepository` gates writes on the *Record stats* setting; the pipeline is untouched.

**Tech Stack:** Kotlin, Jetpack Compose (Canvas, no chart library), Room, DataStore, JUnit4 + Robolectric.

**Spec:** `docs/superpowers/specs/2026-09-29-stats-dashboard-design.md`

## Global Constraints

- Work on branch `feat/stats-dashboard`. Commit after every task, Conventional Commits, subject ≤ 50 chars, body wrapped at 72. **Never add a `Co-Authored-By` trailer.**
- Change files with the native Edit/Write tools, never with sed, python, or heredocs.
- No chart library and no new dependency. `material-icons-extended` is already in the project.
- Days are local calendar dates in the device time zone; weeks start on Monday; ranges are the last N days ending today, inclusive; the previous period is the N days before that; `ALL` starts at the first row and has no previous period.
- Counted rows are `succeeded == true`, except cost and success rate, which include failed attempts.
- Typing speed is 40 wpm (1,500 ms per word); a novel is 80,000 words; speaking pace uses only rows with `audioMs >= 2000`.
- Card IDs, in default order: `summary`, `streak_calendar`, `milestones`, `fun_facts`, `words_per_day`, `time_saved`, `speaking_pace`, `spend_per_day`, `spend_by_model`, `budget`, `when_you_dictate`, `where_you_dictate`, `cleanup_levels`, `reliability`.
- Every chart carries a one-sentence TalkBack description built by the same data it draws; touch targets ≥ 48 dp; animations are skipped when the system animation scale is 0.
- Core tests: `./gradlew :core:testDebugUnitTest --tests '<Class>'`. App tests: `./gradlew :app:testDebugUnitTest --tests '<Class>'`. Install with `./gradlew installDebug -PversionCode=10100` (needs `ANDROID_HOME=~/Android/Sdk`).
- Test style: JUnit4, `UTC` zone unless a test is about zones, helper `at(day, hour)` and `entry(...)` as in `UsageSummaryTest`.

## Review Focus

1. A dictation near midnight or on a DST change day lands on the correct local day (zone tests in Task 4).
2. A failed attempt costs money but adds no words or dictations (Tasks 4, 7).
3. Zero or tiny audio length must not divide by zero or distort pace (Task 4).
4. One dictation total, or everything on one day: no NaN or infinite deltas, no empty-chart crash (Tasks 4, 10).
5. A saved layout with unknown or removed IDs, or every card hidden, must still render sensibly (Tasks 2, 15).
6. Deleting data or pausing recording while the dashboard is open updates it without a crash (Tasks 3, 9).

## File Structure

| File | Responsibility |
|---|---|
| `core/.../stats/DashboardRange.kt` | Range enum and window date math |
| `core/.../stats/DashboardLayout.kt` | Card IDs, default order, saved-layout merge, move |
| `core/.../stats/PeriodStats.kt` | Totals, previous period, per-day buckets, pace |
| `core/.../stats/Engagement.kt` | Calendar, streaks, badges, fun facts (lifetime) |
| `core/.../stats/Habits.kt` | Hour grid, top apps, levels, latency, success rate |
| `core/.../stats/Costs.kt` | Spend by model, per-dictation cost, month projection |
| `core/.../stats/DashboardStats.kt` | Composes the four calculators |
| `app/.../ui/charts/*.kt` | Chart composables, `ChartText`, motion check |
| `app/.../settings/dashboard/*.kt` | Card registry, cards, `Dashboard`, edit mode, sample data |
| `app/.../settings/StatsPage.kt` | Settings → Stats page |

---

### Task 1: Stats settings keys

**Files:**
- Modify: `core/src/main/kotlin/io/github/agopalareddy/umm/core/data/SettingsRepository.kt`
- Test: `core/src/test/kotlin/io/github/agopalareddy/umm/core/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `UmmSettings` gains `statsVisible: Boolean = true`, `statsRecording: Boolean = true`, `dashboardRange: DashboardRange = DashboardRange.D30`, `dashboardOrder: List<String> = emptyList()`, `dashboardHidden: Set<String> = emptySet()` (empty order means default order). Keys: `stats_visible`, `stats_recording`, `dashboard_range`, `dashboard_order` (comma-separated), `dashboard_hidden` (comma-separated).
- Creates `core/src/main/kotlin/io/github/agopalareddy/umm/core/stats/DashboardRange.kt` containing only `enum class DashboardRange(val days: Int?) { D7(7), D30(30), D90(90), ALL(null) }`; Task 2 adds its window functions.

- [ ] **Step 1:** Extend `defaultsMatchSpec` and `roundTripsEveryField` with the five fields (non-default values: `statsVisible=false`, `statsRecording=false`, `dashboardRange=D90`, `dashboardOrder=listOf("budget","summary")`, `dashboardHidden=setOf("fun_facts")`). Add `unknownRangeFallsBackToDefault`: write `"bogus"` under `dashboard_range` via the DataStore, expect `D30`.
- [ ] **Step 2:** Run `SettingsRepositoryTest`; expect compile failure or FAIL.
- [ ] **Step 3:** Implement the fields, keys, `update` writes and `toSettings` reads, following the existing enum-with-fallback pattern (`runCatching { valueOf }`).
- [ ] **Step 4:** Run `SettingsRepositoryTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(stats): add stats and dashboard settings`.

### Task 2: DashboardRange and DashboardLayout

**Files:**
- Modify: `core/.../stats/DashboardRange.kt` (created in Task 1)
- Create: `core/.../stats/DashboardLayout.kt`
- Test: `core/src/test/.../stats/DashboardRangeTest.kt`, `DashboardLayoutTest.kt`

**Interfaces:**
- Produces:
  - `DashboardRange` gains `fun window(today: LocalDate, firstRowDay: LocalDate?): ClosedRange<LocalDate>` and `fun previousWindow(today: LocalDate): ClosedRange<LocalDate>?` (null for `ALL`). For `ALL`, `window` starts at `firstRowDay ?: today`.
  - `object DashboardLayout { val DEFAULT_ORDER: List<String>; fun merge(savedOrder: List<String>, savedHidden: Set<String>): Layout; fun move(order: List<String>, id: String, by: Int): List<String> }` and `data class Layout(val order: List<String>, val hidden: Set<String>) { val visible: List<String> }`.

- [ ] **Step 1:** Range tests: `d7WindowIsSevenDaysEndingToday` (today 2026-09-27 → 09-21..09-27), `previousWindowIsTheSevenDaysBefore` (09-14..09-20), `allStartsAtFirstRowAndHasNoPrevious`, `allWithNoRowsIsJustToday`. Layout tests: `emptySavedStateGivesDefaultOrder`, `unknownIdsAreDropped`, `newIdsAreAppendedInDefaultOrder` (saved = 3 ids → those 3 first, remaining 11 after in default order), `hiddenIsPrunedToKnownIds`, `visibleExcludesHidden`, `allHiddenGivesEmptyVisible`, `moveClampsAtEnds` (moving the first id up, or the last down, returns the list unchanged), `moveSwapsNeighbours`.
- [ ] **Step 2:** Run both classes; expect FAIL.
- [ ] **Step 3:** Implement per the signatures. `merge` keeps saved order for known IDs, then appends missing defaults.
- [ ] **Step 4:** Run both classes; expect PASS.
- [ ] **Step 5:** Commit `feat(stats): add dashboard range and layout`.

### Task 3: Recording gate, deleteAll, dashboard flow

**Files:**
- Modify: `core/.../stats/StatsDao.kt`, `core/.../stats/StatsRepository.kt`, `app/.../AppGraph.kt`
- Test: `core/src/test/.../stats/StatsRepositoryTest.kt`

**Interfaces:**
- Produces: `StatsRepository(db: UmmDatabase, enabled: suspend () -> Boolean = { true })`; `suspend fun deleteAll()`; `fun observeAll(): Flow<List<StatsEntry>>`. `record()` returns without writing when `enabled()` is false. (`observeDashboard` is added in Task 8.) `observeSummary` stays until Task 16.
- `AppGraph.stats` becomes `StatsRepository(database) { settings.settings.first().statsRecording }`.

- [ ] **Step 1:** Add tests: `recordDoesNothingWhenDisabled` (repo built with `enabled = { false }`; after `record`, `all()` is empty), `deleteAllEmptiesTheTable`, `deleteAllWorksWhileDisabled`, `observeAllEmitsEmptyAfterDelete` (collect first emission after record, then after `deleteAll`, expect size 1 then 0).
- [ ] **Step 2:** Run `StatsRepositoryTest`; expect FAIL.
- [ ] **Step 3:** Add `@Query("DELETE FROM dictation_stats") suspend fun deleteAll()` to the DAO; implement the repository changes and the `AppGraph` wiring.
- [ ] **Step 4:** Run `StatsRepositoryTest` and `:core:testDebugUnitTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(stats): gate recording and add delete`.

### Task 4: PeriodStats

**Files:**
- Create: `core/.../stats/PeriodStats.kt`
- Test: `core/src/test/.../stats/PeriodStatsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Totals(val dictations: Int, val words: Int, val timeSavedMs: Long, val costUsd: Double)
  data class DayStat(val date: LocalDate, val dictations: Int, val words: Int, val timeSavedMs: Long, val costUsd: Double, val wpm: Double?)
  data class PeriodStats(val current: Totals, val previous: Totals?, val days: List<DayStat>, val averageWpm: Double?) {
      companion object { fun from(rows: List<StatsEntry>, range: DashboardRange, nowMs: Long, zone: ZoneId): PeriodStats }
  }
  ```
- Rules: `days` is zero-filled, one per date of the window, ascending. `words`/`dictations` count successful rows; `costUsd` counts all rows. Per-day `timeSavedMs = max(0, words*1500 - audioMs - latencyMs)` over that day's successful rows; `Totals.timeSavedMs` is the sum of the days. `wpm` per day and `averageWpm` = `rawWords / (audioMs/60000)` over successful rows with `audioMs >= 2000`, else null. `previous` is null for `ALL`.

- [ ] **Step 1:** Tests (fixture from `UsageSummaryTest`, `today = 2026-09-27`):
  - `zeroFillsEveryDayOfRange`: D7, no rows → 7 days `09-21..09-27`, all zeros, `previous` non-null and zero.
  - `previousIsTheEqualWindowBefore`: D7; two rows on 09-20, one on 09-21 → `current.dictations == 1`, `previous.dictations == 2`.
  - `allHasNoPreviousAndStartsAtFirstRow`.
  - `failedRowsCostButAddNothingElse`: failed row `cost=0.01` → `current.costUsd == 0.01`, `current.dictations == 0`, `current.words == 0`.
  - `timeSavedIsPerDayAndNeverNegative`: a day with 1 word, 30,000 ms audio → `0`; a day with 100 words, 30,000 audio, 2,000 latency → `150000 - 32000 = 118000`; totals equal the sum.
  - `paceIgnoresClipsUnderTwoSeconds`: `rawWords=10, audioMs=1999` → `wpm == null`; `rawWords=60, audioMs=30000` → `wpm == 120.0`, `averageWpm == 120.0`; `audioMs=0` does not throw.
  - `usesLocalDayNotUtcDay`: zone `America/Los_Angeles`, row at `2026-09-28T02:00Z` → bucketed on 09-27.
  - `dstDayKeepsBothEndsInOneBucket`: zone `Europe/Berlin`, rows at 00:30 and 23:30 local on 2026-10-25 (25-hour day) → one `DayStat` with `dictations == 2`.
  - `singleDictationHasNoNanValues`: one row → `averageWpm` finite, all totals finite.
- [ ] **Step 2:** Run `PeriodStatsTest`; expect FAIL.
- [ ] **Step 3:** Implement `PeriodStats.from` with one grouping pass (`groupBy` local date) and the window from `DashboardRange`.
- [ ] **Step 4:** Run `PeriodStatsTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(stats): add period totals and daily buckets`.

### Task 5: Engagement

**Files:**
- Create: `core/.../stats/Engagement.kt`
- Test: `core/src/test/.../stats/EngagementTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class CalendarDay(val date: LocalDate, val dictations: Int, val step: Int)
  data class Badge(val id: String, val label: String, val earned: Boolean)
  data class NextBadge(val id: String, val label: String, val progress: Float)
  data class Engagement(val calendar: List<CalendarDay>, val currentStreak: Int, val bestStreak: Int,
      val badges: List<Badge>, val next: NextBadge?, val fillersRemoved: Int, val novelShare: Double, val typingAvoidedMs: Long) {
      companion object { fun from(rows: List<StatsEntry>, nowMs: Long, zone: ZoneId): Engagement }
  }
  ```
- Rules: ignores the range (lifetime, calendar is the last 84 days ending today). `step` is 0 for no dictations, else `ceil(4 * (non-zero days with count <= this day's count) / (non-zero day count))`. Badge IDs and labels: `words_100/1000/10000` ("100 words" …), `dictations_10/100/1000`, `streak_3/7/30` (best streak), `spoken_1h`. `next` = the unearned badge with the highest `current/target`, first in list order on ties, null when all earned. `novelShare = words / 80000.0`; `typingAvoidedMs = words * 1500`.

- [ ] **Step 1:** Tests: `calendarCoversLast84DaysEndingToday` (size 84, last date today, first today−83), `stepsFollowQuartilesOfNonZeroDays` (counts 1,2,3,4 → steps 1,2,3,4), `equalCountsAllGetTopStep` (three days of 5 → all 4), `currentStreakEndsTodayOrYesterday`, `bestStreakIsLongestRun` (runs of 2 and 4 days → 4), `twoBigDictationsEarnWordBadges` (two 500-word rows on one day → `words_100` and `words_1000` earned, `words_10000` not, `next.id == "streak_3"`, `next.progress == 1f/3`), `emptyRowsEarnNothing` (`next` non-null, streaks 0, calendar all step 0), `factsFromTotals` (fillers summed, `novelShare == words/80000.0`, `typingAvoidedMs == words*1500`), `failedRowsAreIgnored`.
- [ ] **Step 2:** Run `EngagementTest`; expect FAIL.
- [ ] **Step 3:** Implement `Engagement.from`; reuse the streak logic already in `UsageSummary` (copy, since `UsageSummary` is deleted in Task 16).
- [ ] **Step 4:** Run `EngagementTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(stats): add streaks, badges, and fun facts`.

### Task 6: Habits

**Files:**
- Create: `core/.../stats/Habits.kt`
- Test: `core/src/test/.../stats/HabitsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class AppCount(val packageName: String, val dictations: Int)
  data class LatencyPoint(val date: LocalDate, val avgMs: Long)
  data class Habits(val hourGrid: List<List<Int>>, val topApps: List<AppCount>, val levelCounts: Map<CleanupLevel, Int>,
      val latency: List<LatencyPoint>, val successRate: Double?, val attempts: Int) {
      companion object { fun from(rows: List<StatsEntry>, range: DashboardRange, nowMs: Long, zone: ZoneId): Habits }
  }
  ```
- Rules: everything is limited to the range window. `hourGrid[weekday][hour]` counts successful dictations, Monday = 0, local hour. `topApps` are the top 6 by count then by package name. `latency` has one point per day that has successful rows with a latency. `successRate = successful / attempts` over the range, null when there are no attempts.

- [ ] **Step 1:** Tests: `hourGridIsMondayFirstLocalHour` (Mon 2026-09-21 09:00 → `grid[0][9] == 1`; Sun 2026-09-27 23:00 → `grid[6][23] == 1`), `topAppsRankedAndCappedAtSix` (seven apps), `tiesBreakByPackageName`, `successRateCountsAttempts` (3 ok + 1 failed → 0.75, attempts 4), `successRateNullWithoutRows`, `latencyAveragesPerDay` (1,000 and 2,000 ms same day → 1,500), `rowsOutsideRangeAreIgnored` (D7 with a row 8 days ago).
- [ ] **Step 2:** Run `HabitsTest`; expect FAIL.
- [ ] **Step 3:** Implement `Habits.from`.
- [ ] **Step 4:** Run `HabitsTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(stats): add habit aggregates`.

### Task 7: Costs

**Files:**
- Create: `core/.../stats/Costs.kt`
- Test: `core/src/test/.../stats/CostsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class ModelSpend(val model: String, val usd: Double, val dictations: Int)
  data class Costs(val totalUsd: Double, val perDictationUsd: Double?, val bySttModel: List<ModelSpend>,
      val byCleanupModel: List<ModelSpend>, val projectedMonthUsd: Double?) {
      companion object { fun from(rows: List<StatsEntry>, range: DashboardRange, nowMs: Long, zone: ZoneId): Costs }
  }
  ```
- Rules: over the range. `totalUsd` includes failed rows. `perDictationUsd = totalUsd / successful count`, null with none. Each row's full cost counts under the model it used in each list (so each list sums to `totalUsd`); a null model is grouped as `"Unknown"`; lists sorted by `usd` descending. `projectedMonthUsd` = spend in the last 7 days (today inclusive) ÷ 7 × days in the current month, only when at least 3 of those 7 days have rows, else null; it ignores the range.

- [ ] **Step 1:** Tests: `eachListSumsToTotal`, `nullModelBecomesUnknown`, `failedRowsCountInTotalButNotPerDictationDivisor`, `projectionNeedsThreeActiveDays` (2 days → null), `projectionScalesToMonthLength` (0.03 total across 3 active days in the last 7, September → `0.03 / 7 * 30`, tolerance 1e-9), `perDictationNullWithoutSuccess`.
- [ ] **Step 2:** Run `CostsTest`; expect FAIL.
- [ ] **Step 3:** Implement `Costs.from`.
- [ ] **Step 4:** Run `CostsTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(stats): add spend breakdowns and projection`.

### Task 8: DashboardStats and the dashboard flow

**Files:**
- Create: `core/.../stats/DashboardStats.kt`
- Modify: `core/.../stats/StatsRepository.kt`
- Test: `core/src/test/.../stats/DashboardStatsTest.kt`, `StatsRepositoryTest.kt`

**Interfaces:**
- Produces: `data class DashboardStats(val range: DashboardRange, val hasData: Boolean, val period: PeriodStats, val engagement: Engagement, val habits: Habits, val costs: Costs)` with `companion fun from(rows, range, nowMs, zone)`; `hasData` is true when any row is successful. `StatsRepository.observeDashboard(range: DashboardRange, clock: () -> Long = System::currentTimeMillis, zone: ZoneId = ZoneId.systemDefault()): Flow<DashboardStats>` (computed with `flowOn(Dispatchers.Default)`).

- [ ] **Step 1:** Tests: `hasDataFalseForNoRowsAndForOnlyFailures`, `usesTheGivenRangeForPeriodAndHabits` (D7 excludes an 8-day-old row from `period.current` and `habits.attempts` but not from `engagement` lifetime facts), and in `StatsRepositoryTest` `observeDashboardUpdatesAfterRecordAndDelete`.
- [ ] **Step 2:** Run both; expect FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `:core:testDebugUnitTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(stats): compose dashboard stats`.

### Task 9: Settings → Stats page (with debug sample data)

**Files:**
- Create: `app/.../settings/StatsPage.kt`, `app/.../settings/dashboard/SampleData.kt`
- Modify: `app/.../settings/SettingsScreen.kt` (add a `NavRow(Icons.Default.BarChart, "Stats", …)`), `app/.../settings/MainActivity.kt` (`Routes.STATS = "settings/stats"` and its `composable`)

**Interfaces:**
- Produces: `StatsPage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange)` with two `SwitchRow`s (*Show stats on Home*, *Record stats*), a *Delete stats data* button with a confirmation `AlertDialog` calling `graph.stats.deleteAll()`, and, only when `BuildConfig.DEBUG`, a *Load sample data* button. `object SampleData { suspend fun seed(db: UmmDatabase, nowMs: Long) }` writes about 600 deterministic rows (`Random(42)`) over 90 days with varied apps, levels, models, hours, costs, and a few failures, through its own `StatsRepository(db)` (default `enabled`), so seeding works even when recording is off. Call it with `graph.database`.

- [ ] **Step 1:** Implement the page, route, and row. The summary text on the Settings row reads `Shown · Recording` / `Hidden · Recording` / `Shown · Paused` / `Hidden · Paused`.
- [ ] **Step 2:** Run `./gradlew installDebug -PversionCode=10100`. Expected on device: Settings → Stats shows the switches; toggling *Record stats* off and dictating adds no row (History still gets the entry); *Load sample data* then *Delete stats data* fills and empties the table (confirm via `adb shell run-as io.github.agopalareddy.umm sqlite3 databases/umm.db "select count(*) from dictation_stats"` or the dashboard later).
- [ ] **Step 3:** Build `./gradlew assembleRelease` and confirm the seeder is stripped: `unzip -p app/build/outputs/apk/release/app-release.apk 'classes*.dex' | grep -c SampleData` prints `0`.
- [ ] **Step 4:** Commit `feat(stats): add stats settings page`.

### Task 10: Chart text and motion helpers

**Files:**
- Create: `app/.../ui/charts/ChartText.kt`, `app/.../ui/charts/Motion.kt`
- Test: `app/src/test/.../ui/charts/ChartTextTest.kt`

**Interfaces:**
- Produces: `object ChartText { fun scaleMax(values: List<Float>): Float /* max of values, never below 1f */; fun delta(current: Double, previous: Double?): String?; fun peak(title: String, span: String, points: List<Pair<String, Double>>, format: (Double) -> String): String; fun empty(title: String): String }` and `@Composable fun rememberMotionEnabled(): Boolean` (false when `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`).
- `delta`: `null` when `previous == null`; `"new"` when `previous == 0.0 && current > 0`; `null` when both are 0; otherwise `"▲ 12%"` or `"▼ 8%"` (rounded, no decimals, `"▲ 0%"` for no change).
- `peak("Words per day", "last 30 days", …)` returns e.g. `"Words per day, last 30 days. Most on Tue: 420."`; with no points > 0 it returns `empty(title)` = `"<title>: no data yet."`.

- [ ] **Step 1:** Tests: `scaleMaxNeverBelowOne` (empty list, all zeros, and `[0.2f]` → `1f`; `[3f, 7f]` → `7f`), `deltaNullWithoutPrevious`, `deltaNewFromZero`, `deltaBothZeroIsNull`, `deltaRoundsPercent` (120 vs 100 → `"▲ 20%"`, 92 vs 100 → `"▼ 8%"`), `peakNamesTheLargestPoint`, `peakWithAllZeroesIsEmptySentence`.
- [ ] **Step 2:** Run `ChartTextTest`; expect FAIL.
- [ ] **Step 3:** Implement both files.
- [ ] **Step 4:** Run `ChartTextTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(charts): add chart text and motion helpers`.

### Task 11: Bar, line, donut, and list charts

**Files:**
- Create: `app/.../ui/charts/BarChart.kt`, `LineChart.kt`, `Donut.kt`, `HorizontalBars.kt`, `ProgressBar.kt`

**Interfaces:**
- Produces (all `internal`, all set `semantics { contentDescription = description }` on the chart node and draw with `MaterialTheme.colorScheme`; one draw-in animation gated on `rememberMotionEnabled()`):
  - `BarChart(values: List<Float>, labels: List<String>, description: String, modifier: Modifier = Modifier, previous: List<Float>? = null)`; previous bars are drawn faint behind.
  - `LineChart(values: List<Float?>, labels: List<String>, description: String, modifier: Modifier = Modifier)`; null values break the line.
  - `Donut(slices: List<DonutSlice>, description: String, modifier: Modifier = Modifier)` with `data class DonutSlice(val label: String, val value: Float)`; slices use distinct theme colors and a legend below.
  - `HorizontalBars(items: List<BarItem>, description: String, modifier: Modifier = Modifier)` with `data class BarItem(val label: String, val value: Float, val valueText: String, val sublabel: String? = null)`.
  - `ProgressBar(progress: Float, description: String, modifier: Modifier = Modifier)`.
- Empty or all-zero input draws a flat baseline; scale every chart with `ChartText.scaleMax` so nothing divides by zero.

- [ ] **Step 1:** Implement the five files. Bars and lines scale to `max(values, 1f)`; a single value is drawn, not skipped.
- [ ] **Step 2:** Run `./gradlew :app:compileDebugKotlin`; expect success. (Visual check happens in Tasks 13–15 where the charts are hosted.)
- [ ] **Step 3:** Commit `feat(charts): add bar, line, donut, and list charts`.

### Task 12: Heatmaps

**Files:**
- Create: `app/.../ui/charts/CalendarHeatmap.kt`, `app/.../ui/charts/HourHeatmap.kt`

**Interfaces:**
- Consumes: `CalendarDay` (Task 5).
- Produces: `CalendarHeatmap(days: List<CalendarDay>, description: String, modifier: Modifier = Modifier)` (12 columns of weeks, Monday-first rows, cell fill and alpha vary by `step` 0–4 so it isn't color-only) and `HourHeatmap(grid: List<List<Int>>, description: String, modifier: Modifier = Modifier)` (7 rows × 24 columns with weekday labels and 6-hour tick labels, intensity by value relative to the grid max).

- [ ] **Step 1:** Implement both.
- [ ] **Step 2:** Run `./gradlew :app:compileDebugKotlin`; expect success.
- [ ] **Step 3:** Commit `feat(charts): add calendar and hour heatmaps`.

### Task 13: Card registry, Dashboard shell, and the first four cards

**Files:**
- Create: `app/.../settings/dashboard/Cards.kt`, `app/.../settings/dashboard/Dashboard.kt`, `app/.../settings/dashboard/CardsFun.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal class CardContext(val stats: DashboardStats, val range: DashboardRange, val key: String?)
  internal class CardSpec(val id: String, val title: String, val content: @Composable (CardContext) -> Unit)
  internal object Cards { val byId: Map<String, CardSpec> }   // all 14 ids from DashboardLayout.DEFAULT_ORDER
  @Composable internal fun Dashboard(stats: DashboardStats?, settings: UmmSettings, onChange: SettingsChange)
  ```
- `Dashboard` shows: a range `FilterChip` row (7 days / 30 days / 90 days / All) writing `dashboardRange`; an *Edit* text button (Task 15); the "Recording is paused" note when `!settings.statsRecording`; then each visible card in `DashboardLayout.merge(...)` order inside a `Card` with the card title. `stats == null` shows nothing; `!stats.hasData` shows the single line "Your stats show up here after your first dictation."
- This task implements `summary` (three figures with `ChartText.delta` against the previous period, no delta for `ALL`), `streak_calendar`, `milestones` (earned chips plus `ProgressBar` to `next`), `fun_facts`. Every other ID is registered with a placeholder `Text("Coming in the next task")` that Tasks 14–15 replace.

- [ ] **Step 1:** Implement the registry, shell, and four cards. Cards with too little data show "Needs a few more days" instead of an empty chart.
- [ ] **Step 2:** Wire Home: in `HomeScreen.kt` read `graph.settings.settings`, collect `graph.stats.observeDashboard(settings.dashboardRange)` into `stats`, and render `Dashboard(stats, settings, change)` only when `settings.statsVisible`, in place of the old `Stats(s, plan)` call (the old composables and `observeSummary` are deleted in Task 16). Load sample data from Settings → Stats, run `installDebug`; expected on device: range chips work, Summary shows arrows, calendar and badges render in light and dark.
- [ ] **Step 3:** Commit `feat(stats): add dashboard shell and fun cards`.

### Task 14: Productivity and cost cards

**Files:**
- Create: `app/.../settings/dashboard/CardsProductivity.kt`, `app/.../settings/dashboard/CardsCost.kt`
- Modify: `Cards.kt` (replace placeholders)

**Interfaces:**
- Consumes: `PeriodStats.days`, `Costs`, `KeyCheck` and `rememberKeyCheck(key)` from `SettingsScreen.kt`.
- Produces cards: `words_per_day` (`BarChart`, previous period faint, weekly buckets for D90/ALL made by grouping `days` into Monday-start weeks, description via `ChartText.peak`), `time_saved` (cumulative `LineChart`), `speaking_pace` (`LineChart` of `wpm` with the average as text), `spend_per_day` (`BarChart` plus total, per-dictation, and projected month lines; the projection line only when non-null), `spend_by_model` (two `HorizontalBars` lists titled *Speech models* and *Cleanup models* with a note "Each dictation's full cost counts under the model it used"), `budget` (calls `rememberKeyCheck` only when the card is composed; `KeyCheck.Ok` with `limitUsd` → `ProgressBar` of used share plus remaining text; no limit, rejected, or unreachable → an explanatory line).

- [ ] **Step 1:** Implement the six cards.
- [ ] **Step 2:** Run `installDebug`; expected on device with sample data: all six render at 360 dp and full width, weekly bars appear for 90 days, `budget` shows a plain message if the key has no limit.
- [ ] **Step 3:** Commit `feat(stats): add productivity and cost cards`.

### Task 15: Habit cards and Edit mode

**Files:**
- Create: `app/.../settings/dashboard/CardsHabits.kt`, `app/.../settings/dashboard/EditLayout.kt`
- Modify: `Cards.kt`, `Dashboard.kt`

**Interfaces:**
- Produces cards: `when_you_dictate` (`HourHeatmap`), `where_you_dictate` (`HorizontalBars` with app label via the existing `appLabel(context, packageName)` and the app's category name as `sublabel` from `graph.categories.configFor`), `cleanup_levels` (`Donut`), `reliability` (success percent text plus a `LineChart` of `latency`). Edit mode (`EditLayout(layout: Layout, onChange: (order: List<String>, hidden: Set<String>) -> Unit, onDone: () -> Unit)`): one row per card with a `SwitchRow` for visibility and up/down `IconButton`s labelled `"Move <title> up"` / `"Move <title> down"` (using `DashboardLayout.move`), a *Reset layout* button (writes empty order and hidden), and *Done*. When every card is hidden, the normal view shows "Stats are hidden" with an *Edit* button.

- [ ] **Step 1:** Implement the four cards and Edit mode; changes write through `onChange` to `dashboardOrder` / `dashboardHidden`.
- [ ] **Step 2:** Run `installDebug`; expected on device: hide a card and it disappears, reorder persists after killing and reopening the app, *Reset layout* restores the default, hiding all shows the "Stats are hidden" line.
- [ ] **Step 3:** Commit `feat(stats): add habit cards and layout editing`.

### Task 16: Home wiring and removal of the old summary

**Files:**
- Modify: `app/.../settings/HomeScreen.kt`, `core/.../stats/StatsRepository.kt`
- Delete: `core/.../stats/UsageSummary.kt`, `core/src/test/.../stats/UsageSummaryTest.kt`

- [ ] **Step 1:** In `HomeScreen`, delete the old `summary` state, the `Stats` and `Tiles` composables, and any helpers left unused (`duration`, `money`). Keep the *History* and *Settings* buttons and the Try-it card.
- [ ] **Step 2:** Remove `observeSummary` from `StatsRepository`, delete `UsageSummary.kt` and `UsageSummaryTest.kt` (every case is covered by Tasks 4–7).
- [ ] **Step 3:** Run `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest`; expect PASS.
- [ ] **Step 4:** Run `installDebug`; expected on device: *Show stats on Home* off removes the dashboard and keeps the Try-it card; on restores it; deleting data with the dashboard open shows the empty line without a crash.
- [ ] **Step 5:** Commit `refactor(stats): replace summary tiles with dashboard`.

### Task 17: Final verification and docs

**Files:**
- Modify: `docs/superpowers/specs/2026-09-29-stats-dashboard-design.md` (record that the seeder lives in `main` guarded by `BuildConfig.DEBUG` and is stripped by R8, not in `src/debug`/`src/release`), `README.md` and `docs/privacy/index.html` only if they describe stats or say what is stored

- [ ] **Step 1:** Run `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest assembleRelease`; expect all green, and `unzip -p app/build/outputs/apk/release/app-release.apk 'classes*.dex' | grep -c SampleData` prints `0`. Note the APK size against the 3,340,322 bytes measured on 2026-09-29.
- [ ] **Step 2:** Device pass with sample data at 360 dp (`adb shell wm size 810x1800`, then `wm size reset`) and full width, light and dark: every card renders, no clipping, long app names ellipsize, D7/D30/D90/ALL all work.
- [ ] **Step 3:** With TalkBack on, confirm three charts read their description sentences and Edit mode's move buttons are announced with their labels.
- [ ] **Step 4:** Grep `README.md` and `docs/privacy/index.html` for "stats"; update any statement that no longer matches (stats stay on the device and can be hidden, paused, or deleted).
- [ ] **Step 5:** Commit `docs: describe stats controls`.
