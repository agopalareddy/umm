# Shared Core and UI (KMP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `core` a Kotlin Multiplatform library and move the Compose settings UI into a new Compose Multiplatform `ui` module, so a Linux desktop app runs the same pipeline and screens as Android, with Android behavior and user data unchanged.

**Architecture:** Phase 1 (`feat/kmp-core`) converts `core` to KMP (targets `android` and `jvm("desktop")`), moves code from `androidMain` to `commonMain` package by package, and adds a `desktop` module that runs the shared pipeline on a WAV file. Phase 2 (`feat/kmp-ui`, stacked) adds the `ui` module, moves screens behind a `Platform` interface and a shared `UmmNavHost`, and turns `desktop` into a Compose window.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1 (`com.android.kotlin.multiplatform.library`), Compose Multiplatform 1.12.1, Room 2.8.5 (KMP) with `androidx.sqlite` 2.7.1, DataStore 1.2.1, OkHttp 5.5.0, JUnit 4, Robolectric 4.17 (Android host tests only).

**Spec:** `docs/superpowers/specs/2026-10-05-shared-kmp-core-design.md`

## Global Constraints

- Package names do not change (`io.github.agopalareddy.umm.core.*`, `io.github.agopalareddy.umm.*`). Files move between source sets only.
- No `commonMain` source set imports `android.*` or `androidx.compose.ui.platform.LocalContext`.
- The Android database stays `umm.db`, schema version 2, `AutoMigration(from = 1, to = 2)`; `core/schemas/` must be byte-identical at the end of every Phase 1 task (`git diff --exit-code core/schemas`).
- Android settings DataStore stays `preferencesDataStore("settings")`; key storage stays the `secure` SharedPreferences file with keys `openrouter_key` and `openrouter_key_source`, Base64 without line wrapping.
- Desktop paths: database `$XDG_DATA_HOME/umm/umm.db` (default `~/.local/share/umm/umm.db`), settings `$XDG_CONFIG_HOME/umm/settings.preferences_pb` (default `~/.config/umm/settings.preferences_pb`).
- Desktop key: `OPENROUTER_API_KEY` environment variable, held in memory only.
- Test placement: tests of `commonMain` code go in `desktopTest` (plain JVM); `androidHostTest` keeps only tests of `androidMain` code. `commonTest` stays empty.
- Live OpenRouter tests keep the `-Plive` gate and never run on push.
- Versions go in `gradle/libs.versions.toml`.
- Commits: Conventional Commits, subject ≤ 50 chars, **no `Co-Authored-By` or other AI trailer**.
- Never push or merge without the user's OK. Never change device system settings via adb.

## Review Focus

1. **An existing install upgraded from v1.2.0** must open its old `umm.db`, settings, and encrypted key. Pinned by Task 2 (schema diff gate, seed test), Task 4 (Robolectric compatibility test reading a key written the v1.2.0 way), and the Task 6 device check.
2. **Retrying a dictation recorded before the update** (an `.m4a` file in history) must still transcribe with format `m4a`. Pinned by `retry_usesTheStoredFileFormat` in Task 3.
3. **Removing debug sample data** must delete only seeded stats rows and refresh the dashboard. Pinned by `deleteFromHistoryId_keepsEarlierRows` in Task 2.
4. **Desktop run with no key, a missing WAV, or a silent WAV** must exit with a clear message, not a stack trace. Pinned by `DictateCommandTest` in Task 5.
5. **Fresh desktop machine without `XDG_*` variables** must create `~/.local/share/umm` and `~/.config/umm` and start cleanly. Pinned by `XdgPathsTest` in Task 5.

---

## Phase 1: shared core (`feat/kmp-core`)

The branch already exists with the spec commit. Work continues on it.

### Task 1: Convert `core` to a KMP library with everything in `androidMain`

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/build.gradle.kts`, `.github/workflows/ci.yml`
- Move: `core/src/main/**` → `core/src/androidMain/**` (including `AndroidManifest.xml` if present), `core/src/test/**` → `core/src/androidHostTest/**` (Kotlin and `resources/`)

**Interfaces:**
- Produces: Gradle tasks `:core:testAndroidHostTest` and `:core:desktopTest` (confirm exact names with `./gradlew :core:tasks --all | grep -iE "test"` and use what exists), an empty `desktop` target, KSP configurations `kspAndroid` and `kspDesktop`.

- [ ] **Step 1: Add catalog entries**

Plugins: `kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }`, `android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }`. Versions: `sqlite = "2.7.1"`. Libraries: `sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }`, `kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }`. Register both new plugins with `apply false` in the root `build.gradle.kts`.

- [ ] **Step 2: Rewrite `core/build.gradle.kts`**

Plugins: `kotlin-multiplatform`, `android-kotlin-multiplatform-library`, `kotlin-serialization`, `ksp`, `room`. In `kotlin { }`: `jvmToolchain(17)`; `android { namespace = "io.github.agopalareddy.umm.core"; compileSdk = 37; minSdk = 29; withHostTest { isIncludeAndroidResources = true } }`; `jvm("desktop")`. Source set dependencies: everything currently in `dependencies { }` goes to `androidMain` (implementation/api as today) and its test deps to `androidHostTest`. Keep `room { schemaDirectory("$projectDir/schemas") }`. Replace `ksp(libs.room.compiler)` with `add("kspAndroid", libs.room.compiler)`. Port the `tasks.withType<Test>` block unchanged (live exclusion and `sttModel`/`cleanupModel` properties).

- [ ] **Step 3: Move source trees with `git mv`** as listed under Files.

- [ ] **Step 4: Run all tests and builds**

Run: `./gradlew :core:testAndroidHostTest :app:testDebugUnitTest :app:assembleDebug && git diff --exit-code core/schemas`
Expected: BUILD SUCCESSFUL, the same core and app test counts as on `main` (record them from `main` before Step 2), no schema diff.

- [ ] **Step 5: Update CI**

In `ci.yml`, the Unit tests step runs `./gradlew :core:testAndroidHostTest :core:desktopTest :app:testDebugUnitTest --console=plain`; the live step runs `./gradlew :core:testAndroidHostTest -Plive --tests '*LiveOpenRouterTest*' --console=plain`; the report upload paths stay `core/build/reports/tests/` and `app/build/reports/tests/`.

- [ ] **Step 6: Commit**

```bash
git add -A gradle core build.gradle.kts .github
git commit -m "build(core): convert core to a KMP library"
```

### Task 2: Room, settings, stats and cleanup types in `commonMain`

**Files:**
- Move to `core/src/commonMain/kotlin/...`: `cleanup/*`, `data/*` except the database builder, `stats/*`
- Create: `core/src/androidMain/kotlin/io/github/agopalareddy/umm/core/data/UmmDatabaseAndroid.kt`, `core/src/desktopMain/kotlin/io/github/agopalareddy/umm/core/data/UmmDatabaseDesktop.kt`
- Modify: `data/UmmDatabase.kt`, `stats/StatsDao.kt`, `stats/StatsRepository.kt`, `app/src/main/kotlin/io/github/agopalareddy/umm/AppGraph.kt`, `app/src/main/kotlin/io/github/agopalareddy/umm/settings/dashboard/SampleData.kt`, `app/build.gradle.kts` (drop `room.runtime` if nothing else needs it), `core/build.gradle.kts`
- Move tests to `core/src/desktopTest/kotlin/...`: `CategoryRepositoryTest`, `HistoryRepositoryTest`, `StatsRepositoryTest`, `SettingsRepositoryTest`, and all `cleanup/` and `stats/` tests
- Create test: `core/src/desktopTest/kotlin/io/github/agopalareddy/umm/core/data/SeedTest.kt`

**Interfaces:**
- Consumes: Task 1 targets.
- Produces:
  - `@ConstructedBy(UmmDatabaseConstructor::class) abstract class UmmDatabase : RoomDatabase()` in common, with `expect object UmmDatabaseConstructor : RoomDatabaseConstructor<UmmDatabase>`.
  - `fun buildUmmDatabase(context: Context): UmmDatabase` (androidMain; `Room.databaseBuilder<UmmDatabase>(context, context.getDatabasePath("umm.db").absolutePath)` + `AndroidSQLiteDriver()` + seed callback).
  - `fun buildUmmDatabase(file: File): UmmDatabase` and `fun inMemoryUmmDatabase(): UmmDatabase` (desktopMain; `BundledSQLiteDriver()`, `Dispatchers.IO` query context, seed callback).
  - `StatsRepository.deleteFromHistoryId(firstId: Long)` backed by `@Query("DELETE FROM dictation_stats WHERE historyId >= :firstId") suspend fun deleteFromHistoryId(firstId: Long)` in `StatsDao`.
  - The companion `UmmDatabase.build(context)` / `inMemory(context)` are removed; callers use the functions above.

- [ ] **Step 1: Write the failing seed and sample-data tests** (desktopTest)

```kotlin
// SeedTest.kt
@Test fun newDatabase_seedsEveryCategoryAndApp() = runTest {
    val db = inMemoryUmmDatabase()
    val repo = CategoryRepository(db)
    // Same expectations the old CategoryRepositoryTest made about seeded rows:
    // every Seeds.levels category present with its level and LATIN script, every Seeds.apps assignment present.
}

// StatsRepositoryTest.kt (added case)
@Test fun deleteFromHistoryId_keepsEarlierRows() = runTest {
    // record entries with historyId 5 and 1_000_000; deleteFromHistoryId(1_000_000)
    // assertEquals(listOf(5L), repo.all().map { it.historyId })
}
```

Use `Seeds.levels` and `Seeds.apps` in the assertions so the test tracks the seed data, not copies of it.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:desktopTest`
Expected: FAIL to compile (`inMemoryUmmDatabase`, `deleteFromHistoryId` unresolved).

- [ ] **Step 3: Move the files and implement the interfaces above**

Add `room-runtime`, `datastore-preferences`, `kotlinx-coroutines-core`, `kotlinx-serialization-json` to `commonMain`; `sqlite-bundled` to `desktopMain`; `add("kspDesktop", libs.room.compiler)`. Rewrite `SeedCallback` as a `RoomDatabase.Callback` overriding `onCreate(connection: SQLiteConnection)`, inserting the same rows with `connection.prepare(sql).use { bind…; step() }`. Repository tests build their database with `inMemoryUmmDatabase()` and drop Robolectric. `SampleData.remove(db)` becomes `remove(stats: StatsRepository) = stats.deleteFromHistoryId(FIRST_ID)`; update its caller. `AppGraph.database` uses `buildUmmDatabase(app)`.

- [ ] **Step 4: Run tests, Android build and schema gate**

Run: `./gradlew :core:desktopTest :core:testAndroidHostTest :app:testDebugUnitTest :app:assembleDebug && git diff --exit-code core/schemas`
Expected: BUILD SUCCESSFUL, no schema diff.

- [ ] **Step 5: Commit**

```bash
git add -A core app
git commit -m "refactor(core): share Room, settings and stats"
```

### Task 3: OpenRouter, policy, audio and pipeline in `commonMain`

**Files:**
- Move to `commonMain`: `openrouter/*`, `policy/*`, `audio/AudioSource.kt`, `audio/SilenceDetector.kt`, `pipeline/*`
- Stays in `androidMain`: `audio/MediaRecorderAudioSource.kt`
- Modify: `AudioSource.kt`, `MediaRecorderAudioSource.kt`, `DictationPipeline.kt` (lines 134, 201, 337 today), `.github/workflows/ci.yml`
- Move tests to `desktopTest`: `openrouter/`, `policy/`, `audio/`, `pipeline/` (including `Fakes.kt`), `live/`, and `resources/audio/`

**Interfaces:**
- Consumes: Task 2 `inMemoryUmmDatabase()`.
- Produces: `interface AudioSource { val format: String; fun record(file: File): Flow<Int>; fun stop() }`. `MediaRecorderAudioSource.format == "m4a"`. `DictationPipeline.AUDIO_FORMAT` is removed.

- [ ] **Step 1: Write the failing pipeline tests** (in the moved `DictationPipelineTest`, using its existing fakes)

```kotlin
@Test fun recording_usesTheSourceFormat() = runTest {
    // fake audio source with format = "wav"; run a dictation to Done
    // assert the recorded file name ends with ".wav" and the fake API received format "wav"
}

@Test fun retry_usesTheStoredFileFormat() = runTest {
    // history entry pending with an existing "<uuid>.m4a" file; fake source format = "wav"
    // pipeline.retry(id) → fake API received format "m4a"
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:desktopTest --tests '*DictationPipelineTest*'`
Expected: FAIL (`format` not a member of `AudioSource`).

- [ ] **Step 3: Implement**

New recordings are named `"${UUID.randomUUID()}.${audio.format}"`; transcription passes `File(path).extension` as the format. Move the remaining files and tests; replace Robolectric DB setup with `inMemoryUmmDatabase()`. Move OkHttp, serialization and coroutines deps to `commonMain`; `mockwebserver`, `turbine`, `coroutines-test`, `junit` to `desktopTest`. The `-Plive` exclusion applies to `desktopTest`. CI live step becomes `./gradlew :core:desktopTest -Plive --tests '*LiveOpenRouterTest*' --console=plain`.

- [ ] **Step 4: Run all tests**

Run: `./gradlew :core:desktopTest :core:testAndroidHostTest :app:testDebugUnitTest :app:assembleDebug && git diff --exit-code core/schemas`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A core .github
git commit -m "refactor(core): share pipeline, policy and network"
```

### Task 4: Key store and auth URL in `commonMain`

**Files:**
- Create: `core/src/commonMain/kotlin/io/github/agopalareddy/umm/core/auth/KeyValueStore.kt`, `core/src/androidMain/kotlin/io/github/agopalareddy/umm/core/auth/SharedPreferencesKeyValueStore.kt`
- Move to `commonMain`: `auth/ApiKeyStore.kt`, `auth/AuthUrl.kt`, `auth/Pkce.kt`, `auth/SecretCipher.kt`
- Stays in `androidMain`: `auth/KeystoreCipher.kt`
- Modify: `AppGraph.kt` (key store construction), `app/src/main/kotlin/io/github/agopalareddy/umm/auth/SignInLauncher.kt:20` (`Uri.parse(AuthUrl.build(...))`)
- Tests: `ApiKeyStoreTest`, `AuthUrlTest`, `PkceTest` → `desktopTest`; create `core/src/androidHostTest/kotlin/io/github/agopalareddy/umm/core/auth/KeyStoreCompatibilityTest.kt`

**Interfaces:**
- Produces: `interface KeyValueStore { fun getString(key: String): String?; fun putString(key: String, value: String); fun remove(vararg keys: String) }`; `class SharedPreferencesKeyValueStore(prefs: SharedPreferences) : KeyValueStore`; `ApiKeyStore(store: KeyValueStore, cipher: SecretCipher)` with the same public API as today; `AuthUrl.build(challenge: String, callback: String? = CALLBACK, keyLabel: String = "Umm"): String`.

- [ ] **Step 1: Write the failing compatibility test** (Robolectric, androidHostTest)

```kotlin
@Test fun readsAKeyWrittenByV120() {
    val prefs = context.getSharedPreferences("compat", Context.MODE_PRIVATE)
    val cipher = XorCipher() // test cipher defined in this file
    val blob = cipher.encrypt("sk-or-v1-abcdef1234567890".toByteArray())
    prefs.edit().putString("openrouter_key", android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
        .putString("openrouter_key_source", "SIGNED_IN").commit()
    val store = ApiKeyStore(SharedPreferencesKeyValueStore(prefs), cipher)
    assertEquals("sk-or-v1-abcdef1234567890", store.get())
    assertEquals(KeySource.SIGNED_IN, store.source.value)
}
```

`ApiKeyStoreTest` (desktopTest) switches to an in-memory `KeyValueStore` fake and keeps its current cases. `AuthUrlTest` keeps its current expected URL strings, now compared to the returned `String`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:testAndroidHostTest --tests '*KeyStoreCompatibilityTest*'`
Expected: FAIL to compile (`SharedPreferencesKeyValueStore` unresolved).

- [ ] **Step 3: Implement** the interfaces above. `ApiKeyStore` encodes with `java.util.Base64.getEncoder()` and decodes with `getDecoder()`, and writes `PREF` and `SOURCE` together. `AuthUrl.build` appends query parameters in today's order (`callback_url` if non-null, `code_challenge`, `code_challenge_method`, `key_label`), each value encoded with `URLEncoder.encode(value, Charsets.UTF_8)`. `AppGraph` builds `ApiKeyStore(SharedPreferencesKeyValueStore(app.getSharedPreferences("secure", Context.MODE_PRIVATE)), KeystoreCipher())`.

- [ ] **Step 4: Run all tests and confirm `commonMain` is Android-free**

Run: `./gradlew :core:desktopTest :core:testAndroidHostTest :app:testDebugUnitTest :app:assembleDebug && ! grep -rn "^import android\." core/src/commonMain`
Expected: BUILD SUCCESSFUL and no grep output.

- [ ] **Step 5: Commit**

```bash
git add -A core app
git commit -m "refactor(core): share the key store and auth URL"
```

### Task 5: Desktop dictation proof

**Files:**
- Modify: `settings.gradle.kts` (`include(":desktop")`), `.github/workflows/ci.yml` (add `:desktop:test` to the Unit tests step)
- Create: `core/src/desktopMain/kotlin/io/github/agopalareddy/umm/core/platform/XdgPaths.kt`, `core/src/desktopMain/kotlin/io/github/agopalareddy/umm/core/data/SettingsStoreDesktop.kt`
- Create: `desktop/build.gradle.kts`, `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/{Main.kt, DesktopGraph.kt, DictateCommand.kt, WavFileAudioSource.kt}`
- Tests: `core/src/desktopTest/.../platform/XdgPathsTest.kt`, `desktop/src/test/kotlin/io/github/agopalareddy/umm/desktop/{WavFileAudioSourceTest.kt, DictateCommandTest.kt}`

**Interfaces:**
- Consumes: `buildUmmDatabase(file)`, `ApiKeyStore`, `KeyValueStore`, `DictationPipeline`, `DictationRequest`, `DictationState`, `HistoryRepository`.
- Produces:
  - `class XdgPaths(env: Map<String, String> = System.getenv(), home: File = File(System.getProperty("user.home")))` with `val dataDir: File` (`$XDG_DATA_HOME/umm` or `~/.local/share/umm`) and `val configDir: File` (`$XDG_CONFIG_HOME/umm` or `~/.config/umm`); both created on first access.
  - `fun buildSettingsStore(file: File): DataStore<Preferences>` (`PreferenceDataStoreFactory.createWithPath`).
  - `class WavFileAudioSource(source: File) : AudioSource` with `format = "wav"`: copies `source` to the target file and emits the peak absolute 16-bit sample of each 100 ms chunk, then completes. Rejects non-PCM-16 or unreadable files by throwing `IllegalArgumentException` with the reason.
  - `class DesktopGraph(paths: XdgPaths, env: Map<String, String>)` wiring the same objects as `AppGraph` (key store = `ApiKeyStore(InMemoryKeyValueStore(), PassThroughCipher)`, both private to `DesktopGraph.kt`, set from `OPENROUTER_API_KEY` with `KeySource.DEVELOPER`; recommendations bundled from the `recommended.json` resource; audio dir `paths.dataDir/audio`).
  - `suspend fun dictate(args: DictateArgs, graph: DesktopGraph, out: PrintStream): Int` returning the process exit code; `DictateArgs(file: File, level: CleanupLevel?)` parsed by `parseArgs(args: Array<String>): DictateArgs?` (`--dictate <file> [--level raw|light|formatted|polished]`, null when `--dictate` is absent).

- [ ] **Step 1: Write the failing tests**

```kotlin
// XdgPathsTest
@Test fun defaults_whenVariablesUnset() { /* env = emptyMap, home = tmp → dataDir == tmp/.local/share/umm, configDir == tmp/.config/umm, both exist */ }
@Test fun honoursXdgVariables() { /* env XDG_DATA_HOME=tmp/d, XDG_CONFIG_HOME=tmp/c → tmp/d/umm, tmp/c/umm */ }

// WavFileAudioSourceTest: generate a 16 kHz mono PCM-16 WAV in a temp dir:
// 0.5 s of silence then 0.5 s of a 1000-amplitude square wave
@Test fun emitsPeakPer100ms() { /* collect → 10 values; first five 0, last five 1000; target file bytes == source bytes */ }
@Test fun rejectsNonWav() { /* text file → IllegalArgumentException */ }

// DictateCommandTest (fake OpenRouterApi, temp XdgPaths)
@Test fun missingKey_exitsWithMessage() { /* env without OPENROUTER_API_KEY → exit 2, output contains "OPENROUTER_API_KEY" */ }
@Test fun missingFile_exitsWithMessage() { /* exit 2, output contains the path */ }
@Test fun silentWav_reportsNoSpeech() { /* all-zero WAV → exit 1, output contains "No speech" */ }
@Test fun success_printsRawCleanedAndCost() { /* fake API returns "um hello" / "Hello" / cost 0.0004 → exit 0, output contains all three; HistoryRepository has the entry */ }
@Test fun parseArgs_levelAndAbsence() { /* ["--dictate","a.wav","--level","raw"] → RAW; [] → null; bad level → null with usage */ }
```

`DesktopGraph` takes an optional `OpenRouterApi` override for these tests.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:desktopTest --tests '*XdgPathsTest*' :desktop:test`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

`desktop/build.gradle.kts`: `kotlin("jvm")` + `application` (`mainClass = "io.github.agopalareddy.umm.desktop.MainKt"`), `jvmToolchain(17)`, depends on `project(":core")`, resources include `../models` so `recommended.json` is on the classpath. `Main.kt`: if `parseArgs` returns null, print usage and exit 2 (window mode arrives in Task 11); else `runBlocking { exitProcess(dictate(...)) }`. `dictate` starts the pipeline with `DictationRequest(packageName = "desktop", level = args.level ?: settings default, script = LATIN, language = LanguageChoice from settings default, silenceTimeoutSec = null)`, waits for the first finished state, and prints the raw transcript, cleaned text and cost from the history entry, or a one-line message for `Failed` (the `FailureReason`), `NoSpeech` and `EmptyTranscript`.

- [ ] **Step 4: Run tests and a live dictation**

Run: `./gradlew :core:desktopTest :desktop:test`, then:
```bash
ffmpeg -loglevel error -y -i core/src/desktopTest/resources/audio/en_short.m4a -ac 1 -ar 16000 -c:a pcm_s16le /tmp/en_short.wav
OPENROUTER_API_KEY=$(grep -E '^OPENROUTER_API_KEY=' .env | cut -d= -f2-) ./gradlew -q :desktop:run --args="--dictate /tmp/en_short.wav"
```
Expected: tests pass; the run prints a cleaned line like `Can we meet at 6 tomorrow?` and a cost near $0.0004. Never print the key.

- [ ] **Step 5: Commit**

```bash
git add -A core desktop settings.gradle.kts .github
git commit -m "feat(desktop): dictate a WAV file with the shared core"
```

### Task 6: Phase 1 verification gate

No code unless a check fails (then fix in a separate commit with its own test).

- [ ] **Step 1:** `./gradlew check :app:assembleRelease && git diff --exit-code main -- core/schemas` → BUILD SUCCESSFUL, no schema diff.
- [ ] **Step 2: Upgrade check on the Pixel (with the user).** Check what is installed: `adb shell dumpsys package io.github.agopalareddy.umm | grep -E "versionName|versionCode"`. Build with the same signing key as the installed copy and a higher version code: the release key (`~/.config/umm/keystore.properties`) via `./gradlew :app:assembleRelease -PversionName=1.2.1-kmp -PversionCode=<installed+1>` if a release build is installed, otherwise `./gradlew :app:assembleDebug -PversionCode=<installed+1>`. Ask the user, then `adb install -r <apk>`. Never uninstall (it wipes the data under test). Then check: History shows the old entries, Home stats unchanged, Settings values unchanged, Account shows the key connected, one keyboard dictation and one floating-button dictation succeed. Verify `mCurrentFocus` is Umm before any adb tap.
- [ ] **Step 3:** Record results in the PR description draft; ask the user before pushing `feat/kmp-core`.

---

## Phase 2: shared UI (`feat/kmp-ui`, branched from `feat/kmp-core`)

### Task 7: `ui` module with theme, charts and the `Platform` seam

**Files:**
- Modify: `gradle/libs.versions.toml`, root `build.gradle.kts`, `settings.gradle.kts` (`include(":ui")`), `app/build.gradle.kts`, `.github/workflows/ci.yml` (add `:ui:desktopTest`)
- Create: `ui/build.gradle.kts`; `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/ui/{Platform.kt, UmmServices.kt}`; `ui/src/androidMain/kotlin/io/github/agopalareddy/umm/ui/AndroidPlatform.kt`
- Move to `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/ui/`: `ui/UmmTheme.kt`, `ui/Logo.kt`, `ui/VoiceLevel.kt`, `ui/VoiceOrb.kt`, `ui/charts/*`
- Move tests to `ui/src/desktopTest/...`: `ui/VoiceLevelTest.kt`, `ui/charts/*Test.kt`
- Modify imports in `app` (IME, bubble, settings) only as needed

**Before any change (baseline):** with the current `feat/kmp-core` build installed, take screenshots of every shared screen (Home, Settings and each page, Account, Stats, Categories, Models, History) in light and dark theme on the Pixel into `.superpowers/sdd/2026-10-05-kmp-ui/before/`, and record `stat -c %s` of `app-release.apk`. Verify `mCurrentFocus` before adb taps.

**Interfaces:**
- Produces:
  - `interface Platform { fun openUrl(url: String); fun composeEmail(to: String, subject: String, body: String): Boolean; fun copyText(text: String); fun showMessage(text: String); fun installedApps(): List<AppEntry>; fun is24HourClock(): Boolean; fun animationsEnabled(): Boolean; fun dynamicColors(dark: Boolean): ColorScheme? }`
  - `data class AppEntry(val id: String, val label: String)`
  - `class UmmServices(val settings: SettingsRepository, val apiKeyStore: ApiKeyStore, val history: HistoryRepository, val stats: StatsRepository, val categories: CategoryRepository, val dataPolicy: DataPolicyRepository, val modelCatalog: ModelCatalog, val recommendations: RecommendationRepository, val platform: Platform)` plus `val LocalUmm = staticCompositionLocalOf<UmmServices> { error("No UmmServices") }`
  - `class AndroidPlatform(context: Context) : Platform` implementing today's behavior (Intents, `ClipboardManager`, `Toast`, launcher query, `DateFormat.is24HourFormat`, `ANIMATOR_DURATION_SCALE`, `dynamicDarkColorScheme`/`dynamicLightColorScheme` on API 31+ else null)
  - `UmmTheme` reads settings and dynamic colors from `LocalUmm.current`; `Motion.kt` reads `LocalUmm.current.platform.animationsEnabled()`.

- [ ] **Step 1: Catalog and module setup.** Add `composeMultiplatform = "1.12.1"`, `jbNavigation = "2.9.2"`, `jbLifecycle = "2.11.0"`, `jbMaterial3 = "1.9.0"`, `jbMaterialIcons = "1.7.3"`; plugin `compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }`; libraries `jb-compose-runtime`, `jb-compose-foundation`, `jb-compose-ui` (`org.jetbrains.compose.{runtime,foundation,ui}:…` at `composeMultiplatform`), `jb-material3`, `jb-material-icons-extended`, `jb-navigation-compose`, `jb-lifecycle-runtime-compose`, `kotlinx-coroutines-swing`. `ui/build.gradle.kts`: plugins `kotlin-multiplatform`, `android-kotlin-multiplatform-library`, `compose-multiplatform`, `kotlin-compose`; targets as in `core` (namespace `io.github.agopalareddy.umm.ui`); `commonMain` depends on `project(":core")` and the `jb-*` libraries; `desktopTest` on `junit`. `app` depends on `project(":ui")`.
- [ ] **Step 2: Move the files and tests, add the interfaces above, and provide `LocalUmm` at every Compose host in `app`: `MainActivity`, the IME's `ComposeView` and the bubble's `ComposeView` (find them with `grep -rn "setContent\|ComposeView" app/src/main`)** (each builds `UmmServices` from `graph` with `AndroidPlatform(context)`; add `fun AppGraph.services(context: Context): UmmServices`).
- [ ] **Step 3: Run tests and build**

Run: `./gradlew :ui:desktopTest :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; moved chart and voice-level tests pass on the JVM.
- [ ] **Step 4: Commit**

```bash
git add -A gradle build.gradle.kts settings.gradle.kts ui app .github
git commit -m "feat(ui): add shared Compose module with theme and charts"
```

### Task 8: Dashboard and Stats page in `ui`

**Files:**
- Move to `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/dashboard/`: all of `app/.../settings/dashboard/*`; and `settings/StatsPage.kt` to `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/`
- Move tests: `DashboardSeriesTest`, `DashboardTextTest`, `LayoutEditTest` → `ui/src/desktopTest/...`

**Interfaces:**
- Consumes: `LocalUmm`, `Platform.is24HourClock()`.
- Produces: same composable signatures as today, minus any `Context` parameters.

- [ ] **Step 1:** Replace `DateFormat.is24HourFormat(LocalContext.current)` in `CardsHabits.kt` with `LocalUmm.current.platform.is24HourClock()`, and every `LocalContext.current.graph.X` with `LocalUmm.current.X`. `SampleData` stays debug-only: keep it out of release builds the same way it is today (check how the release build strips it before moving and preserve that).
- [ ] **Step 2:** `./gradlew :ui:desktopTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** `refactor(ui): share the stats dashboard`.

### Task 9: History, Models and Categories screens in `ui`

**Files:**
- Create: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/ui/RelativeTime.kt`; test `ui/src/desktopTest/kotlin/io/github/agopalareddy/umm/ui/RelativeTimeTest.kt`
- Move to `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/`: `HistoryScreen.kt`, `ModelsScreen.kt`, `CategoriesScreen.kt`, `Common.kt`

**Interfaces:**
- Produces: `fun relativeTime(thenMs: Long, nowMs: Long): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun relativeTime_buckets() {
    val now = 1_800_000_000_000L
    assertEquals("Just now", relativeTime(now - 30_000, now))
    assertEquals("5 minutes ago", relativeTime(now - 5 * 60_000, now))
    assertEquals("1 minute ago", relativeTime(now - 60_000, now))
    assertEquals("3 hours ago", relativeTime(now - 3 * 3_600_000, now))
    assertEquals("Yesterday", relativeTime(now - 30 * 3_600_000, now))
    assertEquals("4 days ago", relativeTime(now - 4 * 86_400_000L, now))
}
```

Older than 7 days: the date in `MMM d` (same year) or `MMM d, yyyy` (earlier years), system zone.
- [ ] **Step 2:** `./gradlew :ui:desktopTest --tests '*RelativeTimeTest*'` → FAIL (unresolved).
- [ ] **Step 3: Implement and move.** History uses `relativeTime`, `platform.copyText`, `platform.showMessage("Copied")`, `platform.composeEmail(SUPPORT_EMAIL, …)` with the same "No email app found. Write to $SUPPORT_EMAIL." fallback message. Models uses `platform.openUrl`. Categories lists `platform.installedApps()` (`AppEntry.id` is the package name on Android).
- [ ] **Step 4:** `./gradlew :ui:desktopTest :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `refactor(ui): share history, models and categories`.

### Task 10: Settings, Home and the shared `UmmNavHost`

**Files:**
- Move to `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/`: `SettingsScreen.kt` (all pages; `BubblePage.kt` stays in `app`), `HomeScreen.kt`
- Create: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/UmmNavHost.kt` (holds `Routes`, moved from `MainActivity.kt`)
- Modify: `app/.../settings/MainActivity.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class SettingsEntry(val title: String, val subtitle: String, val route: String)
  data class HomeSetup(val complete: Boolean, val onSetup: () -> Unit, val switchKeyboard: (() -> Unit)?)
  @Composable fun UmmNavHost(
      startRoute: String,
      home: HomeSetup,
      onConnect: () -> Unit,
      extraSettings: List<SettingsEntry> = emptyList(),
      platformRoutes: NavGraphBuilder.(NavController) -> Unit = {},
  )
  ```
  `Routes` keeps today's names and values. `UmmNavHost` owns the `NavController` and the shared `composable` entries now in `MainActivity` (Home, Settings, Account, Dictation, Languages, Appearance, Stats, Categories, Models, History).
- `SettingsHome` renders `extraSettings` where the Floating button row sits today. Home shows "Switch keyboard" only when `switchKeyboard != null`. Settings' dynamic-color toggle shows only when `platform.dynamicColors(false) != null`.

- [ ] **Step 1:** Move the screens; replace `Intent(ACTION_VIEW, …)` with `platform.openUrl`, `Build.VERSION` checks with the `dynamicColors` check, `InputMethodManager.showInputMethodPicker()` with `home.switchKeyboard`.
- [ ] **Step 2:** `MainActivity.App()` calls `UmmNavHost(startRoute = if (status.complete) Routes.HOME else Routes.ONBOARDING, home = HomeSetup(status.complete, { nav to onboarding }, { showInputMethodPicker() }), onConnect = { SignInLauncher.start(this) }, extraSettings = listOf(<Floating button row, same title and subtitle as today>), platformRoutes = { nav -> composable(Routes.ONBOARDING) { … } ; composable(Routes.BUBBLE) { BubblePage(…) } })`, keeping the `bubblePageRequested` deep link working.
- [ ] **Step 3:** `./gradlew :ui:desktopTest :app:testDebugUnitTest :app:assembleDebug && ! grep -rnE "^import android\.|LocalContext" ui/src/commonMain` → BUILD SUCCESSFUL, no grep output.
- [ ] **Step 4: Commit** `refactor(ui): share settings, home and navigation`.

### Task 11: Desktop window

**Files:**
- Modify: `desktop/build.gradle.kts`, `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/{Main.kt, DesktopGraph.kt}`
- Create: `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/DesktopPlatform.kt`; test `desktop/src/test/kotlin/io/github/agopalareddy/umm/desktop/DesktopPlatformTest.kt`

**Interfaces:**
- Consumes: `UmmNavHost`, `UmmServices`, `LocalUmm`, `Platform`.
- Produces: `class DesktopPlatform(snackbar: SnackbarHostState, scope: CoroutineScope, launcher: (List<String>) -> Unit = { ProcessBuilder(it).start() }) : Platform` — `openUrl` runs `xdg-open <url>`; `composeEmail` runs `xdg-open mailto:…` with URL-encoded subject and body and returns true; `copyText` uses the AWT system clipboard; `showMessage` shows a snackbar; `installedApps()` = empty; `is24HourClock()` from the default locale's short time pattern (no `a`); `animationsEnabled()` = true; `dynamicColors` = null. `DesktopGraph.services(platform)` returns `UmmServices`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun openUrl_and_composeEmail_useXdgOpen() {
    val calls = mutableListOf<List<String>>()
    val p = DesktopPlatform(SnackbarHostState(), TestScope(), launcher = { calls += it })
    p.openUrl("https://example.com")
    p.composeEmail("agr@agreddy.com", "Hi there", "Body & more")
    assertEquals(listOf("xdg-open", "https://example.com"), calls[0])
    assertEquals(listOf("xdg-open", "mailto:agr@agreddy.com?subject=Hi%20there&body=Body%20%26%20more"), calls[1])
}
```
- [ ] **Step 2:** `./gradlew :desktop:test --tests '*DesktopPlatformTest*'` → FAIL.
- [ ] **Step 3: Implement.** `desktop/build.gradle.kts` switches to `kotlin("jvm")` + `compose-multiplatform` + `kotlin-compose`, depends on `project(":ui")` and `compose.desktop.currentOs`, `compose.desktop { application { mainClass = "io.github.agopalareddy.umm.desktop.MainKt" } }`, and `kotlinx-coroutines-swing`. `Main.kt`: `--dictate` behaves as in Task 5; otherwise `application { Window(title = "Umm", state = rememberWindowState(width = 420.dp, height = 860.dp)) { … Scaffold(snackbarHost) { CompositionLocalProvider(LocalUmm provides services) { UmmTheme { UmmNavHost(startRoute = Routes.HOME, home = HomeSetup(complete = graph.apiKeyStore.get() != null, onSetup = {}, switchKeyboard = null), onConnect = { platform.openUrl("https://openrouter.ai/keys") }) } } } } }`.
- [ ] **Step 4:** `./gradlew :desktop:test` → PASS. Then run a `--dictate` (Task 5 Step 4) followed by `./gradlew :desktop:run` and open every shared screen; History must show the dictation and Stats its numbers. Screenshot the window for the user.
- [ ] **Step 5: Commit** `feat(desktop): show the shared screens in a window`.

### Task 12: Phase 2 verification gate

- [ ] **Step 1:** `./gradlew check :app:assembleRelease` → BUILD SUCCESSFUL. Record the new `app-release.apk` size next to the baseline; growth over 10% needs an explanation before asking to merge.
- [ ] **Step 2:** Install the new build on the Pixel (same signing path as Task 6, after asking the user) and retake the baseline screenshots into `.superpowers/sdd/2026-10-05-kmp-ui/after/`. Compare pairwise; any visible difference is a bug to fix with its own commit. Also run one keyboard and one floating-button dictation.
- [ ] **Step 3:** Ask the user before pushing `feat/kmp-ui` or opening PRs.
