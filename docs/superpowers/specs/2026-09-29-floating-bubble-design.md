# Floating bubble

Status: draft for review, 2026-09-29.

## 1. Goal

A floating button, like Wispr Flow's, that dictates into whatever text field is
focused in any app. It is a second front-end to the same `DictationPipeline`
the keyboard uses, so models, cleanup levels, categories, history, and stats
all work the same. The keyboard stays.

### Gestures

| Gesture | Result |
|---|---|
| Press and hold | Records while held. Releasing stops, processes, and inserts. |
| Tap | Records until silence, using the silence timeout setting. |
| Double-tap | Records through silence until the next tap. |
| Drag | Moves the bubble; on release it snaps to the nearest screen edge. |

### Non-goals (v1)

- A per-dictation cleanup-level override or app-category assignment from the
  bubble. Levels come from the app's category; assign apps in Settings or on the
  keyboard chip.
- A cancel gesture, and hiding the bubble while Umm's own keyboard is showing.
- Snapping to the top or bottom edge, or special tablet and foldable layouts.
- Any foreground service (see §3).

## 2. Decisions

| Topic | Decision |
|---|---|
| Mechanism | An `AccessibilityService`. It draws the bubble in a `TYPE_ACCESSIBILITY_OVERLAY` window (no "draw over other apps" permission) and inserts text into the focused field. |
| Visibility | Setting `Bubble visibility`: *When a text field is focused* (default) or *Always*. |
| Password fields | Bubble hidden and no recording, matching the keyboard. |
| Size | Small 44 dp, Medium 56 dp (default), Large 72 dp. The touch target is never below 48 dp. |
| Position | Docks to the left or right edge; the vertical position is a fraction of the usable screen height, remembered separately for portrait and landscape. Set by dragging the real bubble, or with a live preview in Settings. |
| Look | The existing `VoiceOrb`, at about 60% opacity when idle, full opacity while active. |
| Feedback | Light haptic on start, a confirm haptic on stop. No sounds. |
| Microphone | Recorded directly from the service, no foreground service (spike result, §3). |
| Off by default | The bubble is off until the user turns it on and grants the accessibility permission. |

## 3. Platform and store constraints

Findings from the 2026-09-29 research and spike:

- **Play policy.** Umm is a general dictation tool, so it must **not** set
  `isAccessibilityTool`. It uses the API as a non-accessibility app, which needs
  the Permission Declaration Form, a prominent in-app disclosure, explicit
  consent before the permission is requested, and a demo video of that flow.
- **Android 17 Advanced Protection.** With Advanced Protection on, Android
  blocks non-accessibility-tool services entirely. The bubble page detects this
  where the OS allows it and says the keyboard still works.
- **Restricted settings.** APKs installed outside Play show "Restricted
  setting" until the user allows it in App info. The setup page explains the steps.
- **Microphone.** On a Pixel 9 Pro on Android 17, recording started from a
  button in an accessibility overlay worked with another app in front, across app
  switches, for 43 s, with real (not silenced) audio, and without a foreground
  service. Other manufacturers are untested; the recorder start sits behind the
  existing `AudioSource` interface so a microphone foreground service can be
  added later without restructuring.
- **Fallback if Play rejects the declaration.** Keep all bubble code in the
  `bubble/` package and its manifest entry so it can be removed from a Play
  build (a product flavor) while GitHub releases keep it.

## 4. Behaviour

### Gesture state machine (pure, unit-tested)

Constants: `HOLD_MS = 250`, `DOUBLE_TAP_MS = 300`, `DRAG_SLOP = 12 dp`.

1. **Down** while idle: start recording immediately (silence detection off),
   so the first syllable is never lost.
2. **Move past the slop before `HOLD_MS`**: it is a drag. Cancel the recording,
   move the bubble, snap on release. Movement after `HOLD_MS` is ignored, so a
   held finger can wobble.
3. **Up at or after `HOLD_MS`**: push-to-talk release. Stop and process.
4. **Up before `HOLD_MS`**: a tap. Turn silence detection on and open the
   `DOUBLE_TAP_MS` window.
5. **Second down inside the window**: a double-tap. Turn silence detection off
   again; recording continues until the next press ends it.
6. **Any other press while recording in tap or continuous mode** (that is, one
   that is not the second press of a double-tap): stops it on release.
7. **Down while transcribing or cleaning**: ignored.
8. **Down after a failure**: retries, exactly as the keyboard's Retry does.

A drag can flash the system microphone indicator for under 250 ms. That is
accepted and documented.

### Pipeline change

`DictationPipeline.setContinuous()` becomes `setContinuous(on: Boolean = true)`.
The recording loop already re-reads the flag on every sample, so turning silence
detection back on mid-recording needs no other change. `VoiceOrb` gains an
optional `continuousRing: Boolean?` override so the bubble shows the ring only
for a double-tap recording, not for a held one.

### When the bubble shows

- *When focused* mode: shown while the focused input node is editable and not a
  password field. Hidden 400 ms after focus leaves, so switching between fields
  does not flicker.
- *Always* mode: shown whenever the service is running, except over password
  fields.
- It dims to 40% after 3 s of no interaction and returns to 60% on touch.

### Inserting text

At record start the service reads the focused node and its package name, picks
the level from that package's category, and registers an `InsertionTarget` with
the existing `DeliveryRouter` under a fresh origin. On delivery it:

1. Refreshes the node. If the node is gone, or a different window now has focus,
   `commit` returns false and the router copies to the clipboard as today
   ("Copied — field changed").
2. Treats hint text (`isShowingHintText`) as empty text.
3. Merges into the current text at the selection using `TextInsertion.prepare`
   for spacing and single-line handling.
4. Sets the merged text with `ACTION_SET_TEXT`, then moves the cursor to the end
   of the inserted text with `ACTION_SET_SELECTION`.
5. If `ACTION_SET_TEXT` fails, tries clipboard plus `ACTION_PASTE`; if that also
   fails, `commit` returns false and the clipboard toast applies.

### Enabling flow (Settings → Bubble)

1. A page with the *Bubble* switch, visibility, size, position, and a preview.
2. Turning the switch on shows the **prominent disclosure** (§6). *Decline*
   leaves it off.
3. *Accept* opens the system Accessibility settings with on-screen steps for
   finding Umm and, for sideloaded installs, for "Allow restricted settings".
4. When the user returns and the service is connected, the bubble appears. If it
   is not connected, the page keeps showing the steps.
5. With Advanced Protection on (Android 17), the page shows a note instead of the
   steps and the switch stays off.

### Errors

In *Always* mode, pressing while no editable, non-password field is focused
does not record: the bubble shakes and a toast says "Tap a text field first".

Missing microphone permission or key: tapping opens Umm's setup screen, like the
keyboard's "Finish setup". Network and credit failures show the orb's retry icon;
the failure reasons match the keyboard's.

## 5. Architecture

### `:core`

- `DictationPipeline.setContinuous(on)` as above, with tests in
  `DictationPipelineTest` for turning silence detection back on and stopping for
  silence afterward.
- `UmmSettings` gains: `bubbleEnabled` (false), `bubbleVisibility`
  (`WHEN_FOCUSED`), `bubbleSize` (`MEDIUM`), `bubbleEdge` (`RIGHT`),
  `bubbleYPortrait` (0.6), `bubbleYLandscape` (0.5), and `disclosureAcceptedAt`
  (0, meaning never). Unknown enum values fall back to defaults.

### `:app`, package `bubble`

| Unit | Responsibility |
|---|---|
| `BubbleService` | The `AccessibilityService`. Owns the overlay window, watches focus events, and wires the gesture machine to the pipeline. |
| `BubbleGesture` | The pure state machine of §4. Input: pointer events and a clock. Output: commands (`Start`, `Stop`, `Cancel`, `SetSilence(on)`, `Drag`, `Snap`). |
| `BubblePosition` | Pure math: clamp to the usable area, snap to the nearest edge, convert to and from the saved fraction, per orientation. |
| `BubbleVisibility` | Pure `decide(mode, focusedEditable, isPassword, sinceFocusLostMs)`. |
| `NodeInsertion` | Pure `merge(text, hint, selStart, selEnd, insert, multiLine)` returning the new text and cursor. |
| `AccessibilityTarget` | The `InsertionTarget` that applies a merge to a node and does the paste fallback. |
| `BubbleView` | Compose `VoiceOrb` in a `ComposeView` with the lifecycle and saved-state owners the overlay needs, plus the touch handler. |
| `settings/BubblePage` | The Settings page, disclosure dialog, steps, and the live preview. |
| `res/xml/bubble_service.xml` | Service config: `typeViewFocused`, `typeViewTextSelectionChanged`, `typeWindowStateChanged`; `canRetrieveWindowContent`; `flagIncludeNotImportantViews`; `settingsActivity` pointing at the Bubble page; **no** `isAccessibilityTool`. |

Nothing outside `bubble/` and `BubblePage` knows about the service. The
`DeliveryRouter` and pipeline are shared with the keyboard; a bubble start is
ignored, with a short shake of the bubble, if the pipeline is busy.

## 6. Disclosure and store text

The in-app disclosure, shown before the permission is requested, says:

> **Umm needs the Accessibility permission for the floating button.**
> It is used to see which text field you have selected, so it can put your
> dictated text there, and which app it is in, to pick the cleanup level. Umm
> reads only the selected field's current text and cursor position, never
> password fields, and never reads or stores anything else on your screen. Your
> speech goes to OpenRouter as usual; nothing else leaves your phone.

Deliverables in `docs/play/`:

- `accessibility-declaration.md`: the answers for the Permission Declaration
  Form and a shot list for the demo video (disclosure, consent, enabling,
  dictating with hold, tap, and double-tap).
- Updates to `listing.md` (Data safety and description) and to
  `docs/privacy/index.html`.

## 7. Testing

Unit tests:

- `BubbleGestureTest`: tap, hold, double-tap, drag-cancels-recording, wobble
  after `HOLD_MS` ignored, second press stops continuous recording, press while
  busy ignored, press after failure retries, exact timing at `HOLD_MS - 1` and
  `HOLD_MS`.
- `BubblePositionTest`: snap left and right, clamp to insets, portrait and
  landscape fractions kept separately, size changes keep the docked edge.
- `BubbleVisibilityTest`: the debounce, password fields, both modes.
- `NodeInsertionTest`: empty text, hint text, cursor at start, middle, end,
  a selected range replaced, a reversed selection, null selection, single-line
  fields flattened, spacing around words.
- `DictationPipelineTest`: silence detection re-enabled mid-recording.
- Settings round-trip and defaults.

On device (Pixel 9 Pro): Umm's Try-it field, the system Settings search field, a
password field (bubble hidden), switching fields mid-recording (clipboard
fallback), rotation, each size, and dragging to both edges. Then the disclosure
and enabling flow from a fresh install, including the restricted-setting steps.

## 8. Risks

- **`ACTION_SET_TEXT` support varies.** Some apps and web views ignore it. The
  paste and clipboard fallbacks keep text from being lost; the list of apps that
  need them is recorded as testing finds them.
- **Play review.** The declaration may be refused; see the fallback in §3.
- **Other manufacturers.** Background microphone rules may be stricter; see §3.
- **Battery and focus events.** The service listens to only three event types
  and does no work on other events.

## 9. Order of work

1. Pipeline `setContinuous(on)` and settings keys.
2. Pure logic: `BubbleGesture`, `BubblePosition`, `BubbleVisibility`,
   `NodeInsertion`, each with tests.
3. `BubbleService`, overlay view, and the gesture wiring, with insertion.
4. Settings page, disclosure, steps, and preview.
5. Store documents and privacy policy.
6. Device pass.
