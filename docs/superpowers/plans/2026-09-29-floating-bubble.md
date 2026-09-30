# Floating Bubble Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A floating accessibility-overlay button that dictates into the focused field of any app: hold to talk, tap for until-silence, double-tap for continuous, drag to reposition, with size and position settings.

**Architecture:** Small pure classes hold all decisions (gesture state machine, positioning, visibility, text merge) and are unit-tested. `BubbleService` (an `AccessibilityService`) draws the existing `VoiceOrb` in a `TYPE_ACCESSIBILITY_OVERLAY` window, feeds pointer events to the gesture machine, drives the shared `DictationPipeline`, and inserts text through an `InsertionTarget` registered with the existing `DeliveryRouter`.

**Tech Stack:** Kotlin, Android `AccessibilityService`, Jetpack Compose, DataStore, JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-29-floating-bubble-design.md`

## Global Constraints

- **Branch order.** Run this plan after the stats plan is merged. First rebase `feat/floating-bubble` onto `feat/stats-dashboard` (both edit `UmmSettings`, `SettingsRepository`, `SettingsScreen`, and `MainActivity`), then resolve conflicts keeping both sets of fields. Commit after every task, Conventional Commits, subject ≤ 50 chars, body wrapped at 72. **Never add a `Co-Authored-By` trailer.**
- Change files with the native Edit/Write tools, never with sed, python, or heredocs.
- Never set `isAccessibilityTool`. No foreground service. No `SYSTEM_ALERT_WINDOW`. Overlay window type is `TYPE_ACCESSIBILITY_OVERLAY`.
- Service events: `typeViewFocused|typeViewTextSelectionChanged|typeWindowStateChanged`; `canRetrieveWindowContent="true"`; `accessibilityFlags="flagIncludeNotImportantViews"`.
- Constants: `HOLD_MS = 250`, `DOUBLE_TAP_MS = 300`, `DRAG_SLOP = 12 dp`, sizes 44 / 56 / 72 dp (default 56), minimum touch target 48 dp, idle alpha 0.6, dimmed alpha 0.4 after 3,000 ms idle, focus-lost hide delay 400 ms.
- Password fields: bubble hidden, no recording. The disclosure text is the verbatim block in spec §6.
- Core tests: `./gradlew :core:testDebugUnitTest --tests '<Class>'`. App tests: `./gradlew :app:testDebugUnitTest --tests '<Class>'`. Install with `./gradlew installDebug -PversionCode=10100` (needs `ANDROID_HOME=~/Android/Sdk`).
- Enabling an accessibility service is a system security setting: on device, ask the user to enable it; never change `enabled_accessibility_services` with adb. Turn it off again at the end.
- Reference only: `spike/a11y-mic` (local branch) has a working overlay + `TYPE_ACCESSIBILITY_OVERLAY` example. Do not merge it.

## Review Focus

1. Focus moves to another field or app while recording: the text goes to the clipboard, never into the wrong field (Task 7, and Task 11 on device).
2. Field text that is null, hint text, or has a reversed, out-of-range, or missing selection (Task 6).
3. Gesture boundaries: release at `HOLD_MS - 1` versus `HOLD_MS`, a held finger that wobbles, a press that turns into a drag (Task 3).
4. Password field or no editable focus: bubble hidden, and in *Always* mode a press does not record (Task 5).
5. Rotation or a size change must keep the bubble fully on screen (Task 4).

## File Structure

| File | Responsibility |
|---|---|
| `core/.../pipeline/DictationPipeline.kt` | `setContinuous(on)` |
| `core/.../data/SettingsRepository.kt` | Bubble settings and enums |
| `app/.../bubble/BubbleGesture.kt` | Pure gesture state machine |
| `app/.../bubble/BubblePosition.kt` | Pure sizing, placing, snapping |
| `app/.../bubble/BubbleVisibility.kt` | Pure show/hide and can-record rules |
| `app/.../bubble/NodeInsertion.kt` | Pure text merge |
| `app/.../bubble/AccessibilityTarget.kt` | `FocusedField` interface, `InsertionTarget` implementation, node adapter |
| `app/.../bubble/BubbleService.kt`, `BubbleView.kt` | Service, overlay window, touch wiring |
| `app/.../settings/BubblePage.kt` | Settings page, disclosure, steps, preview |
| `docs/play/accessibility-declaration.md` | Play form answers and demo-video shot list |

---

### Task 1: Pipeline `setContinuous(on)`

**Files:**
- Modify: `core/src/main/kotlin/io/github/agopalareddy/umm/core/pipeline/DictationPipeline.kt`
- Test: `core/src/test/kotlin/io/github/agopalareddy/umm/core/pipeline/DictationPipelineTest.kt`

**Interfaces:**
- Produces: `fun setContinuous(on: Boolean = true)` replacing `fun setContinuous()`. Existing callers keep working.

- [ ] **Step 1:** Add tests next to `switchingToContinuousIgnoresSilenceUntilStopped`: `turningContinuousBackOffStopsForSilence` (`audio.amplitudes = rep(90,5) + rep(8000,5) + rep(90,60)`, `holdOpen = true`, `p.start(request(continuous = true))`, wait for `Listening && speechDetected`, `p.setContinuous(false)`, expect `p.awaitEnd()` to be a `Done` without calling `stop()`) and `setContinuousDefaultsToOn` (`p.setContinuous()` after speech, `delay(1_000)`, still `Listening` with `continuous == true`).
- [ ] **Step 2:** Run `DictationPipelineTest`; expect FAIL (compile error on the parameter).
- [ ] **Step 3:** Change the function to assign `continuous = on`.
- [ ] **Step 4:** Run `DictationPipelineTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(pipeline): allow turning continuous mode off`.

### Task 2: Bubble settings

**Files:**
- Modify: `core/.../data/SettingsRepository.kt`
- Test: `core/src/test/.../data/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `enum class BubbleShowMode { WHEN_FOCUSED, ALWAYS }`, `enum class BubbleSize { SMALL, MEDIUM, LARGE }`, `enum class BubbleEdge { LEFT, RIGHT }`. `UmmSettings` gains `bubbleEnabled: Boolean = false`, `bubbleVisibility: BubbleShowMode = WHEN_FOCUSED`, `bubbleSize: BubbleSize = MEDIUM`, `bubbleEdge: BubbleEdge = RIGHT`, `bubbleYPortrait: Float = 0.6f`, `bubbleYLandscape: Float = 0.5f`, `disclosureAcceptedAt: Long = 0`. Keys: `bubble_enabled`, `bubble_visibility`, `bubble_size`, `bubble_edge`, `bubble_y_portrait`, `bubble_y_landscape`, `disclosure_accepted_at`.

- [ ] **Step 1:** Extend `defaultsMatchSpec` and `roundTripsEveryField` (non-default: enabled true, `ALWAYS`, `LARGE`, `LEFT`, `0.25f`, `0.8f`, `1_700_000_000_000L`). Add `unknownBubbleEnumsFallBackToDefaults` and `bubblePositionIsClampedToZeroOne` (write `1.7f` and `-0.2f` through `update`, read `1f` and `0f`).
- [ ] **Step 2:** Run `SettingsRepositoryTest`; expect FAIL.
- [ ] **Step 3:** Implement following the existing enum-with-fallback pattern; clamp the floats on write.
- [ ] **Step 4:** Run `SettingsRepositoryTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(bubble): add bubble settings`.

### Task 3: BubbleGesture

**Files:**
- Create: `app/src/main/kotlin/io/github/agopalareddy/umm/bubble/BubbleGesture.kt`
- Test: `app/src/test/kotlin/io/github/agopalareddy/umm/bubble/BubbleGestureTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class PipelineView { IDLE, RECORDING, BUSY, FAILED }
  sealed interface BubbleCommand {
      data object Start : BubbleCommand              // begin recording with silence detection off
      data object Stop : BubbleCommand
      data object Cancel : BubbleCommand
      data class SetSilenceDetection(val on: Boolean) : BubbleCommand
      data object Retry : BubbleCommand
      data object Reject : BubbleCommand             // pipeline busy: the bubble shakes
      data class DragBy(val dx: Float, val dy: Float) : BubbleCommand
      data object DragEnd : BubbleCommand
  }
  class BubbleGesture(private val slopPx: Float, private val holdMs: Long = 250, private val doubleTapMs: Long = 300) {
      fun onDown(nowMs: Long, x: Float, y: Float, pipeline: PipelineView): List<BubbleCommand>
      fun onMove(nowMs: Long, x: Float, y: Float): List<BubbleCommand>
      fun onUp(nowMs: Long): List<BubbleCommand>
  }
  ```
- Rules: `onDown` with `IDLE` → `[Start]`; `BUSY` → `[Reject]`; `FAILED` → `[Retry]`; `RECORDING` → no command, and if the previous release was a tap less than `doubleTapMs` ago it is the second press of a double-tap (`[SetSilenceDetection(false)]`, and its release emits nothing), otherwise its release emits `[Stop]`. A `Down` while `pipeline != RECORDING` first resets any tap window. A press that moves farther than `slopPx` from its down point before `holdMs` elapses emits `[Cancel, DragBy(...)]` once, then `DragBy` for each further move, then `[DragEnd]` on release; movement at or after `holdMs`, or during a stop or double-tap press, is ignored. Release of a fresh press: elapsed `>= holdMs` → `[Stop]`; else `[SetSilenceDetection(true)]` and open the tap window.

- [ ] **Step 1:** Tests (times in ms, `slopPx = 12f`): `holdThenReleaseAtHoldMsStops` (down at 0, up at 250 → `[Start]` then `[Stop]`), `releaseJustBeforeHoldMsIsATap` (up at 249 → `[SetSilenceDetection(true)]`), `doubleTapTurnsSilenceDetectionOff` (tap at 0–100, down at 350 with `RECORDING` → `[SetSilenceDetection(false)]`, its up → empty, later press with `RECORDING` stops on release), `secondPressAfterWindowStopsOnRelease` (down at 500 → empty, up → `[Stop]`), `dragBeforeHoldCancelsAndMoves` (down, move 20 px at 100 ms → `[Cancel, DragBy(20f, 0f)]`, further move `DragBy`, up → `[DragEnd]`), `wobbleAfterHoldIsIgnored` (move 30 px at 300 ms → empty; up → `[Stop]`), `smallMoveWithinSlopIsNotADrag`, `busyPressIsRejected`, `failedPressRetries`, `windowResetsWhenPipelineNoLongerRecording` (tap, then a down at 200 ms with `IDLE` → `[Start]`, not a double-tap), `upWithoutDownIsNoOp`.
- [ ] **Step 2:** Run `BubbleGestureTest`; expect FAIL.
- [ ] **Step 3:** Implement as a small explicit state enum (`IDLE, PRESSED, DRAGGING, TAP_WINDOW, PRESSED_DOUBLE, CONTINUOUS, PRESSED_STOP`) with no timers; every decision compares `nowMs`.
- [ ] **Step 4:** Run `BubbleGestureTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(bubble): add gesture state machine`.

### Task 4: BubblePosition

**Files:**
- Create: `app/.../bubble/BubblePosition.kt`
- Test: `app/src/test/.../bubble/BubblePositionTest.kt`

**Interfaces:**
- Consumes: `BubbleSize`, `BubbleEdge` (Task 2).
- Produces: `data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)`; `data class Snap(val edge: BubbleEdge, val yFraction: Float)`; `object BubblePosition { fun sizePx(size: BubbleSize, density: Float): Int; fun touchPx(size: BubbleSize, density: Float): Int; fun place(area: Area, boxPx: Int, edge: BubbleEdge, yFraction: Float): Pair<Int, Int>; fun snap(area: Area, boxPx: Int, centerX: Float, centerY: Float): Snap }`. `sizePx` is `44/56/72 * density` rounded; `touchPx = max(sizePx, round(48 * density))`. `place` returns the top-left `(x, y)`: left edge → `area.left`, right edge → `area.right - boxPx`, `y = area.top + yFraction.coerceIn(0f,1f) * (area.height - boxPx)`. `snap` picks the nearer horizontal edge to `centerX` (ties go right) and `yFraction = ((centerY - boxPx / 2f) - area.top) / (area.height - boxPx)` clamped to `0..1`.

- [ ] **Step 1:** Tests: `sizesAtDensityTwo` (88, 112, 144), `smallSizeStillHasA48DpTouchTarget` (density 2 → touch 96, size small 88), `placeRightEdgeAtHalfHeight`, `placeClampsFraction`, `snapChoosesNearerEdge`, `snapTieGoesRight`, `snapClampsYAtTopAndBottom`, `placeAfterRotationStaysInsideArea` (portrait 1080×2000 vs landscape 2000×1080 areas, same fraction, result within bounds), `changingSizeKeepsTheDockedEdge`.
- [ ] **Step 2:** Run `BubblePositionTest`; expect FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `BubblePositionTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(bubble): add bubble positioning math`.

### Task 5: BubbleVisibility

**Files:**
- Create: `app/.../bubble/BubbleVisibility.kt`
- Test: `app/src/test/.../bubble/BubbleVisibilityTest.kt`

**Interfaces:**
- Consumes: `BubbleShowMode` (Task 2).
- Produces: `object BubbleVisibility { const val HIDE_DELAY_MS = 400L; fun shouldShow(mode: BubbleShowMode, focusedEditable: Boolean, isPassword: Boolean, msSinceFocusLost: Long?): Boolean; fun canRecord(focusedEditable: Boolean, isPassword: Boolean): Boolean }`. Password always hides. `ALWAYS` shows otherwise. `WHEN_FOCUSED` shows while `focusedEditable`, and for 399 ms after focus was lost (`msSinceFocusLost` non-null), then hides. `canRecord = focusedEditable && !isPassword`.

- [ ] **Step 1:** Tests: `passwordHidesInBothModes`, `alwaysShowsWithoutFocus`, `whenFocusedShowsWhileFocused`, `whenFocusedKeepsShowingFor399Ms`, `whenFocusedHidesAt400Ms`, `neverFocusedHidesInWhenFocusedMode` (`msSinceFocusLost = null`), `canRecordNeedsAnEditableNonPasswordField`.
- [ ] **Step 2:** Run `BubbleVisibilityTest`; expect FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `BubbleVisibilityTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(bubble): add visibility rules`.

### Task 6: NodeInsertion

**Files:**
- Create: `app/.../bubble/NodeInsertion.kt`
- Test: `app/src/test/.../bubble/NodeInsertionTest.kt`

**Interfaces:**
- Consumes: `io.github.agopalareddy.umm.ime.TextInsertion.prepare`.
- Produces: `data class Merge(val text: String, val cursor: Int)`; `object NodeInsertion { fun merge(current: CharSequence?, showingHint: Boolean, selStart: Int, selEnd: Int, insert: String, multiLine: Boolean): Merge }`. Null text or hint text counts as empty. Selection is normalized with `min`/`max` and clamped to `0..length`; a negative start or end means "no selection info", so insert at the end. `text = before + prepared + after`, `cursor = start + prepared.length`.

- [ ] **Step 1:** Tests (`merge(current, showingHint, selStart, selEnd, insert, multiLine)` → expected): `(null,false,-1,-1,"hello",false)` → `Merge("hello",5)`; `("Message",true,0,0,"hi",false)` → `Merge("hi",2)`; `("hello world",false,5,5,"big",false)` → `Merge("hello big world",9)`; `("hello world",false,6,11,"there",false)` → `Merge("hello there",11)`; reversed `(11,6)` gives the same; `("abc",false,50,50,"x",false)` → `Merge("abc x",5)`; `("abc",false,-1,-1,"x",false)` → `Merge("abc x",5)`; `("",false,0,0,"a\nb",false)` → `Merge("a b",3)`; `("world",false,0,0,"hello",false)` → `Merge("hello world",6)`; multi-line keeps `"a\nb"`.
- [ ] **Step 2:** Run `NodeInsertionTest`; expect FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `NodeInsertionTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(bubble): add text merge for focused fields`.

### Task 7: AccessibilityTarget

**Files:**
- Create: `app/.../bubble/AccessibilityTarget.kt`
- Test: `app/src/test/.../bubble/AccessibilityTargetTest.kt`

**Interfaces:**
- Consumes: `InsertionTarget` (in `ime/DeliveryRouter.kt`), `NodeInsertion` (Task 6).
- Produces:
  ```kotlin
  interface FocusedField {
      val packageName: String
      fun refresh(): Boolean               // false if the node is gone or no longer focused and editable
      val text: CharSequence?
      val showingHint: Boolean
      val selectionStart: Int
      val selectionEnd: Int
      val multiLine: Boolean
      fun setText(text: String): Boolean
      fun setSelection(position: Int): Boolean
      fun paste(text: String): Boolean     // puts text on the clipboard and performs ACTION_PASTE
  }
  class AccessibilityTarget(override val origin: Long, private val field: FocusedField, private val inserted: () -> Unit) : InsertionTarget
  class NodeField(node: AccessibilityNodeInfo, clipboard: ClipboardManager) : FocusedField   // real adapter, device-verified
  ```
- `commit(text)`: `refresh()` false → return false without touching the field. Otherwise merge, `setText(merged.text)`; on success `setSelection(merged.cursor)` (its result is ignored) and return true; if `setText` fails, return `paste(text prepared for the field)`. `onInserted()` calls `inserted()`.

- [ ] **Step 1:** Tests with a `FakeField` recording calls: `commitSetsMergedTextAndCursor` (text "hello", selection 5,5, insert "there" → `setText("hello there")`, `setSelection(11)`, returns true), `staleFieldReturnsFalseAndTouchesNothing`, `setTextFailureFallsBackToPaste` (returns the paste result), `bothFailReturnsFalse`, `hintTextIsTreatedAsEmpty`.
- [ ] **Step 2:** Run `AccessibilityTargetTest`; expect FAIL.
- [ ] **Step 3:** Implement the interface, target, and `NodeField` (`ACTION_SET_TEXT` with `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE`, `ACTION_SET_SELECTION` with `ACTION_ARGUMENT_SELECTION_START_INT`/`END_INT`, `ACTION_PASTE`, `refresh()` plus `isFocused && isEditable`, `isShowingHintText`, `isMultiLine`).
- [ ] **Step 4:** Run `AccessibilityTargetTest`; expect PASS.
- [ ] **Step 5:** Commit `feat(bubble): insert dictated text into nodes`.

### Task 8: Orb ring override

**Files:**
- Modify: `app/.../ui/VoiceOrb.kt`

- [ ] **Step 1:** Add `continuousRing: Boolean? = null` to `VoiceOrb`; `val continuous = continuousRing ?: (listening?.continuous == true)`. The content description "Finish" follows the same value.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`; expect success (the keyboard's call site is unchanged).
- [ ] **Step 3:** Commit `refactor(ui): let callers control the orb ring`.

### Task 9: BubbleService with overlay and visibility

**Files:**
- Create: `app/.../bubble/BubbleService.kt`, `app/.../bubble/BubbleView.kt`, `app/src/main/res/xml/bubble_service.xml`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml` (create if absent: `bubble_description`)

**Interfaces:**
- Produces: `class BubbleService : AccessibilityService` with `companion object { val connected: StateFlow<Boolean> }`; manifest entry with `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, `android:exported="true"`, the `android.accessibilityservice.AccessibilityService` intent filter, and the `bubble_service` meta-data. `BubbleView` hosts `VoiceOrb` in a `ComposeView` with lifecycle and saved-state owners (as `UmmInputMethodService` does).
- Behaviour in this task: read `graph.settings.settings`; add the overlay only when `bubbleEnabled`; size from `BubblePosition.sizePx/touchPx`, placement from `place(...)` in the usable area (screen minus system bars and cutout); show/hide by `BubbleVisibility.shouldShow` from focus events (`findFocus(FOCUS_INPUT)`, `isEditable`, `isPassword`) with a 400 ms hide delay; alpha 0.6 idle, 0.4 after 3 s; re-place on configuration change; the orb shows `graph.pipeline.state`. No touch handling yet. Remove the overlay in `onUnbind`/`onDestroy` and set `connected` accordingly.

- [ ] **Step 1:** Implement the config XML per Global Constraints (`android:settingsActivity` is `.settings.MainActivity`, since the Bubble page is a Compose route inside it), manifest entry, service, and view.
- [ ] **Step 2:** Run `installDebug`; **ask the user to turn the service on** (Settings → Accessibility → Umm) and the bubble switch is not built yet, so temporarily have the service treat `bubbleEnabled` as true only in a local uncommitted edit. Expected: the bubble appears over the Settings app search field, disappears on a non-editable screen after about 0.4 s, is hidden on a password field, and stays in place after rotation. Revert the temporary edit.
- [ ] **Step 3:** Commit `feat(bubble): add accessibility overlay service`.

### Task 10: Touch, dictation, and insertion

**Files:**
- Modify: `app/.../bubble/BubbleService.kt`, `app/.../bubble/BubbleView.kt`

**Interfaces:**
- Consumes: `BubbleGesture`, `BubbleCommand`, `PipelineView` (Task 3); `BubblePosition.snap/place` (Task 4); `BubbleVisibility.canRecord` (Task 5); `AccessibilityTarget`, `NodeField` (Task 7); `VoiceOrb(continuousRing = …)` (Task 8); `LevelResolver`, `graph.categories.configFor`, `graph.delivery` (`newOrigin`, `attach`, `detach`), `graph.pipeline`.
- Behaviour: map pipeline state to `PipelineView` (`Listening` → `RECORDING`; `Transcribing`/`Cleaning` → `BUSY`; `Failed` → `FAILED`; else `IDLE`). On `Start`: require `BubbleVisibility.canRecord` for the focused node, else shake and toast "Tap a text field first" and do nothing; otherwise read the focused node's package, resolve level and script from its category and the global default, create `NodeField`, register `AccessibilityTarget` under `graph.delivery.newOrigin()`, and call `pipeline.start(DictationRequest(pkg, level, script, LanguageChoice.decode(settings.defaultLanguage), settings.silenceTimeoutSec, origin, continuous = true))`. `SetSilenceDetection(on)` → `pipeline.setContinuous(!on)`. `Stop`/`Cancel` → `pipeline.stop()`/`pipeline.cancel()`. `Retry` → `pipeline.reset(); pipeline.retry(historyId, newOrigin)` with a fresh target. `Reject` → a short horizontal shake. `DragBy` moves the window; `DragEnd` snaps with `BubblePosition.snap` and saves `bubbleEdge` and the orientation's Y fraction. Haptics: `CONTEXT_CLICK` on start, `CONFIRM` on stop. Tapping while the key or microphone permission is missing opens `MainActivity`. Detach the target when its result is delivered or the dictation ends. Pass `continuousRing = gestureIsDoubleTap` so a held recording shows no ring.

- [ ] **Step 1:** Implement the touch handler in `BubbleView` (feeds `BubbleGesture` with raw pointer events and dp-converted slop) and the command handling in the service.
- [ ] **Step 2:** Run `installDebug`. On device (Umm's Try-it field and the system Settings search field only; do not use personal apps): hold and speak → text inserted on release; tap → stops on silence; double-tap → keeps recording until the next tap; drag → moves and snaps to the nearest edge and stays after killing and reopening Umm; move focus to a different field mid-recording → clipboard toast, not insertion; password field → no bubble.
- [ ] **Step 3:** Commit `feat(bubble): dictate from the floating button`.

### Task 11: Settings → Bubble page

**Files:**
- Create: `app/.../settings/BubblePage.kt`
- Modify: `app/.../settings/SettingsScreen.kt` (row `NavRow(Icons.Default.…, "Floating button", …)`), `app/.../settings/MainActivity.kt` (`Routes.BUBBLE = "settings/bubble"`), `app/src/main/AndroidManifest.xml` (`android.permission.QUERY_ADVANCED_PROTECTION_MODE`)

**Interfaces:**
- Produces: `BubblePage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange)`. Contents, top to bottom: the *Floating button* `SwitchRow`; when turning it on and `disclosureAcceptedAt == 0`, an `AlertDialog` with the spec §6 disclosure text and *Accept* / *Decline* (Accept stores `System.currentTimeMillis()` and `bubbleEnabled = true`, then starts `Settings.ACTION_ACCESSIBILITY_SETTINGS`; Decline leaves it off); a status line from `BubbleService.connected` ("Active" / "Turn Umm on in Accessibility settings"); collapsible help for "Can't turn it on?" with the steps Settings → Apps → Umm → ⋮ → Allow restricted settings; on `SDK_INT >= 36`, if `AdvancedProtectionManager.isAdvancedProtectionEnabled()`, a note that Android blocks the feature and the keyboard still works, with the switch disabled; *Visibility* radio rows; *Size* radio rows; *Edge* radio rows; a live preview box (a phone-shaped area with the orb at the chosen size and edge, draggable, updating `bubbleEdge` and `bubbleYPortrait`); *Reset position* (defaults `RIGHT`, 0.6, 0.5).

- [x] **Step 1:** Implement the page, route, row, and permission.
- [x] **Step 2:** Run `./gradlew :app:testDebugUnitTest installDebug`. On device from a state with the service off: switch on → disclosure appears; Decline keeps it off; Accept opens Accessibility settings; after the user enables the service the status reads "Active" and the bubble appears; size, edge, and visibility changes apply live; the preview drag and *Reset position* work.
- [x] **Step 3:** Commit `feat(bubble): add floating button settings page`.

### Task 12: Store and privacy documents

**Files:**
- Create: `docs/play/accessibility-declaration.md`
- Modify: `docs/play/listing.md`, `docs/privacy/index.html`, `README.md`

- [x] **Step 1:** Write the declaration: the API's core purpose (insert dictated text into the focused field, pick the cleanup level from the foreground app), why a keyboard alone is not enough, the data it touches (the focused field's text and cursor, the foreground app's package name, never passwords, nothing stored or sent except speech to OpenRouter), that `isAccessibilityTool` is not claimed, and a demo-video shot list (disclosure, consent, enabling with the restricted-setting step, hold, tap, and double-tap dictation, disabling).
- [x] **Step 2:** Update the Play listing description and Data safety rows and the privacy policy to mention the floating button and what the permission reads; add a short README section.
- [x] **Step 3:** Commit `docs: describe the floating button and permission`.

### Task 13: Final verification

- [x] **Step 1:** Run `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest assembleRelease`; expect all green. Confirm the manifest merges with the service and the new permission, and note the release APK size.
- [ ] **Step 2:** On device, full pass with the user enabling the service: each size, both edges, both orientations, all three visibility cases (field focused, no field, password), hold/tap/double-tap, drag from an in-progress press, focus change mid-recording, Advanced Protection note if available, and turning the switch off removes the bubble.
- [ ] **Step 3:** Ask the user to turn the accessibility service off, confirm the phone's `enabled_accessibility_services` is empty again, and reinstall the normal debug build.
- [ ] **Step 4:** Delete the local `spike/a11y-mic` branch after confirming with the user.
