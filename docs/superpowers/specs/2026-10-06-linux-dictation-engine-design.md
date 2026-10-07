# Linux dictation engine

Status: draft for review, 2026-10-06.

## 1. Goal

Press a global hotkey on KDE Plasma or GNOME (Wayland) and dictate into any app, using the same shared pipeline,
settings, history and stats as the Android app.

This is sub-project 2 of the Linux app. Sub-project 1 (shared core and UI, merged in PR #2) gave the desktop the
pipeline and the settings screens. Still to come: 3, desktop app at parity (layout, accent color, per-app
categories on desktop); 4, packaging (Flatpak, AUR, release CI); 5, QR pairing and serverless sync.

The 2026-10-05 spike (`.superpowers/linux-spike/findings.md`, untracked) proved the hard parts on KDE Plasma 6.7
and GNOME 50.

### Success criteria

- On the KDE host and in the GNOME 50 VM, tap and hold dictation both insert cleaned text into Firefox, Kate
  (gedit on GNOME) and a terminal; ten dictations in a row all land.
- Emoji and Devanagari arrive intact on KDE in every app, and on GNOME in Firefox.
- The orb shows listening, processing and done/failed on both desktops.
- The key survives a reboot (keyring), and sign-in through the browser works.
- Desktop dictations appear in the window's History and stats.

### Non-goals

- Per-app cleanup levels on desktop (the app picker), desktop layout, accent color (sub-project 3).
- X11, Flatpak sandbox, wlroots compositors such as Sway or Hyprland, and packaging (sub-project 4).
- Android changes beyond what shared code needs.

## 2. Decisions

| Topic | Decision |
|---|---|
| Hotkey | Default Super+Alt+Space, bound through the GlobalShortcuts portal; the user can change it in the desktop's shortcut settings. A tap toggles (auto-stop on silence per the shared "stop after silence" setting, or the next tap); holding over 500 ms is hold-to-talk. |
| Feedback | A floating orb while dictating (bottom-centre by default, position is a setting), a tray icon with a menu, notifications for errors only, optional start/stop sounds (off by default). |
| Inserting text | Type as keys wherever the desktop allows; paste only what it can't type. On failure, copy to the clipboard and say so. |
| GNOME indicator | One RemoteDesktop session per dictation, so GNOME's orange icon shows about 5 s each time, never permanently. |
| Key storage | System keyring (Secret Service). Connect by browser sign-in through `umm://` or by pasting a key; `OPENROUTER_API_KEY` stays as a developer override. |
| Lifecycle | Starts at login in the background (setting, on by default after setup); tray icon; settings window on demand; one instance. |
| Architecture | Pure Kotlin/JVM in one process, talking to the desktop over D-Bus (dbus-java + junixsocket, as in the spike). Rejected: a native helper (two languages, per-distro builds) and JNA bindings to libportal/libsecret (native library version drift). |

## 3. Modules

```
core/      unchanged
ui/        shared screens (sub-project 1)
linux/     NEW Kotlin/JVM module: adapters that talk to the Linux desktop
desktop/   the app: lifecycle, windows, dictation controller, wiring
```

### `linux/` (thin adapters, one job each)

| Class | Job |
|---|---|
| `GlobalShortcutsHotkey : HotkeySource` | Binds `SUPER+ALT+space` as shortcut id `dictate`; emits `Down` and `Up` from `Activated`/`Deactivated`. |
| `PortalTextInserter : TextInserter` | Opens a RemoteDesktop + Clipboard session per call (restoring with the saved token), types keysyms, pastes through the clipboard, closes the session. |
| `JavaSoundMicrophone : AudioSource` | Records 16 kHz mono 16-bit WAV from the default (or chosen) input; emits the peak sample of each 100 ms like the Android recorder. |
| `SecretServiceStore : KeyValueStore` | Reads and writes keyring items tagged `application=io.github.agopalareddy.Umm`, `key=<name>`. |
| `SniTrayIcon : TrayIcon` | StatusNotifierItem icon, tooltip and menu. |
| `KWinFocusTracker : FocusTracker` | On KDE, the focused window's app id and whether it is a terminal; null elsewhere. |
| `SingleInstance` | Owns the bus name `io.github.agopalareddy.Umm`; a second launch calls `Activate()` or `OpenUrl(url)` on the first and exits. |
| `Notifier` | `org.freedesktop.Notifications`, with an optional "Open Umm" action. |
| `Autostart` | Writes or removes `~/.config/autostart/io.github.agopalareddy.Umm.desktop` (`Exec=… --background`). |

### `desktop/` (plain Kotlin logic, unit-tested with fakes)

| Class | Job |
|---|---|
| `HotkeyGesture` | Turns `Down`/`Up` events and a clock into `Start`, `Stop` and `Ignore`. Repeated `Down` while held (GNOME sends one every 30 ms) is ignored. |
| `DictationController` | Connects gestures, the orb and tray actions to the shared `DictationPipeline` and the `TextInserter`; enforces the busy rule; waits for the hotkey release before inserting; publishes `DesktopState`. |
| `InsertionPlanner` | Splits text into typed and pasted parts from a `TypingCapability` (`ALL` on KDE, `ASCII` on GNOME) and picks the paste chord. |
| `OrbWindow` | Borderless, always-on-top, non-focusable Compose window (XWayland) showing the shared `VoiceOrb` and a small ✕. |
| `DesktopApp` | Background lifecycle, tray menu, window on demand, first-run setup, `--dictate` mode kept for testing. |
| `DesktopSettings` | Orb position, sounds, start at login, microphone; stored in `~/.config/umm/desktop.preferences_pb`, separate from the shared settings. |

`DesktopState` is `Idle`, `NeedsKey`, `Listening(level)`, `Processing`, `Inserted(pastedOnGnome: Boolean)`,
`Copied(reason)` or `Failed(reason)`.

### Changes to existing code

- `DesktopGraph` builds the key store as `ApiKeyStore(SecretServiceStore(...), PassThroughCipher)` (the keyring
  encrypts at rest), falling back to the in-memory store when no Secret Service is running; the environment
  variable, when set, wins and is not saved.
- The desktop window passes a "Desktop" settings row through `UmmNavHost`'s `extraSettings`, and the Desktop page
  and first-run setup through `platformRoutes`.

## 4. One dictation

1. **Key down.** If no key is connected: orb shows "Connect OpenRouter", a notification offers "Open Umm", nothing
   records. Otherwise recording starts on key-down (the pipeline with `JavaSoundMicrophone`), the orb fades in as
   listening and the tray icon switches to recording. Optional start sound.
2. **Key up.** Held over 500 ms: stop. A tap: keep recording until silence (shared setting; Off means until the next
   tap) or the next tap. Clicking the orb also stops; its ✕ cancels; the tray menu offers Stop and Cancel.
3. **Processing.** The shared pipeline transcribes and cleans as on Android and writes history and stats. Package
   name: the KDE app id from `KWinFocusTracker`, else `desktop`. Level: `CategoryRepository.configFor(packageName)`,
   which is the default level until sub-project 3 adds desktop app assignment. Language: the default language.
4. **Inserting.** After the hotkey is released (in toggle mode it already is), open a session, then:
   - typed parts go out as keysyms;
   - pasted parts: on KDE, save the clipboard, set the text, send Ctrl+V (Ctrl+Shift+V when the focused app is a
     terminal), then restore the clipboard; on GNOME, set the text and send Ctrl+V, leave the dictated text on the
     clipboard, and the orb says "If nothing appeared, press Ctrl+Shift+V" (Umm cannot see the focused app there);
   - close the session. The orb shows ✓ and fades after about a second. Optional stop sound.
5. **Busy rule.** Hotkey events during processing and inserting are ignored, so injected keys can never re-trigger
   a dictation (the spike's first-round bug).

Rules carried from the spike: never inject while the trigger modifiers are held; close the clipboard fd that the
portal hands over (not a `/proc/self/fd` copy); register the app id with `org.freedesktop.host.portal.Registry`
before using portals.

## 5. Desktop integration

- **Identity.** App id `io.github.agopalareddy.Umm` for the `.desktop` file, D-Bus name and portal registration.
- **Dev install.** `./gradlew :desktop:installDev` builds the runnable image (`createDistributable`) and writes
  `~/.local/share/applications/io.github.agopalareddy.Umm.desktop` (with `MimeType=x-scheme-handler/umm;` and an
  executable `Exec`) plus the autostart entry; `:desktop:uninstallDev` removes them. Packaging replaces this in
  sub-project 4.
- **Hotkey binding.** First run asks the portal to bind `SUPER+ALT+space`; the desktop shows its own dialog and
  remembers the binding per app id. Settings shows the current trigger (`ShortcutsChanged`/`ListShortcuts`) and a
  button that calls `ConfigureShortcuts` where the portal supports it.
- **Typing permission.** The first insertion shows the RemoteDesktop dialog (keyboard only, `persist_mode=2`). The
  restore token is stored in the keyring item `remote_desktop_token`; a refused restore shows the dialog once more.
- **Keyring.** No Secret Service: keep the key in memory and show "No keyring found: your key won't be saved".
  Never write the key to a plain file.
- **Sign-in.** "Connect with OpenRouter" opens `AuthUrl.build(challenge)` in the browser (same GitHub Pages
  callback as Android). The page opens `umm://oauth?code=…`; the system launches Umm with the URL;
  `SingleInstance` forwards it to the running instance, which exchanges the code with its PKCE verifier and saves
  the key. Pasting a key stays.
- **First-run setup.** Connect OpenRouter; approve the shortcut; approve typing (note to switch on "Allow Remote
  Interaction" on GNOME); press the shortcut to test (no event within 10 s suggests a clash and how to rebind); try a
  dictation in a practice box. Start at login turns on when setup completes.
- **The window.** The shared screens plus the setup route and a "Desktop" settings page. Closing hides it; Quit is
  in the tray menu.

## 6. Errors

| Situation | Behavior |
|---|---|
| No key | Orb "Connect OpenRouter", notification with "Open Umm"; no recording |
| No microphone, or it is busy | Orb ✕, notification "Microphone unavailable" |
| GlobalShortcuts portal missing | Setup explains it; tray "Start dictation" still works |
| Shortcut accepted but clashing | Setup's press-to-test step detects no event and suggests another key |
| Network, key, credits, model errors | Same wording as Android (`FailureReason`), on the orb and in a notification |
| Cleanup failed | Raw text inserted, notification |
| Typing permission refused or session fails | Text copied, orb "Copied", notification with the reason |
| No speech / empty transcript | Orb fades with "No speech", no notification |

## 7. Testing and verification

- **Unit tests (desktopTest/test, written first, with fakes):** `HotkeyGesture` timing (tap, hold at 499/501 ms,
  repeated downs, stray ups); `DictationController` (busy rule, waits for release, orb stop and cancel, no key,
  fallback to clipboard, cleanup-failed path, state sequence); `InsertionPlanner` (ASCII vs all, mixed text,
  terminal chord on KDE, GNOME leaves clipboard); WAV writing and 100 ms peaks; `umm://` link parsing;
  `SingleInstance` message handling; `Autostart` file contents; `DesktopSettings` defaults.
- **Adapters** stay thin and are verified on real desktops, not with D-Bus mocks.
- **Live OpenRouter tests (`-Plive`, manual):** "thumbs up emoji" gives 👍 and drops the word; talking about an emoji
  keeps the words; Hinglish with "heart emoji" under the Latin script stays romanized and gets ❤️.
- **Manual matrix (KDE host, GNOME 50 VM):** the success criteria above, plus the first-run dialogs, a refused typing
  permission, no keyring, and running `--dictate` alongside the background app.

## 8. Risks

- **Portal behavior differs by version.** GlobalShortcuts needs xdg-desktop-portal 1.17+ with a backend that
  implements it (KDE 6, GNOME 48+). Older desktops fall back to the tray's Start dictation.
- **XWayland orb.** The orb relies on XWayland honoring always-on-top and non-focusable windows. If GNOME ignores
  stacking for some app, the orb may sit behind it; the tray and notifications still report state.
- **Tray on GNOME.** Hidden without an AppIndicator extension; the orb and notifications carry the state there.
- **Two processes.** `--dictate` while the background app runs shares the database and DataStore files; it stays a
  test tool, and the window does not live-refresh from it.
