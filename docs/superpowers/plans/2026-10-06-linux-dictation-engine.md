# Linux Dictation Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Global-hotkey dictation into any app on KDE Plasma and GNOME (Wayland), with an orb, tray icon, keyring storage and browser sign-in, running the shared Umm pipeline.

**Architecture:** A new Kotlin/JVM `linux` module holds the D-Bus adapters (portals, Secret Service, StatusNotifierItem, notifications) and the interfaces they implement. The `desktop` app holds the pure dictation logic (`HotkeyGesture`, `InsertionPlanner`, `DictationController`), tested with fakes, plus the background lifecycle, orb window, setup and settings.

**Tech Stack:** Kotlin 2.4.20/JVM 17, dbus-java 5.2.2 with the junixsocket transport, javax.sound, Compose Multiplatform 1.12.1 desktop, the shared `core` and `ui` modules, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-06-linux-dictation-engine-design.md`

## Global Constraints

- App id `io.github.agopalareddy.Umm` everywhere (desktop file, D-Bus name, portal registration, keyring attribute `application`).
- Default trigger `SUPER+ALT+space`, shortcut id `dictate`. Hold threshold 500 ms.
- One RemoteDesktop session per dictation (open, insert, close). Never inject while the hotkey is held.
- The API key and the RemoteDesktop restore token are only ever stored in the keyring (or memory). Never a plain file.
- `core` and `ui` stay unchanged except where a task says so. Android behavior is untouched.
- Desktop-only settings live in `~/.config/umm/desktop.preferences_pb`.
- Versions go in `gradle/libs.versions.toml`. Builds use `-Pkotlin.compiler.execution.strategy=in-process` (the machine ran out of memory with separate Kotlin daemons).
- Commits: Conventional Commits, subject ≤ 50 chars, no `Co-Authored-By` or other AI trailer. Never push or merge without the user's OK.
- Reference implementation for every portal call: `.superpowers/linux-spike/Spike.kt` (untracked) and its `findings.md`.

## Review Focus

1. **Hotkey pressed while Umm is processing or inserting** must do nothing (the spike's re-trigger bug). Pinned by `DictationControllerTest.hotkeyIgnoredWhileBusy`.
2. **Text with newlines, tabs, emoji and Devanagari on GNOME** must arrive intact: newlines and tabs typed as Return/Tab, everything non-ASCII pasted. Pinned by `InsertionPlannerTest`.
3. **No keyring running** must keep the key in memory and say so, never write it to disk. Pinned by `DesktopGraphKeyTest.noKeyring_keepsKeyInMemory`.
4. **A second launch, or a `umm://` link while Umm runs**, must reach the running instance, not start a second engine. Pinned by `SingleInstanceTest` and the Task 11 matrix.
5. **The typing permission refused or revoked** must fall back to the clipboard with a message. Pinned by `DictationControllerTest.insertFailure_copiesAndReports`.

---

### Task 1: `linux` module, interfaces and microphone

**Files:**
- Modify: `gradle/libs.versions.toml` (`dbusJava = "5.2.2"`; libraries `dbus-java-core = com.github.hypfvieh:dbus-java-core`, `dbus-java-transport-junixsocket = com.github.hypfvieh:dbus-java-transport-junixsocket`), `settings.gradle.kts` (`include(":linux")`), `desktop/build.gradle.kts` (depends on `project(":linux")`), `.github/workflows/ci.yml` (add `:linux:test`)
- Create: `linux/build.gradle.kts` (`kotlin-jvm`, `jvmToolchain(17)`, depends on `project(":core")` and both dbus-java libraries; test deps `junit`, `kotlinx-coroutines-test`)
- Create in `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/`: `Interfaces.kt`, `WavWriter.kt`, `JavaSoundMicrophone.kt`
- Test: `linux/src/test/kotlin/io/github/agopalareddy/umm/linux/WavWriterTest.kt`

**Interfaces (Produces):**
```kotlin
sealed interface HotkeyEvent { data object Down : HotkeyEvent; data object Up : HotkeyEvent }
interface HotkeySource { val events: Flow<HotkeyEvent>; suspend fun bind(): BindResult; suspend fun configure(): Boolean }  // configure opens the desktop's shortcut dialog; false when unsupported
sealed interface BindResult { data class Bound(val trigger: String) : BindResult; data object Unsupported : BindResult; data class Failed(val reason: String) : BindResult }
enum class TypingCapability { ALL, ASCII }
sealed interface InsertPart { data class Type(val text: String) : InsertPart; data class Paste(val text: String) : InsertPart }
interface TextInserter { val capability: TypingCapability; suspend fun insert(parts: List<InsertPart>) }   // throws InsertException
class InsertException(message: String, cause: Throwable? = null) : Exception(message, cause)
interface FocusTracker { fun focusedAppId(): String? }
interface TrayIcon { fun show(state: TrayState, onAction: (TrayAction) -> Unit); fun update(state: TrayState); fun hide() }
enum class TrayState { IDLE, RECORDING, BUSY }
enum class TrayAction { OPEN, START, STOP, CANCEL, QUIT }
interface Notifier { fun notify(title: String, body: String, openAction: Boolean = false, onOpen: () -> Unit = {}) }
interface Clipboard { fun setText(text: String) }   // the non-portal fallback (AWT)
```
`WavWriter` exposes `fun header(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray` and `fun peak(pcm16le: ByteArray, length: Int): Int`. `JavaSoundMicrophone(mixerName: String? = null) : AudioSource` with `format = "wav"` and `fun available(): Boolean` (a matching capture line exists and can be opened).

- [ ] **Step 1: Write the failing test** — `WavWriterTest`: `header(16000, 1, 32000)` is 44 bytes with `RIFF`, size 36+32000, `WAVE`, `fmt ` PCM 1 channel 16000 Hz byte rate 32000 block align 2 bits 16, `data` 32000 (assert each little-endian field); `peak` of samples `[0, 1000, -2000, 500]` is 2000; `peak` of `[-32768]` is 32767.
- [ ] **Step 2: Run** `./gradlew :linux:test` → FAIL (unresolved).
- [ ] **Step 3: Implement.** `JavaSoundMicrophone.record(file)`: open a `TargetDataLine` (16 kHz, 16-bit, mono, signed, little-endian; the named mixer when set), write a placeholder header, stream 100 ms chunks to the file emitting `peak` per chunk until `stop()`, then rewrite the header with the real size. A `LineUnavailableException` is thrown out of the flow as `IllegalStateException("Microphone unavailable")`.
- [ ] **Step 4: Run** `./gradlew :linux:test` → PASS.
- [ ] **Step 5: Commit** `feat(linux): add engine interfaces and microphone`.

### Task 2: `HotkeyGesture`

**Files:** Create `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/engine/HotkeyGesture.kt`; test `desktop/src/test/kotlin/io/github/agopalareddy/umm/desktop/engine/HotkeyGestureTest.kt`

**Interfaces:** `class HotkeyGesture(holdMs: Long = 500)` with `fun onEvent(event: HotkeyEvent, nowMs: Long, recording: Boolean): GestureCommand` where `enum class GestureCommand { START, STOP, NONE }`. Rules: `Down` while not held: `START` if not recording, else mark "stop on this press" and return `STOP`. Repeated `Down` while held: `NONE`. `Up`: if the press started recording and was held ≥ `holdMs`, `STOP` (hold-to-talk); otherwise `NONE` (toggle keeps recording). Stray `Up` with no press: `NONE`.

- [ ] **Step 1: Failing tests:** `tap_startsAndKeepsRecording` (Down@0 START, Up@120 NONE); `secondTap_stops` (then Down@3000 recording=true → STOP, Up NONE); `hold_stopsOnRelease` (Down@0 START, Up@501 STOP); `holdBoundary` (Up@499 NONE, Up@500 STOP); `gnomeRepeats_ignored` (Down@0 START, Down@30, Down@60 NONE, Up@700 STOP); `strayUp_ignored`.
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*HotkeyGestureTest*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(desktop): add tap and hold hotkey gesture`.

### Task 3: `InsertionPlanner`

**Files:** Create `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/engine/InsertionPlanner.kt`; test `.../engine/InsertionPlannerTest.kt`

**Interfaces:** `object InsertionPlanner { fun plan(text: String, capability: TypingCapability): List<InsertPart> }`. `ALL` → one `Type(text)`. `ASCII` → consecutive runs: printable ASCII plus `\n` and `\t` → `Type`; everything else (any code point > 0x7E or other control chars, including emoji surrogate pairs kept whole) → `Paste`. Empty text → empty list.

Ruling carried from planning: KDE has `ALL`, so it never pastes; the spec's KDE clipboard restore and terminal paste chord are not built.

- [ ] **Step 1: Failing tests:** `all_typesEverything` ("héllo 👍" → [Type]); `ascii_splitsRuns` ("Sounds good 👍 thanks" → [Type("Sounds good "), Paste("👍"), Type(" thanks")]); `ascii_keepsNewlinesAndTabsTyped` ("- a\n- b\tc" → [Type(...)]); `ascii_devanagariPasted` ("कल meeting है" → [Paste("कल"), Type(" meeting "), Paste("है")]); `surrogatePairNotSplit` ("👍🏽" → one Paste of the whole string); `empty_noParts`.
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*InsertionPlannerTest*'` → FAIL.
- [ ] **Step 3: Implement** by walking code points.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(desktop): plan typed and pasted text parts`.

### Task 4: `DictationController`

**Files:** Create `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/engine/{DesktopState.kt, DictationController.kt}`; test `.../engine/DictationControllerTest.kt` (fakes for `HotkeySource`, `TextInserter`, `Clipboard`, `Notifier`, `FocusTracker`; real `DictationPipeline` over `inMemoryUmmDatabase()` with the fake OpenRouter API and a fake `AudioSource`, as in `DictateCommandTest`)

**Interfaces:**
```kotlin
sealed interface DesktopState {
    data object Idle : DesktopState; data object NeedsKey : DesktopState
    data class Listening(val level: Int) : DesktopState; data object Processing : DesktopState
    data class Inserted(val pastedOnAsciiDesktop: Boolean) : DesktopState
    data class Copied(val reason: String) : DesktopState; data class Failed(val reason: String) : DesktopState
    data object NoSpeech : DesktopState
}
class DictationController(
    scope: CoroutineScope, hotkey: HotkeySource, pipeline: DictationPipeline, inserter: TextInserter,
    clipboard: Clipboard, notifier: Notifier, focus: FocusTracker, hasKey: () -> Boolean, micAvailable: () -> Boolean,
    request: suspend (packageName: String) -> DictationRequest, clock: () -> Long = System::currentTimeMillis,
) { val state: StateFlow<DesktopState>; fun start(); fun stop(); fun cancel() }
```
`start/stop/cancel` are what the orb and tray call. Behavior per spec §4: on START with no key → `NeedsKey` + notification with open action, no recording; with `micAvailable()` false → `Failed("Microphone unavailable")` + notification, no recording (otherwise the pipeline would report a failed mic as no speech). Insert only after the hotkey is up (track the last `Up`; in toggle mode it already is). While `Processing` or inserting, hotkey events return `NONE`. Insert failure → `clipboard.setText`, `Copied(reason)`, notification. `DictationState.Done(cleanupFailed = true)` → insert raw and notify "Cleanup failed; inserted the raw transcript". `Failed` → `Failed(reason text)` + notification using the Android wording for each `FailureReason`. `NoSpeech`/`EmptyTranscript` → `NoSpeech`, no notification. `Inserted(pastedOnAsciiDesktop = parts.any { it is Paste })`.

- [ ] **Step 1: Failing tests:** `tapDictation_insertsCleanText`; `hotkeyIgnoredWhileBusy` (Down events during Processing do not start a second recording or insert twice); `waitsForHotkeyRelease` (hold: no insert call until Up arrives); `noKey_promptsAndDoesNotRecord`; `noMicrophone_reportsUnavailable`; `insertFailure_copiesAndReports`; `cleanupFailed_insertsRawAndNotifies`; `orbStopAndCancel` (stop() processes, cancel() discards with no insert); `packageName_usesFocusedAppOrDesktop`; `pasteOnAsciiDesktop_flagged`.
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*DictationControllerTest*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(desktop): add the hotkey dictation controller`.

### Task 5: Portal adapters (hotkey and typing)

**Files:** Create in `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/`: `portal/PortalInterfaces.kt` (the D-Bus interfaces from `Spike.kt`: `Registry`, `GlobalShortcuts`, `RemoteDesktop`, `Clipboard`, `Session`, `Shortcut` struct, plus `Request.Response` handling), `portal/Portal.kt` (connection, `request()` helper, `register(appId)`), `GlobalShortcutsHotkey.kt`, `PortalTextInserter.kt`, `Keysyms.kt`; test `linux/src/test/.../KeysymsTest.kt`

**Interfaces:** `GlobalShortcutsHotkey(portal: Portal) : HotkeySource` (bind requests `preferred_trigger = "SUPER+ALT+space"`, description "Dictate with Umm"; `Unsupported` when the interface is missing; `Bound(trigger)` reads `trigger_description` from the response; `configure()` calls `ConfigureShortcuts` on portal version ≥ 2, else returns false). `PortalTextInserter(portal: Portal, tokenStore: KeyValueStore, capability: TypingCapability) : TextInserter` — per call: CreateSession, SelectDevices(types = keyboard, persist_mode = 2, restore_token if stored), RequestClipboard, Start; save the returned restore token to `tokenStore` key `remote_desktop_token`; type `Type` parts with `NotifyKeyboardKeysym` (press/release per char, `\n` → Return 0xff0d, `\t` → Tab 0xff09); for `Paste` parts SetSelection(text/plain;charset=utf-8), answer `SelectionTransfer` by writing UTF-8 to the fd from `SelectionWrite` and closing that fd, then send Ctrl+V (Control_L 0xffe3 + v); finally `Session.Close`. Any D-Bus error or a non-zero Start response → `InsertException`. `fun capabilityFor(env: Map<String,String>) = if (env["XDG_CURRENT_DESKTOP"].orEmpty().contains("KDE", true)) ALL else ASCII`. `Keysyms.forChar(c: Int): Int` (Latin-1 → same value, else `0x01000000 + codePoint`).

- [ ] **Step 1: Failing test** `KeysymsTest`: 'a' → 0x61, 'é' → 0xe9, '\n' → 0xff0d, '\t' → 0xff09, 'क' → 0x01000915; `capabilityFor(mapOf("XDG_CURRENT_DESKTOP" to "KDE"))` is ALL, `"GNOME"` and empty are ASCII.
- [ ] **Step 2: Run** `./gradlew :linux:test --tests '*KeysymsTest*'` → FAIL.
- [ ] **Step 3: Implement** by porting the spike, minus its debug logging.
- [ ] **Step 4: Run** → PASS; then a manual smoke on the KDE host: a temporary `main` in `linux/src/test` (deleted before commit) binds the shortcut and types "héllo 👍" into Kate on each press.
- [ ] **Step 5: Commit** `feat(linux): bind the hotkey and type through portals`.

### Task 6: Keyring, notifications, focus and autostart

**Files:** Create `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/{SecretServiceStore.kt, DbusNotifier.kt, KWinFocusTracker.kt, Autostart.kt, AwtClipboard.kt}`; tests `linux/src/test/.../AutostartTest.kt`

**Interfaces:** `SecretServiceStore.openOrNull(conn): SecretServiceStore?` (null when `org.freedesktop.secrets` is not on the bus) implementing `KeyValueStore` with items labelled "Umm <key>" in the default collection, attributes `application=io.github.agopalareddy.Umm`, `key=<name>`, plain session (no DH; the bus is local), unlocking the collection via `Prompt` when locked. `DbusNotifier : Notifier` (`Notify` with app name "Umm", icon `io.github.agopalareddy.Umm`, action `open` when requested, `ActionInvoked` signal → `onOpen`). `KWinFocusTracker.createOrNull(conn): FocusTracker?` (KDE only: loads a small KWin script that reports `workspace.activeWindow.resourceClass` over D-Bus to an object Umm exports; null when KWin scripting is absent). `Autostart(home: File)` with `fun enable(exec: String)`, `fun disable()`, `fun isEnabled()`. `AwtClipboard : Clipboard`.

- [ ] **Step 1: Failing test** `AutostartTest`: `enable("/opt/umm/bin/Umm")` writes `~/.config/autostart/io.github.agopalareddy.Umm.desktop` containing `Type=Application`, `Name=Umm`, `Exec=/opt/umm/bin/Umm --background`, `X-GNOME-Autostart-enabled=true`; `disable()` deletes it; respects `XDG_CONFIG_HOME` when passed.
- [ ] **Step 2: Run** `./gradlew :linux:test --tests '*AutostartTest*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; manual: store and read back a test item in KWallet with `SecretServiceStore`, then delete it.
- [ ] **Step 5: Commit** `feat(linux): add keyring, notifications and autostart`.

### Task 7: Tray icon and single instance

**Files:** Create `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/{SniTrayIcon.kt, SingleInstance.kt, UmmLink.kt}` and icon resources `linux/src/main/resources/tray/{idle,recording,busy}.png` (generated from the Umm mark at 22 and 44 px); tests `linux/src/test/.../{UmmLinkTest.kt, SingleInstanceTest.kt}`

**Interfaces:** `SniTrayIcon(conn) : TrayIcon` (exports `org.kde.StatusNotifierItem` with `IconPixmap` per state and a `com.canonical.dbusmenu` menu: Open Umm, Start dictation / Stop, Cancel, Quit; registers with `org.kde.StatusNotifierWatcher`; no-op when no watcher). `object UmmLink { fun authCode(url: String): String? }` (`umm://oauth?code=X` → X; anything else → null). `class SingleInstance(conn)` with `fun claim(onActivate: () -> Unit, onUrl: (String) -> Unit): Boolean` (true when this process owns `io.github.agopalareddy.Umm`; exports `Activate()` and `OpenUrl(s)`) and `fun forward(args: Array<String>)` (calls `OpenUrl` for a `umm://` argument, else `Activate`). Pure helper `fun SingleInstance.Companion.messageFor(args: Array<String>): Message` where `sealed interface Message { data object Activate; data class OpenUrl(val url: String) }`.

- [ ] **Step 1: Failing tests:** `UmmLinkTest` (code extracted; wrong scheme, missing code, `umm://other` → null; URL-encoded code decoded); `SingleInstanceTest.messageFor` (`[]` → Activate, `["--background"]` → Activate, `["umm://oauth?code=a"]` → OpenUrl).
- [ ] **Step 2: Run** `./gradlew :linux:test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; manual: the tray icon shows on KDE and switches state; a second launch raises the first.
- [ ] **Step 5: Commit** `feat(linux): add tray icon and single instance`.

### Task 8: Background app, orb and key storage

**Files:** Modify `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/{Main.kt, DesktopGraph.kt}`; create `.../desktop/{DesktopApp.kt, OrbWindow.kt, DesktopSettings.kt, SignIn.kt, Sounds.kt}` and `desktop/src/main/resources/sounds/{start,stop}.wav` (two short generated tones); tests `desktop/src/test/.../{DesktopGraphKeyTest.kt, DesktopSettingsTest.kt}`

**Interfaces:** `DesktopGraph(paths, env, api = null, recommendationsUrl = DEFAULT_URL, secretStore: KeyValueStore? = null)`: key store = `ApiKeyStore(secretStore ?: InMemoryKeyValueStore(), PassThroughCipher)`; `val keyringAvailable = secretStore != null`; `OPENROUTER_API_KEY` set → held in a separate in-memory store and used as the key without being saved. `DesktopSettings(store: DataStore<Preferences>)` with `data class DesktopPrefs(val orbPosition: OrbPosition = BOTTOM_CENTER, val sounds: Boolean = false, val startAtLogin: Boolean = false, val microphone: String? = null, val setupDone: Boolean = false)` and `enum class OrbPosition { BOTTOM_CENTER, BOTTOM_LEFT, BOTTOM_RIGHT, TOP_CENTER }`. `SignIn` holds the PKCE verifier and turns `UmmLink.authCode` into `openRouter.exchangeAuthCode` + `apiKeyStore.set(key, SIGNED_IN)`. `OrbWindow`: undecorated, transparent, always-on-top, `focusableWindowState = false`, 96×96 dp at the chosen position on the primary screen; shows `VoiceOrb` for Listening/Processing, ✓/"Copied"/✕ text states, and the GNOME paste hint; click = `controller.stop()`, ✕ = `controller.cancel()`; hidden when `Idle`, fades 1 s after a final state. `Sounds` plays `start.wav` on `Listening` and `stop.wav` when recording ends, only when `DesktopPrefs.sounds` is on. `Main.kt`: `--dictate` unchanged; otherwise `SingleInstance.claim` (else `forward(args)` and exit); `--background` starts without the window; registers the app id, binds the hotkey, shows the tray, starts the controller.

- [ ] **Step 1: Failing tests:** `DesktopGraphKeyTest`: `noKeyring_keepsKeyInMemory` (secretStore null → `keyringAvailable` false, set key readable in-process, no file under the temp XDG dirs contains it); `envKey_winsAndIsNotSaved` (env key present → `get()` returns it, fake secret store untouched); `keyringStoresPastedKey` (fake store receives `openrouter_key`). `DesktopSettingsTest`: defaults and round-trip of each field.
- [ ] **Step 2: Run** `./gradlew :desktop:test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; manual on KDE: `./gradlew :desktop:run --args=--background`, tap and hold dictation into Kate with the orb and tray states visible.
- [ ] **Step 5: Commit** `feat(desktop): run in the background with orb and tray`.

### Task 9: First-run setup, Desktop settings page and dev install

**Files:** Create `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/{SetupScreen.kt, DesktopSettingsPage.kt}`; modify `desktop/build.gradle.kts` (tasks `installDev`, `uninstallDev`), `Main.kt` (routes and `extraSettings`); create `desktop/packaging/io.github.agopalareddy.Umm.desktop.in` and the app icon `desktop/packaging/io.github.agopalareddy.Umm.png`

**Interfaces:** routes `desktop/setup` and `desktop/settings` via `UmmNavHost(platformRoutes = …)`, Settings row "Desktop" (orb position, sounds, start at login, microphone list from `AudioSystem.getMixerInfo()` filtered to capture lines, current shortcut and a "Change shortcut" button). Setup steps per spec §5, with a 10 s press-to-test that listens to `HotkeySource.events`. `installDev`: depends on `createDistributable`, writes the desktop file (`Exec=<image>/bin/Umm %u`, `MimeType=x-scheme-handler/umm;`, `StartupWMClass`, icon) to `~/.local/share/applications/`, copies the icon to `~/.local/share/icons/hicolor/256x256/apps/`, runs `update-desktop-database` and `xdg-mime default io.github.agopalareddy.Umm.desktop x-scheme-handler/umm`. `uninstallDev` reverses it and removes the autostart entry.

- [ ] **Step 1:** Write `DesktopSettingsPage` and `SetupScreen` (UI over tested pieces; no new logic beyond `DesktopSettings`).
- [ ] **Step 2:** Run `./gradlew :desktop:test :desktop:installDev`; `desktop-file-validate ~/.local/share/applications/io.github.agopalareddy.Umm.desktop` → no errors; `xdg-mime query default x-scheme-handler/umm` → `io.github.agopalareddy.Umm.desktop`.
- [ ] **Step 3:** Manual on KDE: launch from the app menu, complete setup (sign in through the browser, approve both dialogs, press-to-test), reboot or log out and in, confirm Umm starts in the background and the key survived.
- [ ] **Step 4: Commit** `feat(desktop): add first-run setup and dev install`.

### Task 10: Live emoji tests

**Files:** Modify `core/src/desktopTest/kotlin/io/github/agopalareddy/umm/core/live/LiveOpenRouterTest.kt`

- [ ] **Step 1:** Add `emojiRequestBecomesEmoji` ("sounds good thumbs up emoji" at LIGHT → contains "👍", not "emoji"), `talkingAboutEmojiKeepsWords` ("I love that emoji you sent" → contains "emoji", no character above U+1F000), `hinglishEmojiStaysLatin` ("kal milte hain heart emoji" at LIGHT, LATIN → contains "❤", no Devanagari).
- [ ] **Step 2:** Run `./gradlew :core:desktopTest -Plive --tests '*LiveOpenRouterTest*'` (key from `.env`, quotes stripped) → PASS. If a case fails, tune the emoji rule in `PromptBuilder` and rerun the unit and live tests.
- [ ] **Step 3: Commit** `test(core): add live emoji cleanup tests`.

### Task 11: Verification on KDE and GNOME

- [ ] **Step 1:** KDE host matrix: tap and hold into Firefox, Kate and Konsole/WezTerm; 10 dictations in a row; "thumbs up emoji" and a Devanagari phrase; orb states; tray menu Start/Stop/Cancel/Quit; second launch raises the window; refused typing permission → Copied; keyring stopped → in-memory banner; `--dictate` alongside the background app.
- [ ] **Step 2:** GNOME 50 VM: the spike's VM files were deleted. Ask the user before downloading the Fedora Workstation live ISO again (state file name and size), recreate the VM with the spike's QEMU/QMP scripts, copy the `createDistributable` image in over 9p, run `installDev` there, and repeat Step 1 with gedit and GNOME Terminal (mic replaced by `--dictate` replay if the VM has no audio, as in the spike).
- [ ] **Step 3:** Record results in the PR description draft; fix any failure in its own commit with a failing test first where the logic is testable; ask the user before pushing or merging.
