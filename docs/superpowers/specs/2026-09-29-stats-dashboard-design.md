# Stats dashboard

Status: draft for review, 2026-09-29.

## 1. Goal

Replace the Home screen's 13 label-and-number tiles with a dashboard of charts
that is fun to open, useful, and under the user's control. It serves four jobs:
fun and motivation, cost control, productivity proof, and habit insight.

Everything is computed on the device from data Umm already stores. Nothing is
sent anywhere.

### Non-goals

- Drag-and-drop reordering, a custom date picker, user-made cards.
- Sharing or exporting a recap image.
- Any new data collection. The existing `dictation_stats` table is enough.
- A charting library. Charts are drawn with Compose `Canvas`.

## 2. Decisions

| Topic | Decision |
|---|---|
| Structure | Card registry: each card has a stable ID, a title, and drawing code. The layout is an ordered ID list plus a hidden set. |
| Ranges | 7 days, 30 days, 90 days, All. One range drives every card except lifetime ones (streaks, milestones). Default 30 days. |
| Comparison | Each range is compared with the equal-length period just before it. "All" has no comparison. |
| Customizing | Range switcher, show/hide, and reorder. Reorder uses up/down buttons in an Edit mode, not drag-and-drop, so it works with TalkBack. |
| Stats controls | Three independent settings: *Show stats on Home*, *Record stats*, and *Delete stats data*. |
| Where it lives | On Home, below the Try-it card. |
| Old summary | `UsageSummary` is replaced by the new aggregator; its tests move with it. |

## 3. Settings

New "Stats" section in Settings:

| Control | Effect | Default |
|---|---|---|
| Show stats on Home | Hides or shows the whole dashboard. Recording is unaffected. | On |
| Record stats | Off stops new rows being written. Existing rows stay. | On |
| Delete stats data | Confirmation dialog, then deletes every row in `dictation_stats`. Works with either switch in any position. | button |

When *Record stats* is off and the dashboard is shown, a small "Recording is
paused" note appears under the range switcher. The History screen's report keeps
working: it already prints "?" for a missing model name.

New DataStore keys in `SettingsRepository`: `stats_visible` (true),
`stats_recording` (true), `dashboard_range` (`D30`), `dashboard_order` (comma
separated card IDs), `dashboard_hidden` (comma separated card IDs).

## 4. Cards

All 14 are visible by default, in this order.

| ID | Goal | Shows |
|---|---|---|
| `summary` | Overview | Words, time saved, dictations for the range, each with a ▲/▼ percent against the previous period. |
| `streak_calendar` | Fun | Calendar heatmap of the last 12 weeks (fixed, ignores the range) with current and best streak. |
| `milestones` | Fun | Earned badges and a progress bar to the next one. Lifetime. |
| `fun_facts` | Fun | Umms removed, words as a share of a novel, typing time avoided. |
| `words_per_day` | Productivity | Bars per day (per week for 90 days and All), previous period faint behind. |
| `time_saved` | Productivity | Cumulative line over the range. |
| `speaking_pace` | Productivity | Words per minute over time, with the average. |
| `spend_per_day` | Cost | Bars, plus total, per dictation, and projected month. |
| `spend_by_model` | Cost | Horizontal bars, speech models and cleanup models. |
| `budget` | Cost | Bar against the key's OpenRouter limit. |
| `when_you_dictate` | Habits | Hour × weekday heatmap. |
| `where_you_dictate` | Habits | Top apps as horizontal bars, each with its category. |
| `cleanup_levels` | Habits | Donut of Raw / Light / Formatted / Polished. |
| `reliability` | Habits | Success rate and the wait-time trend. |

## 5. Definitions

- **Day** is the local calendar date in the device's time zone when computed.
- **Range** for 7/30/90 days is the last N days ending today, inclusive. The
  previous period is the N days before that. "All" runs from the first row.
- **Counted** rows are successful dictations, except cost, which includes failed
  attempts because they can still be billed (as today).
- **Speaking pace** is `rawWords / (audioMs / 60000)`, over rows with at least
  2 s of audio, so one-word taps don't distort it.
- **Time saved** is unchanged: typing the words at 40 wpm, minus time spent
  speaking and waiting. The card carries a footnote stating the 40 wpm.
- **Projected month** is the average daily spend over the last 7 days times the
  days in the current month, only shown with at least 3 days of data.
- **Novel** is 80,000 words.
- **Milestones**: 100 / 1,000 / 10,000 words; 10 / 100 / 1,000 dictations;
  3 / 7 / 30 day streaks; 1 hour spoken.
- **Heatmap intensity** uses four steps by quartile of non-zero days, plus empty.
- **Budget** reads the same key info the Account page uses (`limitUsd`,
  `limitRemainingUsd`, `usageMonthlyUsd`, `limitReset`). With no limit set or no
  successful lookup, the card explains that instead of drawing a bar.
- **Weeks** for bars start on Monday.

## 6. Architecture

### `:core`

| Unit | Responsibility |
|---|---|
| `stats.DashboardStats` | Pure `from(rows, range, nowMs, zone)`. Returns per-day and per-week buckets, previous-period totals, the 12-week calendar, the hour × weekday grid, top apps, spend by model, level counts, latency trend, streaks, milestones, and the projection. |
| `stats.DashboardRange` | `D7`, `D30`, `D90`, `ALL`, with the range and previous-range date math. |
| `stats.StatsRepository` | Gains `deleteAll()`. `record()` returns without writing when recording is off, via an injected `enabled: suspend () -> Boolean` wired to the setting in `AppGraph`. `observeSummary()` is replaced by `observeAll()`. |
| `data.SettingsRepository` | The five new keys, added to `UmmSettings`. |
| `stats.DashboardLayout` | Pure `merge(savedOrder, savedHidden, knownIds)`: drops unknown IDs, appends new IDs, keeps the hidden set to known IDs. |

`DictationPipeline` is not modified.

### `:app`

| Unit | Responsibility |
|---|---|
| `ui/charts/` | `BarChart`, `LineChart`, `CalendarHeatmap`, `HourHeatmap`, `Donut`, `HorizontalBars`, `ProgressBar`. Theme colors, one draw-in animation, and a TalkBack summary sentence each. |
| `settings/dashboard/Cards.kt` | The registry: ID → title and composable. |
| `settings/dashboard/Dashboard.kt` | Range chips, the card column, empty and paused states, Edit mode (per card: switch, up, down; plus Reset layout). |
| `settings/HomeScreen.kt` | Renders `Dashboard` only when *Show stats* is on. Loses the old `Stats` and `Tiles`. |
| `settings/SettingsScreen.kt` | The new Stats section and delete confirmation. |
| `settings/dashboard/SampleData.kt` | `SampleData`: seeds about 90 days of varied rows. Lives in `main`, not `src/debug` or `src/release`; the only call sites are behind `BuildConfig.DEBUG` on the Stats page, so R8 strips it from release builds (checked: no `SampleData` in the release dex). |

### Data flow

`StatsRepository.observeAll()` → `DashboardStats.from(rows, range, now, zone)` →
`Dashboard` → each card reads only the slice it needs. Range or layout changes
recompute or reorder without touching the database again.

## 7. Empty and edge states

- No rows at all: one friendly line, as today, and no charts.
- Too little data for a chart (under 3 days for lines and trends): the card
  shows "Needs a few more days" and keeps its title.
- All cards hidden: Home shows a "Stats are hidden" line with an Edit button.
- Very long app names truncate with an ellipsis. Unknown package names fall
  back to the package name, as `appLabel` does today.
- Thousands of rows: a single pass over the list per recompute is enough; no
  pagination.

## 8. Accessibility

- Every chart has a one-sentence description with its peak and total ("Words
  per day, last 30 days, most on Tuesday: 420"). The sentence is built by the
  same code that draws the chart, so they cannot disagree.
- Charts don't rely on color alone; heatmap steps also differ in fill level.
- Edit mode's up/down buttons have labels ("Move Words per day up").
- Touch targets are at least 48 dp.
- Animations are skipped when the system's remove-animations setting is on.

## 9. Testing

Unit tests (`:core`):

- `DashboardStatsTest`: range and previous-period boundaries, week starts,
  streaks across midnight and a DST change, the projection threshold, pace
  filtering, sparse and empty data. It absorbs `UsageSummaryTest`'s cases.
- `DashboardLayoutTest`: unknown IDs dropped, new IDs appended, hidden set
  pruned, empty saved state yields the default order.
- `StatsRepositoryTest`: `record()` is a no-op when disabled, `deleteAll()`
  empties the table.
- `SettingsRepositoryTest`: defaults and round-trip of the new keys.

On device: seed with the debug sample data, then check each card in light and
dark, at 360 dp and the Pixel's width, with TalkBack reading a few charts.

## 10. Order of work

1. Aggregator, range, layout merge, and settings keys, with tests.
2. Recording gate and `deleteAll()`.
3. Chart components.
4. Cards and dashboard with Edit mode.
5. Settings section and Home wiring.
6. Debug seeder and visual pass.
