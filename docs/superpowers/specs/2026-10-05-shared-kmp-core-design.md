# Shared core and UI (Kotlin Multiplatform)

Status: draft for review, 2026-10-05.

## 1. Goal

Make the Android app and a future Linux desktop app run the same Kotlin code: the dictation pipeline, the
OpenRouter client, prompts, model policy, the database, settings, stats, and the Compose settings screens,
dashboard, and charts.

This is sub-project 1 of the Linux app. The order agreed on 2026-10-05:

1. Shared core and UI (this spec).
2. Linux dictation engine: global hotkey, desktop mic, text insertion, keyring.
3. Desktop app at parity: desktop layout, per-app categories, accent color.
4. Packaging: Flatpak, AUR, release CI.
5. Cross-device: QR pairing, then serverless stats and history sync. No accounts, no Umm server.

The Linux feasibility spike (KDE Plasma 6.7 and GNOME 50, 2026-10-05) confirmed that a Kotlin/JVM app can
register a global shortcut, record audio, and insert text on both desktops. Its findings shape sub-project 2,
not this one.

### Success criteria

- The Android app behaves and looks exactly as it does today.
- An update keeps existing users' history, stats, settings, and encrypted OpenRouter key.
- All `core` tests run on the plain JVM.
- A desktop app on Linux runs the shared pipeline and shows the shared screens with real data.

### Non-goals

- Desktop hotkey, text insertion, microphone capture, and keyring storage (sub-project 2).
- Desktop layout, per-app categories on desktop, system accent color, and light/dark detection (sub-project 3).
- Packaging or distributing the desktop app (sub-project 4).
- An Android release. The user decides when the next version ships.
- iOS or any non-JVM target. Both targets are JVM, so OkHttp and `java.*` stay.

## 2. Decisions

| Topic | Decision |
|---|---|
| Scope | Logic and Compose UI are shared now. Android-only surfaces (IME, floating button, Android onboarding) stay in `app`. |
| Structure | Two KMP library modules, `core` (logic) and `ui` (Compose Multiplatform), plus a `desktop` JVM app. |
| Rollout | Two phases on stacked branches: `feat/kmp-core`, then `feat/kmp-ui`. Each is verified on the Pixel before the next starts. |
| Desktop look | Umm's own Material 3 design on every platform. Desktop later picks up the system accent color and light/dark preference (sub-project 3); this spec only leaves the hook. |
| Rejected | One combined `shared` module (logic would depend on Compose, no checkpoint between phases). Plain JVM modules without KMP (cannot share Compose UI with Android). |

## 3. Modules and build

```
umm/
├── core/      KMP library: android + jvm("desktop")      (was an Android library)
│   ├── commonMain    pipeline, OpenRouter, prompts, policy, Room DB, settings, stats, key store
│   ├── androidMain   MediaRecorder audio, Keystore cipher, SharedPreferences key storage, DB and DataStore builders
│   └── desktopMain   DB and DataStore builders (XDG paths)
├── ui/        KMP library + Compose Multiplatform: android + desktop        (new)
│   ├── commonMain    theme, charts, voice orb, dashboard, shared screens, UmmNavHost, Platform interface
│   ├── androidMain   Android Platform implementation
│   └── desktopMain   desktop Platform implementation
├── app/       Android app: MainActivity, IME, floating button, onboarding, bubble page, AppGraph
└── desktop/   JVM app: Compose window and the --dictate proof                (new)
```

- `core` and `ui` use AGP 9.4's `com.android.kotlin.multiplatform.library` plugin. `ui` and `desktop` add
  Compose Multiplatform 1.12.1. Kotlin stays at 2.4.20.
- Room 2.8.5 and DataStore 1.2.1 switch to their multiplatform artifacts; desktop adds
  `androidx.sqlite:sqlite-bundled` 2.7.1. Navigation (`org.jetbrains.androidx.navigation` 2.9.2), Lifecycle
  (`org.jetbrains.androidx.lifecycle` 2.11.0), and Material icons extended (`org.jetbrains.compose.material` 1.7.3)
  switch to the JetBrains multiplatform builds. OkHttp stays.
- Dependencies: `app → ui → core`, `desktop → ui → core`. Nothing in any `commonMain` imports `android.*`.
- New versions go in `gradle/libs.versions.toml`, like the existing ones.

## 4. Moving `core`

| Piece | Change | Data compatibility |
|---|---|---|
| Room database | `UmmDatabase` moves to `commonMain` with `@ConstructedBy` and an `expect object` constructor. `SeedCallback` moves from `SupportSQLiteDatabase` to the `SQLiteConnection` callback and inserts the same rows. Android builds with `Room.databaseBuilder(context, "umm.db")` and `AndroidSQLiteDriver`; desktop builds with `BundledSQLiteDriver` at `$XDG_DATA_HOME/umm/umm.db` (default `~/.local/share/umm/umm.db`). DAOs are already all `suspend` or `Flow`, so they don't change. | Same file name and location, schema version 2, `AutoMigration(1, 2)` kept. The exported schema JSON in `core/schemas/` must be byte-identical after the move. |
| Settings | `SettingsRepository` already takes a `DataStore<Preferences>` and moves unchanged. Android keeps `preferencesDataStore("settings")` in `AppGraph`. Desktop uses `PreferenceDataStoreFactory.createWithPath` at `$XDG_CONFIG_HOME/umm/settings.preferences_pb` (default `~/.config/umm/`). | Same Android file. |
| API key store | `ApiKeyStore` moves to `commonMain`. `SharedPreferences` is replaced by a `KeyValueStore` interface (`getString`, `putString`, `remove`). `android.util.Base64` with `NO_WRAP` is replaced by `java.util.Base64`, which encodes identically. The Android implementation wraps the same `secure` prefs file and the keys `openrouter_key` and `openrouter_key_source`. | Same prefs file, keys, encoding, and Keystore cipher. |
| Keystore cipher | `KeystoreCipher` moves to `androidMain` unchanged. `SecretCipher` stays common. | Same Keystore alias. |
| Audio | `AudioSource` gains a `format` property (`"m4a"` for `MediaRecorderAudioSource`). `DictationPipeline` names new recordings with it and sends the transcription format from the audio file's extension instead of hardcoding `m4a`, so a retry of an older recording still sends its own format. `MediaRecorderAudioSource` moves to `androidMain`. | Retry audio already on disk (`.m4a`) still transcribes. |
| Debug sample data | `SampleData.remove` deletes through `openHelper`, which driver-based Room does not offer. It calls a new `StatsRepository.deleteFromHistoryId(firstId)` backed by a `@Query` DELETE in `StatsDao`, so stats flows still refresh. | Real rows untouched, as today. |
| Auth URL | `AuthUrl.build` returns a `String` built with `URLEncoder` instead of `android.net.Uri`. Callers in `app` parse it with `Uri.parse`. `AuthUrlTest` asserts the output is unchanged. | Not affected. |
| Silence detection, policy, prompts, OpenRouter, stats | Move to `commonMain` unchanged. | Not affected. |

On desktop the OpenRouter key comes from the `OPENROUTER_API_KEY` environment variable and is held in memory
only. Persistent desktop storage (Secret Service keyring) is sub-project 2.

### Tests

- The six Robolectric-based tests (repositories and `ApiKeyStore`) become plain JVM tests: repositories use an
  in-memory Room database with `BundledSQLiteDriver`, and `ApiKeyStore` uses a fake `KeyValueStore`.
- Pure-logic tests move to `commonTest` and run on both targets. Tests that need Android stay as Android unit tests.
- The live OpenRouter tests keep their `-Plive` gate.

## 5. Moving the UI

### Services for screens

Screens stop calling `LocalContext.current.graph`. `ui` provides a `LocalUmm` CompositionLocal holding an
`UmmServices` object with:

- The `core` objects the screens use today: settings, API key store, history, stats, categories, data policy,
  model catalog, and recommendations.
- A `Platform` interface with only the platform calls the screens make today:

| Method | Android | Desktop (this spec) |
|---|---|---|
| `openUrl(url)` | `ACTION_VIEW` intent | `xdg-open` |
| `composeEmail(to, subject, body): Boolean` | `ACTION_SENDTO` intent | `xdg-open mailto:` |
| `copyText(text)` | `ClipboardManager` | AWT clipboard |
| `showMessage(text)` | `Toast` | snackbar in the window |
| `installedApps(): List<AppEntry>` | `PackageManager` launcher query | empty list (desktop apps come in sub-project 3) |
| `is24HourClock(): Boolean` | `DateFormat.is24HourFormat` | locale default |
| `animationsEnabled(): Boolean` | `ANIMATOR_DURATION_SCALE` | `true` |
| `dynamicColors(dark): ColorScheme?` | Material You on Android 12+ | `null` (system accent color in sub-project 3) |

`AppEntry` is an ID and a label. On Android the ID is the package name, so stored app assignments keep working.

### Navigation

A shared `UmmNavHost` in `ui` holds the shared routes and route names from today's `Routes`: Home, Settings,
Account, Dictation, Languages, Appearance, Stats, Categories, Models, and History. It takes:

- A `platformRoutes` builder for platform-only destinations. Android adds Onboarding and the Floating button page.
- Extra Settings rows supplied by the platform. Android adds the Floating button row.
- The Home setup banner state and an optional Home action. Android supplies its setup status and "Switch keyboard".
- The start destination, since only Android has an onboarding flow today.

### What moves where

- To `ui/commonMain`: `UmmTheme`, `Logo`, `VoiceLevel`, `VoiceOrb`, all of `ui/charts`, all of
  `settings/dashboard` (including `SampleData`, still debug-only), and `HomeScreen`, `HistoryScreen`,
  `ModelsScreen`, `CategoriesScreen`, `SettingsScreen` (all pages except the Floating button page), `StatsPage`,
  and `Common`.
- `DateUtils.getRelativeTimeSpanString` in History is replaced by a shared relative-time formatter with tests.
- Stays in `app`: `MainActivity` (hosts `UmmNavHost`, keeps the mic permission launcher and sign-in),
  `Onboarding`, `BubblePage`, the IME (`ime/`), and the bubble overlay (`bubble/`). The IME and bubble import
  `VoiceOrb` and `UmmTheme` from `ui`.
- App-wide strings stay hardcoded as they are today. No resource migration.

## 6. Desktop proof app

`desktop/` uses the Compose Multiplatform desktop application plugin and runs with `./gradlew :desktop:run`.
It is not packaged. Phase 1 creates it with dictation mode only and a dependency on `core`; phase 2 adds `ui`
and window mode.

- Window mode (default): builds the desktop graph (section 4 paths) and opens a phone-sized window titled "Umm"
  running `UmmNavHost`, starting at Home.
- Dictation mode: `--dictate <file.wav> [--level raw|light|formatted|polished]` runs the shared
  `DictationPipeline` with a `WavFileAudioSource` that replays the file and emits amplitudes from its samples,
  so silence detection and the level meter run as on the phone. It prints the raw transcript, the cleaned text,
  and the cost, and saves history and stats, which then appear in the window. Each run costs about $0.0004 and
  is only started by hand, never in CI.
- Errors: without `OPENROUTER_API_KEY`, the Account page shows its existing not-connected state and
  `--dictate` exits with a message naming the variable. A missing or unreadable WAV exits with a message.
  Pipeline failures use the existing `DictationState` errors.

## 7. Verification

### Phase 1 (`feat/kmp-core`) is done when

1. `./gradlew check` passes: all `core` tests on the JVM and the `app` tests.
2. `core/schemas/` is unchanged.
3. `./gradlew :app:assembleRelease` succeeds.
4. On the Pixel, the new build installed over v1.2.0 with real data still has its history, stats, settings, and
   key; a dictation works through the keyboard and through the floating button.
5. `./gradlew :desktop:run --args="--dictate <wav>"` prints a cleaned transcript on Linux. (In phase 1 the
   `desktop` module has only the dictation mode; the window comes in phase 2.)

### Phase 2 (`feat/kmp-ui`) is done when

1. `./gradlew check` passes.
2. Screenshots of every shared screen on the Pixel, taken before phase 2 starts and after it ends, match in
   light and dark themes.
3. The desktop window opens every shared screen and shows history and stats from a `--dictate` run.
4. The release APK size is recorded before and after; a growth above 10% needs an explanation before merge.

### CI

The workflow adds the desktop module build and its tests. Live OpenRouter calls stay manual-only.

## 8. Risks

- **Material 3 version gap.** JetBrains Material 3 1.9.0 trails the AndroidX BOM 2026.09.00 that the app uses.
  On Android, Gradle keeps the newer AndroidX version. If a component used by a shared screen is missing on
  desktop, that screen gets a small desktop-only substitute.
- **AGP 9 KMP plugin maturity.** `com.android.kotlin.multiplatform.library` is the only supported way to put an
  Android target in a KMP module on AGP 9; `com.android.library` cannot be combined with `kotlin("multiplatform")`
  any more. The only escape hatch is `android.builtInKotlin=false` and `android.newDsl=false` in
  `gradle.properties`, which AGP 10 removes, so it may be used only to unblock a build temporarily, never merged.
- **Room driver switch on Android.** Moving from the SupportSQLite path to `AndroidSQLiteDriver` must not change
  how the existing file opens. Phase 1 step 4 (upgrade over v1.2.0) is the gate for this.
