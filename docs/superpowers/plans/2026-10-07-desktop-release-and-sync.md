# Desktop Parity, Linux Packaging and Device Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Linux app look native and installable from a release, and sync key, settings, history and stats between a user's devices with no account or server.

**Architecture:** Three phases, three branches, three PRs to `main`: parity (`feat/desktop-parity`, Tasks 1–4), packaging (`feat/linux-packaging`, Tasks 5–11), sync (`feat/device-sync`, Tasks 12–23). Sync is a per-device change log in `core` (Room tables + merge rules) carried by a new KMP `sync` module over mDNS/TCP and Nostr relays, encrypted with a shared group key.

**Tech Stack:** Kotlin 2.4 / JVM 17, Compose Multiplatform 1.12, Room 2.8 KMP, dbus-java 5.2, OkHttp 5.5, kotlinx.serialization; new: MaterialKolor, JmDNS, secp256k1-kmp (ACINQ), ZXing core, WorkManager, Google code scanner.

**Spec:** `docs/superpowers/specs/2026-10-07-desktop-release-and-sync-design.md`

## Global Constraints

- App ID `io.github.agopalareddy.Umm` everywhere (desktop file, Flatpak, D-Bus name, autostart).
- Conventional Commits, subject ≤ 50 chars, no AI co-author trailers. Native Edit/Write for file changes.
- Gradle always with `-Pkotlin.compiler.execution.strategy=in-process`; don't run the VM during a Gradle build.
- Never print the `.env` OpenRouter key. Never push, open a PR or merge without the user's OK. Ask before large downloads (Flatpak runtimes, ISOs).
- Phone: never change system settings via adb, never uninstall Umm, don't dump other apps' UI.
- UI copy: terse labels, whole-row toggles, explicit status (see the `umm-ui-preferences` memory).
- Sync crypto: AES-256-GCM, HKDF-SHA256, secp256k1 (BIP-340 Schnorr for Nostr, ECDH for pairing and key rotation).
- Limits: history 50 per origin device; ops kept 90 days; Nostr event content < 32 KB, expiration 30 days; LAN frame ≤ 1 MB; pairing code valid 10 minutes, single use; old-epoch grace 24 hours.
- Full test command (CI's): `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:testAndroidHostTest :core:desktopTest :ui:desktopTest :desktop:test :linux:test :app:testDebugUnitTest` (+ `:sync:desktopTest` from Task 15 on).

## Review Focus

1. A v1.2.0 database holding a PENDING row with an audio file and stats with sample data upgrades to v3 with every row intact and stamped with this device → `MigrationTest.pendingRowWithAudioSurvives` (Task 12).
2. The same op arriving twice, once over LAN and once via a relay, minutes apart → applied once, no duplicate history row → `OpApplierTest.sameOpFromTwoTransportsAppliesOnce` (Task 14).
3. A phone whose clock ran a day fast and was then corrected keeps winning nothing it shouldn't: a later edit from another device still wins → `HybridClockTest.futureRemoteKeepsMonotonic` and `OpApplierTest.correctedFastClockLosesToLaterEdit` (Tasks 13–14).
4. Malformed `.desktop` files (no `Name`, bad UTF-8, `Name[de]` only, a directory named `x.desktop`) → skipped without crashing the Categories page → `DesktopEntriesTest.malformedEntriesAreSkipped` (Task 3).
5. A removed device keeps publishing to relays after rotation → its new events are dropped once the 24 h grace ends → `KeyRotationTest.removedDeviceIsIgnoredAfterGrace` (Task 18).

---

# Phase 1 — desktop parity (branch `feat/desktop-parity`)

### Task 1: Sidebar frame and desktop pages

**Files:**
- Modify: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/Common.kt` (Page back arrow)
- Modify: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/UmmNavHost.kt` (`Routes.ABOUT`, `HomeSetup` copy)
- Modify: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/HomeScreen.kt`
- Create: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/AboutPage.kt`
- Create: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/SidebarFrame.kt`
- Modify: `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/DesktopApp.kt`
- Test: `ui/src/desktopTest/kotlin/io/github/agopalareddy/umm/settings/SidebarTest.kt`

**Interfaces:**
- Produces: `val LocalShowBack = staticCompositionLocalOf { true }` (Page draws its back arrow only when true); `data class SidebarItem(val icon: ImageVector, val label: String, val route: String)`; `fun sidebarSelection(items: List<SidebarItem>, currentRoute: String?): String?`; `@Composable fun SidebarFrame(items: List<SidebarItem>, currentRoute: String?, onSelect: (String) -> Unit, content: @Composable () -> Unit)`; `HomeSetup(complete, onSetup, switchKeyboard, intro: String = ANDROID_INTRO, tryPlaceholder: String = ANDROID_TRY_PLACEHOLDER)`; `Routes.ABOUT = "about"`; `@Composable fun AboutPage(onBack: (() -> Unit)?)`.

- [ ] **Step 1: Write the failing test** — `SidebarTest`:
  - `selectsExactRoute`: items HOME, STATS, HISTORY; `sidebarSelection(items, Routes.HISTORY) == Routes.HISTORY`.
  - `subRouteSelectsParent`: an item with route `settings/desktop` is selected for current route `settings/desktop/shortcut`.
  - `unknownRouteSelectsNothing`: `sidebarSelection(items, "setup") == null`; and `null` route → `null`.
- [ ] **Step 2: Run** `./gradlew -Pkotlin.compiler.execution.strategy=in-process :ui:desktopTest --tests '*SidebarTest*'` — expect compile failure (`sidebarSelection` undefined).
- [ ] **Step 3: Implement.** `sidebarSelection` picks the longest item route that equals or prefixes (`route + "/"`) the current route. `SidebarFrame` is a `Row`: a `NavigationRail` (labels shown when the window is ≥ 720 dp wide via `BoxWithConstraints`, icons only below), then a `Box` filling the rest whose content is `widthIn(max = 720.dp)` centered; provides `LocalShowBack provides false`. `AboutPage` rows: version (`platform.appVersion()`), "Source on GitHub", "Privacy policy" (the two URLs SettingsHome uses today). Home uses `home.intro`/`home.tryPlaceholder`; desktop passes intro "Press your shortcut in any text field and start talking. It stops when you do." and placeholder "Click here, press your shortcut, and talk". Desktop: window `rememberWindowState(width = 1040.dp, height = 720.dp)`, `window.minimumSize = Dimension(720, 520)`; wrap `UmmNavHost` in `SidebarFrame` with items Home, Stats (`Routes.STATS`), History, Categories, Models, Dictation, Languages, Appearance, Account, Desktop (`DesktopRoutes.SETTINGS`), About; `onSelect` navigates with `popUpTo(Routes.HOME)` and `launchSingleTop = true`. Setup keeps its full-window look: hide the rail while on `DesktopRoutes.SETUP`. Add `composable(Routes.ABOUT) { AboutPage(back) }` to `UmmNavHost`.
- [ ] **Step 4: Run** the Step 2 command, then `:desktop:test` — PASS.
- [ ] **Step 5: Run the app** (`./gradlew -Pkotlin.compiler.execution.strategy=in-process :desktop:run`), click every sidebar item, resize below 720 px; confirm no back arrows on desktop pages and Setup has no rail. Android is untouched: `:app:assembleDebug` builds.
- [ ] **Step 6: Commit** `feat(desktop): add a sidebar layout to the window`.

### Task 2: System accent color and light/dark

**Files:**
- Create: `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/PortalAppearance.kt`
- Modify: `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/portal/PortalInterfaces.kt` (Settings interface)
- Modify: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/ui/Platform.kt`, `UmmTheme.kt`, `settings/SettingsScreen.kt` (Appearance note)
- Modify: `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/DesktopPlatform.kt`, `DesktopEngine.kt` (owns the appearance flow)
- Modify: `gradle/libs.versions.toml`, `desktop/build.gradle.kts` (MaterialKolor)
- Test: `linux/src/test/kotlin/io/github/agopalareddy/umm/linux/PortalAppearanceTest.kt`

**Interfaces:**
- Produces: `data class SystemAppearance(val dark: Boolean?, val accentArgb: Int?)` in `ui` (`Platform.kt`); `Platform.systemAppearance(): StateFlow<SystemAppearance>? = null` (default method); in `linux`: `object AppearanceMapping { fun dark(colorScheme: Int): Boolean?; fun accent(r: Double, g: Double, b: Double): Int? }` and `class PortalAppearance(portal: Portal) : AutoCloseable { val appearance: StateFlow<SystemAppearance> }` — `linux` gets its own `data class Appearance(dark, accentArgb)` to avoid depending on `ui`; desktop maps it.

- [ ] **Step 1: Write the failing test** — `PortalAppearanceTest`:
  - `colorSchemeMapping`: `dark(0) == null`, `dark(1) == true`, `dark(2) == false`, `dark(7) == null`.
  - `accentMapping`: `accent(1.0, 0.0, 0.0) == 0xFFFF0000.toInt()`; `accent(0.2, 0.4, 0.6) == 0xFF336699.toInt()`; `accent(-1.0, 0.5, 0.5) == null`; `accent(1.2, 0.0, 0.0) == null`.
- [ ] **Step 2: Run** `./gradlew -Pkotlin.compiler.execution.strategy=in-process :linux:test --tests '*PortalAppearanceTest*'` — FAIL (unresolved).
- [ ] **Step 3: Implement.** `Settings` D-Bus interface: `ReadOne(namespace: String, key: String): Variant<*>`; signal `SettingChanged(namespace, key, value)` via `portal.onSignal("org.freedesktop.portal.Settings", "SettingChanged")`. Namespace `org.freedesktop.appearance`, keys `color-scheme` (uint32) and `accent-color` (struct of three doubles). Missing portal or key → `Appearance(null, null)`. `UmmTheme`: collect `platform.systemAppearance()` when non-null; `ThemeMode.SYSTEM` uses `appearance.dark ?: isSystemInDarkTheme()`; with `dynamicColor` on, `DesktopPlatform.dynamicColors(dark)` returns `dynamicColorScheme(Color(accent), isDark = dark, isAmoled = false)` from MaterialKolor when the current accent is non-null, else null. Appearance page: show "Needs Android 12 or newer" only when `systemAppearance() == null` and `dynamicColors(false) == null`. Add MaterialKolor's latest release to the catalog (check Maven Central).
- [ ] **Step 4: Run** the Step 2 command and `:desktop:test` — PASS.
- [ ] **Step 5: Verify live on KDE:** run the app, change the accent and the light/dark scheme in System Settings; the window follows within a second without restarting. With "Use Dynamic Theme" off, Umm purple returns.
- [ ] **Step 6: Commit** `feat(desktop): follow the system accent and theme`.

### Task 3: Desktop app picker and seeds

**Files:**
- Create: `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/DesktopEntries.kt`
- Modify: `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/DesktopPlatform.kt`
- Modify: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/ui/Platform.kt`, `settings/CategoriesScreen.kt`
- Modify: `core/src/commonMain/kotlin/io/github/agopalareddy/umm/core/data/Seeds.kt`
- Test: `linux/src/test/kotlin/io/github/agopalareddy/umm/linux/DesktopEntriesTest.kt`, `core/src/desktopTest/.../data/SeedTest.kt`

**Interfaces:**
- Produces: `data class DesktopEntry(val id: String, val name: String)`; `class DesktopEntries(dirs: List<File>, locale: Locale = Locale.getDefault()) { fun all(): List<DesktopEntry>; fun label(id: String): String? }` (index built once, lazily); `companion fun forEnvironment(env: Map<String, String>, home: File): DesktopEntries` (`$XDG_DATA_HOME` or `~/.local/share`, then each `$XDG_DATA_DIRS` entry or `/usr/local/share:/usr/share`, each + `/applications`); `Platform.perAppLevelsNote(): String? = null`.

- [ ] **Step 1: Write the failing tests** — `DesktopEntriesTest` (fixtures written to a `TemporaryFolder`):
  - `readsNameAndId`: `org.kde.kate.desktop` with `Name=Kate` → `DesktopEntry("org.kde.kate", "Kate")`.
  - `subdirectoryIdUsesDashes`: `applications/kde4/foo.desktop` → id `kde4-foo`.
  - `skipsHiddenNoDisplayAndNonApplications`: `NoDisplay=true`, `Hidden=true`, `Type=Link` all absent from `all()`.
  - `skipsUmmItself`: `io.github.agopalareddy.Umm.desktop` absent.
  - `earlierDirectoryWins`: same id in user and system dirs → user's name.
  - `localizedNameWins`: `Name=Files`, `Name[de]=Dateien`, locale `de_DE` → "Dateien"; `en_US` → "Files".
  - `malformedEntriesAreSkipped` (Review Focus 4): no `Name`, invalid UTF-8 bytes, only `Name[de]` under `en_US`, a directory named `x.desktop` → none returned, no exception.
  - `sortedByName`: `all()` sorted case-insensitively by name.
  - `SeedTest.desktopAppsAreSeeded`: a fresh in-memory DB has `org.kde.kate` → NOTES and `org.mozilla.Thunderbird` → EMAIL.
- [ ] **Step 2: Run** `./gradlew -Pkotlin.compiler.execution.strategy=in-process :linux:test --tests '*DesktopEntriesTest*' :core:desktopTest --tests '*SeedTest*'` — FAIL.
- [ ] **Step 3: Implement.** Parse only the `[Desktop Entry]` group; read with a strict UTF-8 decoder and skip on failure. Localized key order: `Name[ll_CC]`, `Name[ll]`, `Name`. `DesktopPlatform.installedApps()` maps entries to `AppEntry(id, name)`; `appLabel(id)` returns `label(id) ?: id`. `perAppLevelsNote()` on desktop returns "Per-app levels need KDE Plasma; other desktops use the default level." when `XDG_CURRENT_DESKTOP` doesn't contain `KDE`, else null; CategoriesScreen shows it as one `bodySmall` line under the title when non-null. Seeds: add the IDs listed in spec §3 "Per-app levels".
- [ ] **Step 4: Run** Step 2 command — PASS; then the full test command.
- [ ] **Step 5: Verify on KDE:** assign Kate to Notes (Formatted) on the Categories page, dictate into Kate, and check History shows "Kate" with level Formatted.
- [ ] **Step 6: Commit** `feat(desktop): list desktop apps for categories`.

### Task 4: Phase 1 check and PR

- [ ] **Step 1:** Full test command and `./gradlew -Pkotlin.compiler.execution.strategy=in-process assembleDebug` — PASS.
- [ ] **Step 2:** KDE screenshots (light and dark, accent changed) of Home, Stats, Categories, Desktop. Then (no Gradle running) the GNOME VM from `~/vm-test`: install with `installDev`, screenshot the same pages, confirm the GNOME note on Categories and that the GNOME accent applies.
- [ ] **Step 3:** Report to the user with screenshots; on their OK push `feat/desktop-parity`, open the PR (body ends with the Claude Code line, no co-author trailer), and merge when they approve. Then `git switch main && git pull && git switch -c feat/linux-packaging`.

---

# Phase 2 — packaging (branch `feat/linux-packaging`)

### Task 5: Remove the `umm://` path and tidy the dev install

**Files:**
- Delete: `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/UmmLink.kt` and its test
- Modify: `linux/.../SingleInstance.kt` (drop `OpenUrl`, `Message`, `messageFor`), `linux/src/test/.../SingleInstanceTest.kt` (delete)
- Modify: `desktop/.../Main.kt`, `desktop/.../SignIn.kt` (callers), `desktop/build.gradle.kts`
- Move: `desktop/packaging/*` → `packaging/linux/` (`git mv`); drop `MimeType=` and `%u` from the desktop file.

**Interfaces:**
- Produces: `SingleInstance.claim(onActivate: () -> Unit): Boolean`, `SingleInstance.forward()`; packaging assets live in `packaging/linux/`.

- [ ] **Step 1:** Delete `UmmLink` and the URL forwarding; `UmmControl` keeps only `Activate()`. Remove the `xdg-mime` call. In `installDev`, write `Exec="<launcher>"` quoted (the desktop-entry spec allows double quotes) and point at `packaging/linux/`.
- [ ] **Step 2: Run** the full test command — PASS. `grep -rn "umm://" --include=*.kt --include=*.in --include=*.kts .` returns nothing outside `app/` (Android still uses its own `umm://` callback; leave it).
- [ ] **Step 3:** `installDev` into a home path containing a space (`XDG_DATA_HOME="$scratch/a b"`), then `desktop-file-validate` the written file — no errors.
- [ ] **Step 4: Commit** `refactor(linux): drop the unused umm:// handler`.

### Task 6: Versioned, trimmed runtime and self-check

**Files:**
- Modify: `desktop/build.gradle.kts`, `desktop/.../Main.kt`
- Create: `desktop/src/main/kotlin/io/github/agopalareddy/umm/desktop/SelfCheckCommand.kt`
- Test: `desktop/src/test/kotlin/io/github/agopalareddy/umm/desktop/SelfCheckTest.kt`

**Interfaces:**
- Produces: `object SelfCheck { fun run(): List<String> }` (returns failures, empty = OK) and the `--self-check` flag (prints `ok` and exits 0, or prints failures and exits 1). CI and Task 10 call `<image>/bin/Umm --self-check`.

- [ ] **Step 1: Write the failing test** — `SelfCheckTest.passesInTheTestJvm`: `SelfCheck.run()` is empty.
- [ ] **Step 2: Run** `:desktop:test --tests '*SelfCheckTest*'` — FAIL.
- [ ] **Step 3: Implement** `SelfCheck.run()`: each check in `runCatching`, named on failure — `AudioSystem.getMixerInfo()`; opening an in-memory Umm database and reading categories; AES-GCM encrypt/decrypt and an EC key pair; an `SSLContext.getInstance("TLS")` init plus OkHttp client build; loading dbus-java's transport provider class via `ServiceLoader` (no connection); a Compose `ImageBitmap` allocation. Version: `val appVersionName = providers.gradleProperty("versionName").getOrElse("0.1.0-dev")`; `packageVersion` = the part before `-` (jpackage needs digits only); write `Implementation-Version` to the jar manifest so `appVersion()` reports it. Replace `includeAllModules` with `modules(...)`: start from `./gradlew :desktop:suggestRuntimeModules`, add `java.desktop`, `jdk.crypto.ec`, `jdk.unsupported`, `java.naming` (JmDNS), `jdk.charsets`, and iterate until `--self-check` passes on the built image.
- [ ] **Step 4: Run** `:desktop:test`; then `:desktop:createDistributable -PversionName=9.9.9` and `desktop/build/compose/binaries/main/app/Umm/bin/Umm --self-check` prints `ok`; `du -sh` of the image is under 100 MB; About shows 9.9.9.
- [ ] **Step 5: Investigate "pure virtual method called"** at launch: reproduce with the built image, check whether it appears with `-Dsun.java2d.opengl=false`, with Compose's `skiko.renderApi=SOFTWARE`, and at exit only. Fix if it's ours (e.g. a Skia object closed after its window); otherwise add a one-line comment in `Main.kt` naming the JDK/Skiko issue. Record the finding in the commit body.
- [ ] **Step 6: Commit** `build(desktop): version and trim the runtime image`.

### Task 7: Tarball, .deb and .rpm

**Files:**
- Create: `packaging/linux/install.sh`, `packaging/linux/uninstall.sh`, `packaging/linux/io.github.agopalareddy.Umm.metainfo.xml`, `packaging/linux/io.github.agopalareddy.Umm.png` (256 px, rendered once from the SVG with `rsvg-convert`)
- Create: `packaging/linux/nfpm.yaml`
- Modify: `desktop/build.gradle.kts` (`packageLinuxTarball`, `packageLinuxDeb`, `packageLinuxRpm` tasks)

**Interfaces:**
- Produces: `./gradlew :desktop:packageLinuxTarball :desktop:packageLinuxDeb :desktop:packageLinuxRpm -PversionName=X.Y.Z` → `desktop/build/dist/umm-X.Y.Z-linux-x86_64.tar.gz`, `umm_X.Y.Z_amd64.deb`, `umm-X.Y.Z-1.x86_64.rpm`. Tarball layout: `umm-X.Y.Z/` with `Umm/` (app image), `install.sh`, `uninstall.sh`, `share/` (desktop file template, SVG, PNG, metainfo).

Deviation from spec §4: jpackage names its desktop file `<package>-<launcher>.desktop` and can't be told otherwise, but the portals need `io.github.agopalareddy.Umm.desktop`. So `.deb`/`.rpm` are built with nfpm from the same app image instead of jpackage's installers. nfpm is one static binary: CI downloads a pinned release; locally, ask the user before downloading it (about 10 MB) into the scratchpad.

- [ ] **Step 1: Implement.** `install.sh [--system]`: user mode copies to `~/.local/lib/umm`, links `~/.local/bin/umm`, writes the desktop file (`Exec` quoted absolute launcher) and icon under `~/.local/share`, metainfo under `~/.local/share/metainfo`; `--system` uses `/opt/umm`, `/usr/local/bin/umm`, `/usr/local/share` and requires root. Both run `update-desktop-database` and `gtk-update-icon-cache` when present, ignoring failures. `uninstall.sh [--system]` removes exactly those paths and the autostart entry. `set -euo pipefail`, paths quoted. `nfpm.yaml`: name `umm`, version from env, maintainer `agr@agreddy.com`, license `PolyForm-Noncommercial-1.0.0`, contents: app image → `/opt/umm`, symlink `/usr/bin/umm`, desktop file (`Exec=/opt/umm/bin/Umm`) → `/usr/share/applications/io.github.agopalareddy.Umm.desktop`, SVG → `/usr/share/icons/hicolor/scalable/apps/`, metainfo → `/usr/share/metainfo/`; recommends `xdg-desktop-portal`.
- [ ] **Step 2: Verify locally:** `shellcheck packaging/linux/*.sh` clean; `appstreamcli validate --no-net packaging/linux/*.metainfo.xml` passes; `desktop-file-validate` on the packaged desktop file; `bsdtar -tf` on the `.deb`'s `data.tar.*` and `rpm -qlp` (if `rpm` is installed; else checked in CI) list `/opt/umm/bin/Umm`, the desktop file and icons; tarball user install into a scratch `HOME` then uninstall leaves nothing behind (`find "$HOME" -newer marker`).
- [ ] **Step 3: Commit** `build(linux): package a tarball, deb and rpm`.

### Task 8: AUR PKGBUILD

**Files:**
- Create: `packaging/aur/umm-bin/PKGBUILD`, `packaging/aur/umm-bin/.SRCINFO`, `scripts/bump-aur.sh`, `packaging/aur/README.md`

**Interfaces:**
- Consumes: the tarball name and layout from Task 7. Produces: `scripts/bump-aur.sh X.Y.Z [tarball]` updates `pkgver`, resets `pkgrel=1`, sets `sha256sums` (from the given local tarball, or downloaded from the GitHub release), and regenerates `.SRCINFO` with `makepkg --printsrcinfo`.

- [ ] **Step 1: Implement.** `pkgname=umm-bin`, `provides=(umm)`, `conflicts=(umm)`, `arch=(x86_64)`, `license=('LicenseRef-PolyForm-Noncommercial-1.0.0')`, `depends=(xdg-desktop-portal hicolor-icon-theme)`, `optdepends=('xdg-desktop-portal-kde: KDE Plasma' 'xdg-desktop-portal-gnome: GNOME')`, `source` = the release tarball URL; `package()` installs to `/opt/umm`, `/usr/bin/umm` symlink, desktop file, SVG icon, metainfo, and `LICENSE.md` under `/usr/share/licenses/umm-bin/`. README: the three commands to publish once AUR registration reopens (clone `ssh://aur@aur.archlinux.org/umm-bin.git`, copy files, push).
- [ ] **Step 2: Verify:** build a tarball with `-PversionName=0.0.1`, `scripts/bump-aur.sh 0.0.1 desktop/build/dist/umm-0.0.1-linux-x86_64.tar.gz`, then in a scratch copy with `source` pointed at the local file: `makepkg -f` and `namcap umm-bin-*.pkg.tar.zst` (warnings reviewed; none about missing files). Don't install it.
- [ ] **Step 3: Commit** `build(aur): add the umm-bin PKGBUILD`.

### Task 9: Flatpak

**Files:**
- Create: `packaging/flatpak/io.github.agopalareddy.Umm.yml`, `packaging/flatpak/gradle-sources.json`, `scripts/flatpak-gradle-sources.sh`
- Create: `linux/src/main/kotlin/io/github/agopalareddy/umm/linux/BackgroundPortal.kt`
- Modify: `linux/.../Autostart.kt`, `desktop/...` (start-at-login wiring)
- Test: `linux/src/test/kotlin/io/github/agopalareddy/umm/linux/AutostartTest.kt`

**Interfaces:**
- Produces: `interface LoginStart { fun isEnabled(): Boolean; fun enable(exec: String); fun disable() }` implemented by `Autostart` (file) and `BackgroundPortal(portal)` (`RequestBackground` with `reason`, `autostart`, `commandline = ["umm", "--background"]`); `fun loginStartFor(env: Map<String, String>, home: File, flatpakInfo: File = File("/.flatpak-info"), portal: () -> Portal): LoginStart`. `BackgroundPortal.isEnabled()` reads a persisted flag (the portal can't be queried).

- [ ] **Step 1: Write the failing test** — `AutostartTest.flatpakUsesThePortal`: with a temp file standing in for `/.flatpak-info`, `loginStartFor(...)` is a `BackgroundPortal`; without it, an `Autostart`.
- [ ] **Step 2: Run** `:linux:test --tests '*AutostartTest*'` — FAIL; implement; PASS.
- [ ] **Step 3: Manifest.** As spec §4 "Flatpak": runtime `org.freedesktop.Platform//25.08`, `sdk-extensions: [org.freedesktop.Sdk.Extension.openjdk17]`, module builds with `./gradlew --offline :desktop:createDistributable -PversionName=$VERSION` using `gradle-sources.json` (Maven artifacts placed into a local repo that `settings.gradle.kts` uses when `-Pflatpak` is set), installs the image to `/app/lib/umm`, `/app/bin/umm` wrapper, desktop file, icon, metainfo. `scripts/flatpak-gradle-sources.sh` regenerates the JSON from a clean Gradle cache (resolve all desktop configurations, list every downloaded artifact with URL and sha256).
- [ ] **Step 4: Ask the user** before installing the Flatpak runtime and SDK (several hundred MB). Then `flatpak-builder --user --install --force-clean build-flatpak packaging/flatpak/io.github.agopalareddy.Umm.yml` and `flatpak run io.github.agopalareddy.Umm --self-check` → `ok`. On KDE: tray, hotkey, insertion, keyring, KWin focus, notifications, and "Start at login" (portal dialog) all work; reboot check is the user's.
- [ ] **Step 5: Commit** `build(flatpak): add a Flatpak manifest`.

### Task 10: Release workflow

**Files:**
- Modify: `.github/workflows/release.yml`, `.github/workflows/ci.yml` (desktop `--self-check` on PRs)

- [ ] **Step 1: Implement.** New job `linux` (needs the `version` output; move version calculation into a small first job both use): JDK 17, install `flatpak`, `flatpak-builder`, and a pinned nfpm release (checksum-verified); build the tarball, deb, rpm with `-PversionName`; run `--self-check` on the image; `flatpak-builder --repo=repo` then `flatpak build-bundle repo io.github.agopalareddy.Umm.flatpak io.github.agopalareddy.Umm`; append `sha256sum` lines; upload to the same release (`gh release upload` after the Android job creates it, or have the publish step wait on both jobs and create the release with all assets — prefer the latter). Release notes gain a Linux section: tarball `./install.sh`, deb/rpm, `flatpak install --user ./io.github.agopalareddy.Umm.flatpak`, AUR "coming soon". CI: after desktop tests, `createDistributable` + `--self-check`.
- [ ] **Step 2: Verify:** run the job's build commands locally in order from a clean checkout (`git worktree add` in the scratchpad, no VM running), then `actionlint` on both workflows. The real run happens on the first tag.
- [ ] **Step 3: Commit** `ci: build Linux packages on release`.

### Task 11: Phase 2 check and PR

- [ ] **Step 1:** Full test command — PASS.
- [ ] **Step 2:** In the GNOME VM (no Gradle running): install the tarball (user mode), dictate, uninstall; install the `.rpm` (Fedora), dictate, start at login after a VM reboot; install the `.flatpak`, dictate. Screenshots of each.
- [ ] **Step 3:** Report; on the user's OK push, PR, merge after approval. Optionally a `v1.3.0-beta.1` tag to exercise the release workflow — only if the user asks. Then `git switch -c feat/device-sync` from the updated `main`.

---

# Phase 3 — sync (branch `feat/device-sync`)

### Task 12: Database v3

**Files:**
- Modify: `core/.../data/HistoryEntity.kt`, `HistoryDao.kt`, `HistoryRepository.kt`, `core/.../stats/StatsEntry.kt`, `StatsDao.kt`, `StatsRepository.kt`, `core/.../data/UmmDatabase.kt`, both `UmmDatabase*.kt` builders, `core/.../pipeline/DictationPipeline.kt`, `core/build.gradle.kts` (room-testing for desktopTest)
- Create: `core/.../data/Migrations.kt`, `core/.../sync/SyncMeta.kt`, `core/.../sync/SyncEntities.kt`, `core/.../sync/SyncDao.kt`
- Generated: `core/schemas/.../3.json`
- Test: `core/src/desktopTest/.../data/MigrationTest.kt`, `HistoryRepositoryTest.kt`

**Interfaces:**
- Produces: `HistoryItem` gains `originDevice: String`, `originId: Long`, `appLabel: String?`; `StatsEntry` gains `originDevice: String` (first field) and `appLabel: String?`, primary key `(originDevice, historyId)` (`historyId` is the origin's history ID); `class SyncMeta(db: UmmDatabase) { suspend fun deviceId(): String }` (device ID stored in table `sync_meta(key TEXT PK, value TEXT)`, created by the migration or by `SeedCallback` on a new DB); `internal val MIGRATION_2_3: Migration`; tables `sync_ops(originDevice, seq, hlc, type, payload, PK(originDevice, seq))`, `sync_peers(deviceId PK, marks TEXT, lastSeen INTEGER, transport TEXT)`, `sync_settings_hlc(key PK, hlc INTEGER, origin TEXT)`, `sync_clears(data TEXT, origin TEXT, before INTEGER, PK(data, origin))` (origin `""` = all); `HistoryRepository.createPending(packageName, appLabel, level, script, language, audioPath)`; `StatsRepository.get(historyId)` reads this device's row; `deleteFromHistoryId` limited to this device.

- [ ] **Step 1: Write the failing tests** — `MigrationTest` (Room `MigrationTestHelper` on the bundled driver, schema dir `core/schemas`):
  - `stampsExistingRows`: a v2 DB with two history rows and one stats row → after migration, every row's `originDevice` equals `SyncMeta.deviceId()`, `history.originId == id`, stats `historyId` unchanged, `appLabel` null.
  - `pendingRowWithAudioSurvives` (Review Focus 1): PENDING row with `audioPath` keeps status and path.
  - `createsDeviceIdOnce`: device ID is 26 chars of Crockford base32 and stable across reopen.
  - `addsMissingDesktopSeeds`: v2 DB without `org.kde.kate` gains it as NOTES; an existing user assignment for the same id is untouched.
  - `HistoryRepositoryTest.keepsFiftyPerOrigin`: 50 own rows + 10 rows inserted for origin `B` → own 51st insert evicts the oldest own row only; `B` keeps all 10.
- [ ] **Step 2: Run** `:core:desktopTest --tests '*MigrationTest*' --tests '*HistoryRepositoryTest*'` — FAIL.
- [ ] **Step 3: Implement.** Bump `version = 3`, keep the 1→2 auto-migration, add `MIGRATION_2_3` to both builders (no destructive fallback). Migration SQL: add columns with defaults, create `sync_*` tables, generate the device ID with `SecureRandom` inside the migration, `UPDATE history SET originDevice = ?, originId = id`, rebuild `dictation_stats` with the composite key (create new, copy, drop, rename), create unique index `(originDevice, originId)` on history and indexes on `dictation_stats(originDevice)` and `(createdAt)`, insert missing desktop seeds with `INSERT OR IGNORE`. Own history rows: insert then set `originId = id` in one transaction (`db.useWriterConnection { it.immediateTransaction { … } }`). `beyond(keep)` becomes per origin. The pipeline passes `appLabel` through `DictationRequest` (new field `appLabel: String?`, filled by the Android IME/bubble from `PackageManager` and by desktop from `DesktopEntries`) into history and stats.
- [ ] **Step 4: Run** the full test command — PASS (update existing tests for the new constructor parameters).
- [ ] **Step 5: Commit** `feat(core): add device origin to history and stats`.

### Task 13: Change log and clock

**Files:**
- Create: `core/.../sync/HybridClock.kt`, `SyncOp.kt`, `OpLog.kt`, `SharedSettings.kt`
- Modify: `HistoryRepository.kt`, `StatsRepository.kt`, `CategoryRepository.kt`, `SettingsRepository.kt` (recorder hook), both graphs (construction order)
- Test: `core/src/desktopTest/.../sync/HybridClockTest.kt`, `OpLogTest.kt`, `SharedSettingsTest.kt`

**Interfaces:**
- Consumes: `SyncMeta`, `sync_ops` from Task 12.
- Produces:
  - `class HybridClock(private val wall: () -> Long) { fun tick(): Long; fun observe(remote: Long) }`.
  - `@Serializable sealed interface SyncOp` with `HistoryUpsert(row: SyncedHistory)`, `HistoryDelete(origin: String, originId: Long)`, `StatsUpsert(row: SyncedStats)`, `SettingSet(key: String, value: String)`, `CategorySet(category: Category, level: CleanupLevel?, script: ScriptPreference)`, `AppAssign(packageName: String, category: Category)`, `ClearBefore(data: DataKind, before: Long, origin: String?)`, `MemberUpsert(member: Member)`, `MemberRemove(deviceId: String)`, `KeyRotation(epoch: Int, sealed: Map<String, String>)`; `enum class DataKind { HISTORY, STATS }`; `@Serializable data class Member(deviceId: String, name: String, platform: String, publicKey: String)`; `SyncedHistory`/`SyncedStats` mirror the entities minus local `id` and `audioPath`.
  - `@Serializable data class OpEnvelope(val origin: String, val seq: Long, val hlc: Long, val op: SyncOp)`.
  - `fun interface ChangeRecorder { suspend fun record(op: SyncOp); companion object { val None: ChangeRecorder } }`.
  - `class OpLog(db: UmmDatabase, meta: SyncMeta, clock: HybridClock, active: suspend () -> Boolean) : ChangeRecorder` with `suspend fun since(marks: Map<String, Long>): List<OpEnvelope>`, `suspend fun highWaterMarks(): Map<String, Long>`, `suspend fun oldestSeq(origin: String): Long?`, `suspend fun store(envelope: OpEnvelope): Boolean` (false if already present), `suspend fun prune(nowMs: Long)`.
  - `object SharedSettings { val keys: List<String>; fun read(s: UmmSettings, key: String): String; fun write(s: UmmSettings, key: String, value: String): UmmSettings }` — keys exactly the spec §5 shared list.
  - Repositories take `recorder: ChangeRecorder = ChangeRecorder.None` as the last constructor parameter.

- [ ] **Step 1: Write the failing tests:**
  - `HybridClockTest.monotonicWhenWallStalls`: wall fixed at 1000 → ticks 1000, 1001, 1002.
  - `HybridClockTest.futureRemoteKeepsMonotonic` (Review Focus 3): wall 1000, `observe(90_000_000)` → next tick 90_000_001; wall later corrected to 2000 → next tick 90_000_002.
  - `OpLogTest.recordsOnlyWhenActive`: inactive → `record` stores nothing; active → seq 1, 2 for this origin with increasing hlc.
  - `OpLogTest.sinceReturnsMissingInOrder`: own seq 1..3 and origin B seq 1..2 stored; `since(mapOf(me to 1, B to 2))` → own 2, 3 only.
  - `OpLogTest.deleteCompactsEarlierUpsert`: record `HistoryUpsert(o, 7)` then `HistoryDelete(o, 7)` → `since(emptyMap())` holds only the delete.
  - `OpLogTest.clearCompactsCoveredUpserts`: `ClearBefore(STATS, t, null)` removes stored `StatsUpsert` ops with `createdAt < t`.
  - `OpLogTest.pruneDropsOlderThan90Days`.
  - `SharedSettingsTest.roundTripsEveryKey`: for each key, `write(default, key, read(changed, key))` copies the changed value; `keys` doesn't contain theme, bubble, dashboard or stats keys.
  - `SettingsRepositoryTest.recordsOnlySharedChanges`: changing `defaultLevel` records one `SettingSet("default_level", "FORMATTED")`; changing `themeMode` records nothing.
  - `HistoryRepositoryTest.recordsFinishedItemsNotPending`: `createPending` records nothing; `markDone` records one `HistoryUpsert`; `delete` records `HistoryDelete`; `clearAll` records `ClearBefore(HISTORY, now, null)`.
- [ ] **Step 2: Run** `:core:desktopTest --tests '*sync*' --tests '*SettingsRepositoryTest*' --tests '*HistoryRepositoryTest*'` — FAIL.
- [ ] **Step 3: Implement.** `HybridClock.tick() = max(wall(), last + 1)`, `observe(r)` sets `last = max(last, r)`. Op JSON via a `Json { classDiscriminator = "t" }` with the sealed hierarchy registered. `record` assigns `seq = max(own seq) + 1` and stores the envelope; for `SettingSet` it also upserts `sync_settings_hlc`. Category and app assignment changes record `CategorySet`/`AppAssign`. Stats record on every `record(entry)`. Graph order: database → `SyncMeta` → `HybridClock` → `OpLog(active = { groupStore.state.value != null })` (until Task 15 exists, pass `{ false }`) → repositories.
- [ ] **Step 4: Run** Step 2 command, then the full test command — PASS.
- [ ] **Step 5: Commit** `feat(core): record synced changes in a log`.

### Task 14: Applying ops and snapshots

**Files:**
- Create: `core/.../sync/OpApplier.kt`, `core/.../sync/Snapshot.kt`
- Test: `core/src/desktopTest/.../sync/OpApplierTest.kt`, `SnapshotTest.kt`

**Interfaces:**
- Consumes: Task 13 types.
- Produces: `class OpApplier(db: UmmDatabase, log: OpLog, settings: SettingsRepository, clock: HybridClock, onGroupOp: suspend (OpEnvelope) -> Unit)` with `suspend fun apply(envelopes: List<OpEnvelope>): Int` (number newly applied) and `suspend fun applySnapshot(snapshot: Snapshot)`; `@Serializable data class Snapshot(val from: String, val marks: Map<String, Long>, val history: List<SyncedHistory>, val stats: List<SyncedStats>, val settings: Map<String, Stamped>, val categories: List<CategorySet>, val apps: List<AppAssign>, val clears: List<ClearBefore>, val members: List<Member>)`, `@Serializable data class Stamped(val value: String, val hlc: Long, val origin: String)`; `suspend fun buildSnapshot(db: UmmDatabase, log: OpLog, settings: SettingsRepository, members: List<Member>): Snapshot`. Member/rotation ops are passed to `onGroupOp` (Task 18 handles them).

- [ ] **Step 1: Write the failing tests** (two in-memory DBs "A" and "B", each with its own `OpLog`, `HybridClock` on a fake wall):
  - `historyFromAAppearsOnB`: A records `HistoryUpsert`; `B.apply(A.since(B.marks))` → B's `observeRecent()` has the row with `originDevice == A`.
  - `sameOpFromTwoTransportsAppliesOnce` (Review Focus 2): applying the same list twice → second call returns 0; one row.
  - `lastWriterWinsPerSetting`: A sets level FORMATTED at hlc 10, B sets LIGHT at hlc 20; after exchange both ways both have LIGHT.
  - `correctedFastClockLosesToLaterEdit` (Review Focus 3): A's clock observed 90_000_000 earlier; B's later edit (after observing A's ops) wins on both.
  - `tieBreaksByDeviceId`: equal hlc → higher device ID wins, same on both sides.
  - `clearBeatsLateInsert`: B applies `ClearBefore(HISTORY, 100, null)` then a `HistoryUpsert` created at 50 → row absent; one created at 150 → present.
  - `scopedClearKeepsOtherOrigins`: `ClearBefore(STATS, 100, "A")` removes A's stats only.
  - `relaysThroughMiddleDevice`: A→B exchange, then B→C → C has A's row; C's marks include A.
  - `deleteRemovesEverywhere`: `HistoryDelete(A, 3)` applied on B removes the copy.
  - `remoteOpsAreNotReRecorded`: applying B's ops on A adds no ops with origin A.
  - `SnapshotTest.snapshotThenOps`: C applies A's snapshot then A's later ops → same rows, settings and marks as A.
  - `SnapshotTest.snapshotRespectsNewerLocalSetting`: C's local setting with higher hlc survives the snapshot.
- [ ] **Step 2: Run** `:core:desktopTest --tests '*OpApplierTest*' --tests '*SnapshotTest*'` — FAIL.
- [ ] **Step 3: Implement.** `apply`: for each envelope in `(origin, seq)` order, `clock.observe(hlc)`, `log.store` (skip if present), then effect without recording: upserts keyed by `(originDevice, originId)` (history) or `(originDevice, historyId)` (stats), skipped when a matching clear covers `createdAt`; per-origin 50-row trim for history; `SettingSet` applies when `(hlc, origin)` is greater than the stored stamp (compare hlc, then origin string) and writes through `SettingsRepository.applyShared(key, value)` (new internal method that doesn't record); categories/apps last-writer-wins via the same stamp table keyed `category:<NAME>` / `app:<id>`. Snapshot marks seed `sync_peers`.
- [ ] **Step 4: Run** Step 2 and the full test command — PASS.
- [ ] **Step 5: Commit** `feat(core): merge synced changes and snapshots`.

### Task 15: `sync` module, keys and group state

**Files:**
- Create: `sync/build.gradle.kts` (KMP android + desktop, like `core`), `settings.gradle.kts` include
- Create: `sync/src/commonMain/kotlin/io/github/agopalareddy/umm/sync/crypto/{DeviceKeys,GroupCipher,Hkdf,MemberSeal}.kt`, `sync/.../group/{GroupState,GroupStore}.kt`
- Modify: `gradle/libs.versions.toml` (secp256k1-kmp + `jni-jvm` for desktop, `jni-android` for android), CI test command (`:sync:desktopTest`)
- Test: `sync/src/desktopTest/.../crypto/CryptoTest.kt`, `group/GroupStoreTest.kt`

**Interfaces:**
- Produces:
  - `class DeviceKeys(privateKey: ByteArray) { val publicKey: ByteArray /* 32-byte x-only */; fun sign(message32: ByteArray): ByteArray /* BIP-340 */; fun agree(peerPublic: ByteArray): ByteArray /* 32-byte ECDH secret */; companion object { fun generate(): DeviceKeys; fun verify(publicKey: ByteArray, message32: ByteArray, signature: ByteArray): Boolean } }`.
  - `object Hkdf { fun derive(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray }`.
  - `class GroupCipher(key: ByteArray, val epoch: Int) { fun seal(plain: ByteArray): ByteArray; fun open(sealed: ByteArray): ByteArray }` — format `epoch(4, big-endian) ‖ nonce(12) ‖ ciphertext+tag`, epoch as AAD; `companion fun epochOf(sealed: ByteArray): Int`.
  - `object MemberSeal { fun seal(sender: DeviceKeys, recipient: ByteArray, plain: ByteArray): String; fun open(recipient: DeviceKeys, sender: ByteArray, sealed: String): ByteArray }` (ECDH → HKDF info `umm-member` → AES-GCM, base64url).
  - `@Serializable data class GroupState(val groupId: String, val key: String, val epoch: Int, val previousKey: String?, val previousUntil: Long, val members: List<Member>, val relays: List<String>, val lanEnabled: Boolean = true, val relayEnabled: Boolean = true)`.
  - `class GroupStore(store: KeyValueStore, cipher: SecretCipher) { val state: StateFlow<GroupState?>; val device: DeviceKeys; var deviceName: String; fun save(state: GroupState); fun clear() }` — device keys created on first use; everything stored encrypted through `cipher`.

- [ ] **Step 1: Write the failing tests:**
  - `CryptoTest.bip340Vector`: BIP-340 test vector 0 (secret key `…0003`, message zeros) signs to the published signature and verifies.
  - `CryptoTest.ecdhIsSymmetric`: `a.agree(b.publicKey).contentEquals(b.agree(a.publicKey))`.
  - `CryptoTest.hkdfRfc5869Case1`: RFC 5869 test case 1 output.
  - `CryptoTest.groupCipherRoundTripsAndRejectsTampering`: open(seal(x)) == x; flipping any byte throws; `epochOf` returns the epoch.
  - `CryptoTest.memberSealOnlyOpensForRecipient`: third key fails to open.
  - `GroupStoreTest.persistsAcrossInstances` and `deviceKeysAreStable`.
- [ ] **Step 2: Run** `./gradlew -Pkotlin.compiler.execution.strategy=in-process :sync:desktopTest` — FAIL.
- [ ] **Step 3: Implement** with `fr.acinq.secp256k1.Secp256k1` (`signSchnorr`, `verifySchnorr`, `pubKeyCompress`/x-only, `ecdh`) and JCA AES/GCM + HmacSHA256. Wire `OpLog.active` to `groupStore.state.value != null` in both graphs.
- [ ] **Step 4: Run** Step 2 — PASS; `:app:assembleDebug` builds (JNI lib packaged; `unzip -l` the APK shows `libsecp256k1-jni.so` for arm64). Add a secp256k1 sign/verify check to `SelfCheck.run()` (Task 6) and confirm `--self-check` still prints `ok` on the jlink image (add modules if not).
- [ ] **Step 5: Commit** `feat(sync): add device keys and group state`.

### Task 16: Exchange session and LAN transport

**Files:**
- Create: `sync/.../SyncSession.kt`, `sync/.../lan/{FrameCodec,LanTransport,Discovery}.kt`, `sync/src/desktopMain/.../lan/JmdnsDiscovery.kt`
- Modify: `gradle/libs.versions.toml` (JmDNS)
- Test: `sync/src/desktopTest/.../lan/FrameCodecTest.kt`, `SyncSessionTest.kt`, `LanTransportTest.kt`

**Interfaces:**
- Consumes: `OpLog`, `OpApplier`, `buildSnapshot` (Task 14); `GroupCipher`, `GroupStore` (Task 15).
- Produces:
  - `object FrameCodec { const val MAX = 1 shl 20; fun write(out: OutputStream, frame: ByteArray); fun read(input: InputStream): ByteArray }`.
  - `interface Wire { suspend fun send(message: ByteArray); suspend fun receive(): ByteArray }`.
  - `@Serializable sealed interface SessionMessage { Hello(deviceId, epoch, marks, oldest: Map<String, Long>), Ops(list: List<OpEnvelope>), SnapshotMsg(snapshot), Done }`.
  - `class SyncSession(log: OpLog, applier: OpApplier, snapshot: suspend () -> Snapshot, cipher: () -> GroupCipher) { suspend fun run(wire: Wire, initiator: Boolean): SessionResult }`, `data class SessionResult(val sent: Int, val received: Int)`. A peer gets a snapshot instead of ops when its mark for some origin is below that origin's oldest kept seq, or it has no marks at all.
  - `data class LanPeer(val deviceId: String, val host: InetAddress, val port: Int)`; `interface Discovery { fun advertise(port: Int, groupHash: String, deviceId: String): AutoCloseable; fun browse(groupHash: String): Flow<LanPeer> }`; `fun groupHash(groupId: String): String` (first 8 bytes of SHA-256, hex).
  - `class LanTransport(session: SyncSession, discovery: Discovery, group: GroupStore, selfId: String, scope: CoroutineScope) { suspend fun start(): Int; suspend fun syncWith(peer: LanPeer): SessionResult; val peers: StateFlow<List<LanPeer>>; fun stop() }`; `JmdnsDiscovery` (desktop) binds to each non-loopback IPv4 interface.

- [ ] **Step 1: Write the failing tests:**
  - `FrameCodecTest`: round trip; length > `MAX` → `IOException` on read without allocating; truncated stream → `EOFException`.
  - `SyncSessionTest.exchangesBothWays` (in-memory `Wire` pair): A and B each have one unseen op → both end with both; `SessionResult(1, 1)`.
  - `SyncSessionTest.newPeerGetsSnapshot`: empty C ← A → C equals A.
  - `SyncSessionTest.wrongGroupKeyFailsCleanly`: B with a different key → `run` throws `SyncAuthException`, nothing applied.
  - `LanTransportTest.twoDevicesOverLoopback`: two full stacks (in-memory DBs, same group) with a fake `Discovery` that reports each other on `127.0.0.1`; a history row added on A shows on B after `syncWith`.
- [ ] **Step 2: Run** `:sync:desktopTest --tests '*lan*' --tests '*SyncSessionTest*'` — FAIL; implement; PASS.
- [ ] **Step 3: Implement.** Every `Wire` message is `GroupCipher.seal(json)`; previous-epoch key accepted until `previousUntil`. Server accepts connections, runs `session.run(initiator = false)` per connection, one at a time per peer. Concurrent syncs with the same peer are coalesced by a per-peer `Mutex`. Update `sync_peers` (`lastSeen`, transport `lan`). Add a JmDNS create/close check to `SelfCheck.run()` and re-run `--self-check` on the jlink image.
- [ ] **Step 4: Commit** `feat(sync): sync over the local network`.

### Task 17: Nostr relay transport

**Files:**
- Create: `sync/.../relay/{NostrEvent,RelayClient,RelayTransport}.kt`
- Test: `sync/src/desktopTest/.../relay/NostrEventTest.kt`, `RelayTransportTest.kt` (fake relay on OkHttp `MockWebServer` WebSocket upgrade)

**Interfaces:**
- Consumes: `DeviceKeys` (Task 15), `OpLog`/`OpApplier` (Tasks 13–14).
- Produces:
  - `@Serializable data class NostrEvent(id, pubkey, created_at: Long, kind: Int, tags: List<List<String>>, content: String, sig)` with `companion fun sign(keys: DeviceKeys, kind: Int, tags: List<List<String>>, content: String, createdAt: Long): NostrEvent` and `fun verify(): Boolean`.
  - `class RelayClient(url: String, http: OkHttpClient) { suspend fun publish(event: NostrEvent): Result<Unit>; fun subscribe(id: String, filter: JsonObject): Flow<NostrEvent>; fun close() }` (NIP-01 `EVENT`/`REQ`/`EOSE`/`OK`/`CLOSED`).
  - `class RelayTransport(group: GroupStore, log: OpLog, applier: OpApplier, http: OkHttpClient, scope: CoroutineScope, clockSeconds: () -> Long) { suspend fun publishPending(); fun start(); fun stop(); val relayErrors: StateFlow<Map<String, String>> }`.
  - Constants: `KIND_APP_DATA = 30078`, `KIND_PAIRING = 21078` (ephemeral), `EXPIRATION_SECONDS = 30 * 86_400`, `MAX_CONTENT = 32_000`.

- [ ] **Step 1: Write the failing tests:**
  - `NostrEventTest.idMatchesNip01Serialization`: a fixed event's `id` equals the SHA-256 of `[0,pubkey,created_at,kind,tags,content]` compact JSON with NIP-01 escaping (precomputed hex in the test).
  - `NostrEventTest.tamperedContentFailsVerify`.
  - `RelayTransportTest.publishesBatchesUnderLimit`: 500 history ops → several events, each `content.length < MAX_CONTENT`, each tagged `d=umm:<hash>:<first>-<last>`, `expiration` = now + 30 days.
  - `RelayTransportTest.deliversToOtherMember`: A publishes to the fake relay, B subscribed → B has A's row.
  - `RelayTransportTest.ignoresNonMembers`: an event signed by a non-member key is not applied.
  - `RelayTransportTest.oneRelayDownStillPublishes`: one URL refuses connections → publish succeeds via the other; `relayErrors` names the failing URL.
- [ ] **Step 2: Run** `:sync:desktopTest --tests '*relay*'` — FAIL; implement; PASS.
- [ ] **Step 3: Implement.** Content = base64(`GroupCipher.seal(gzip(json(List<OpEnvelope>)))`). Publish only own-origin ops past the last published seq (stored per relay in the key-value store). Subscription filter: `{"kinds":[30078],"authors":[member pubkeys except self],"since": last seen created_at - 1h}`. Reconnect with backoff 5 s → 5 min.
- [ ] **Step 4: Choose the default relays:** with a throwaway key, publish and read back one kind-30078 event (with `expiration`) on candidates (`wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.primal.net`, `wss://relay.nostr.band`, `wss://nostr.wine`); keep three that accept and return it; record results in the commit body; put them in `GroupState` defaults.
- [ ] **Step 5: Commit** `feat(sync): sync through Nostr relays`.

### Task 18: Pairing, membership and key rotation

**Files:**
- Create: `sync/.../pair/{PairingCode,Pairing}.kt`, `sync/.../group/Membership.kt`
- Test: `sync/src/desktopTest/.../pair/PairingCodeTest.kt`, `PairingTest.kt`, `group/KeyRotationTest.kt`

**Interfaces:**
- Consumes: Tasks 15–17.
- Produces:
  - `@Serializable data class PairingCode(val deviceId: String, val name: String, val publicKey: String, val secret: String, val lan: List<String>, val port: Int, val relays: List<String>, val expiresAt: Long) { fun encode(): String /* "umm-pair:1:" + base64url(json) */; companion fun decode(text: String, now: Long): PairingCode /* throws PairingException(EXPIRED|MALFORMED) */ }`.
  - `class Pairing(group: GroupStore, apiKey: ApiKeyStore, lan: LanTransport?, relay: RelayTransport, applier: OpApplier, snapshot: suspend () -> Snapshot, now: () -> Long)` with `fun offer(sendKey: Boolean): PairingOffer` (`PairingOffer(val code: PairingCode, val result: Deferred<PairedDevice>)`, cancelled after 10 min) and `suspend fun join(code: String, sendKey: Boolean): PairedDevice`; `data class PairedDevice(val name: String, val keyReceived: Boolean)`. Each side's `sendKey` governs only its own key; the key travels from the side already in a group (or from the offering side when neither is), and whichever side is already in a group sends the group.
  - `class Membership(group: GroupStore, log: OpLog, now: () -> Long) { suspend fun remove(deviceId: String); suspend fun leave(); suspend fun rename(name: String); suspend fun onGroupOp(envelope: OpEnvelope) }` — `onGroupOp` is `OpApplier`'s callback.
  - `enum class PairingError { EXPIRED, USED, MALFORMED, UNREACHABLE, WRONG_SECRET }`; UI copy for EXPIRED/USED/WRONG_SECRET: "This code has expired or was already used. Show a new one."

- [ ] **Step 1: Write the failing tests:**
  - `PairingCodeTest`: encode/decode round trip; expired → `EXPIRED`; garbage and wrong prefix → `MALFORMED`.
  - `PairingTest.lanPairingHandsOverGroupAndKey`: A (no group, has API key) offers; B joins over loopback LAN with `sendKey = true` → both share `groupId`, epoch 0, two members; B's `ApiKeyStore.get()` equals A's; B has A's history via snapshot.
  - `PairingTest.relayFallback`: fake LAN unreachable, fake relay → same outcome.
  - `PairingTest.codeIsSingleUse`: second join with the same code → `USED`.
  - `PairingTest.wrongSecretFails`: code with a modified secret → `WRONG_SECRET`, no state saved on either side.
  - `PairingTest.keyNotSentWhenUnchecked`: `sendKey = false` → joiner's key unchanged, `keyReceived == false`.
  - `KeyRotationTest.removeRotatesForRemaining`: A, B, C; A removes C → A and B at epoch 1 with the same key; C cannot open epoch-1 payloads.
  - `KeyRotationTest.removedDeviceIsIgnoredAfterGrace` (Review Focus 5): C's epoch-0 events are applied within 24 h of rotation and dropped after.
  - `KeyRotationTest.leaveClearsLocalGroupOnly`: B leaves → B's `state` null and data kept; A and C rotate.
- [ ] **Step 2: Run** `:sync:desktopTest --tests '*pair*' --tests '*KeyRotationTest*'` — FAIL; implement; PASS.
- [ ] **Step 3: Implement.** Handshake messages (over a LAN connection whose first frame is a plaintext `PairHello`, or `KIND_PAIRING` events `p`-tagged to the offer's key): joiner sends its public key; both compute `k = Hkdf.derive(agree(peer) ‖ secret, salt = offerDeviceId ‖ joinerDeviceId, info = "umm-pair")`; each sends `HMAC(k, "confirm" ‖ own id)`; then `Welcome { group, apiKey? }` sealed with `k`; joiner saves the group and applies the snapshot. Used secrets are remembered for 10 minutes. Rotation: new random key, `KeyRotation(epoch + 1, MemberSeal per remaining member)` recorded as an op sealed under the old key; `previousKey`/`previousUntil = now + 24 h`.
- [ ] **Step 4: Commit** `feat(sync): pair devices and manage the group`.

### Task 19: `SyncEngine` and desktop wiring

**Files:**
- Create: `sync/.../SyncEngine.kt`
- Modify: `desktop/.../DesktopGraph.kt`, `DesktopEngine.kt`, `DesktopApp.kt`, `ui/.../ui/UmmServices.kt` (adds `sync`), `ui/build.gradle.kts` (depends on `:sync`)
- Test: `sync/src/desktopTest/.../SyncEngineTest.kt`

**Interfaces:**
- Consumes: everything in Tasks 13–18.
- Produces: `class SyncEngine(group: GroupStore, lan: LanTransport?, relay: RelayTransport, pairing: Pairing, membership: Membership, log: OpLog, scope: CoroutineScope, now: () -> Long)` with `val status: StateFlow<SyncStatus>`, `val devices: StateFlow<List<DeviceInfo>>`, `fun start()`, `suspend fun syncNow()`, `fun onLocalChange()` (debounced 2 s), `suspend fun setTransports(lan: Boolean, relay: Boolean)`, `suspend fun setRelays(urls: List<String>)`, plus `pairing`/`membership` exposed; `sealed interface SyncStatus { NotPaired; Idle(lastSync: Long?, via: String?); Syncing; Error(message: String, lastSync: Long?) }`; `data class DeviceInfo(val member: Member, val isSelf: Boolean, val lastSeen: Long?, val via: String?)`. `UmmServices.sync: SyncEngine?` (Android passes `null` until Task 21; the Sync entry is hidden when null).

- [ ] **Step 1: Write the failing tests** — `SyncEngineTest` (fake transports):
  - `notPairedUntilGroupExists`; `localChangeTriggersOneDebouncedSync` (three changes within 2 s → one sync, virtual time); `prunesOnStart` (ops > 90 days gone); `errorKeepsLastSyncTime`; `transportSwitchesStopTransports`.
- [ ] **Step 2: Run** `:sync:desktopTest --tests '*SyncEngineTest*'` — FAIL; implement; PASS.
- [ ] **Step 3: Wire desktop:** `DesktopGraph` builds `GroupStore` on the keyring-backed `KeyValueStore`, `JmdnsDiscovery`, transports and engine; `DesktopEngine` starts it after setup and calls `onLocalChange()` after each dictation; the device name defaults to the hostname.
- [ ] **Step 4: Run** the full test command — PASS.
- [ ] **Step 5: Commit** `feat(sync): run sync in the desktop app`.

### Task 20: Sync UI

**Files:**
- Create: `ui/src/commonMain/kotlin/io/github/agopalareddy/umm/settings/SyncPage.kt`, `ui/.../ui/QrCode.kt`
- Modify: `UmmNavHost.kt` (`Routes.SYNC`), `SettingsScreen.kt` (Sync row on Android), `SidebarFrame` items (desktop), `HistoryScreen.kt`, `StatsPage.kt`, `HomeScreen.kt` dashboard (device filter), `Platform.kt`, `core/.../stats/StatsRepository.kt` (`observeDashboard(range, origin: String?)`), `HistoryRepository.observeRecent(origin: String?)`, `gradle/libs.versions.toml` (ZXing core)
- Test: `ui/src/desktopTest/.../ui/QrCodeTest.kt`, `core/src/desktopTest/.../stats/DashboardStatsTest` (origin filter)

**Interfaces:**
- Consumes: `SyncEngine` (Task 19).
- Produces: `fun qrMatrix(text: String): Array<BooleanArray>` and `@Composable fun QrCode(text: String, modifier: Modifier)`; `Platform.scanPairingCode(): (suspend () -> String?)? = null` (null hides "Scan code"); `@Composable fun SyncPage(onBack: (() -> Unit)?)`.

- [ ] **Step 1: Write the failing tests:** `QrCodeTest.matrixHasFinderPatterns` (top-left 7×7 finder: border dark, ring light, 3×3 core dark); `QrCodeTest.pairingCodeFits` (a 400-char code encodes at error level M); `DashboardStatsTest.originFilter` (two origins → filtered totals match one origin's rows).
- [ ] **Step 2: Run** `:ui:desktopTest --tests '*QrCodeTest*' :core:desktopTest --tests '*DashboardStatsTest*'` — FAIL; implement; PASS.
- [ ] **Step 3: Implement the page** per spec §7: status card (explicit text: "Synced 2 min ago via Wi-Fi" / "Not synced since 14:02 — no devices reachable" / "Not paired"); device list (name, platform, "this device", last seen, Rename on self, Remove on others with a confirm dialog); "Pair a device" opens a dialog with the QR, the code with Copy, a "Send OpenRouter key" `CheckRow` (default on), the relay notice "Encrypted copies of your synced data pass through public Nostr relays. They can't read it.", and a live result; "Paste code" (and "Scan code" when the platform offers it) joins; `SwitchRow`s "Sync over Wi-Fi" and "Sync through relays"; Advanced section with the relay list (add/remove, failing relays marked); "Sync now"; "Leave group" with confirm. History rows from other devices append " · from <name>"; Retry only on own rows. Stats and Home dashboard get a device `FilterChip` row when more than one device exists. Stats "Delete data" offers "This device's stats" / "All devices' stats" when paired, recording the matching `ClearBefore`.
- [ ] **Step 4: Verify on desktop:** with the user's KDE session, open Sync, show a code, copy it; a second in-process engine is not needed here — the full flow is Task 23.
- [ ] **Step 5: Commit** `feat(ui): add the Sync page and device filters`.

### Task 21: Android wiring

**Files:**
- Create: `sync/src/androidMain/.../lan/NsdDiscovery.kt`, `app/src/main/kotlin/io/github/agopalareddy/umm/sync/{SyncWorker,CodeScanner}.kt`
- Modify: `app/.../AppGraph.kt`, `app/.../UmmApp.kt` (schedule work), `app/.../ime/UmmInputMethodService.kt` and `bubble/BubbleService.kt` (call `onLocalChange()`; pass `appLabel` — done in Task 12), `app/.../ProvideUmm.kt`/Android `Platform` (`scanPairingCode`), `app/build.gradle.kts`, `gradle/libs.versions.toml` (WorkManager, `play-services-code-scanner`), `AndroidManifest.xml` (`ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`)
- Test: `app/src/test/.../sync/SyncWorkerTest.kt` (Robolectric/WorkManager test helpers as the app's other tests do)

**Interfaces:**
- Consumes: `SyncEngine`, `Discovery`. Produces: `class NsdDiscovery(context: Context) : Discovery` (holds a `WifiManager.MulticastLock` while browsing); `class SyncWorker : CoroutineWorker` that runs `syncNow()` and returns `success` even on network failure (status shows it); periodic work `umm-sync-lan` every 15 min requiring `UNMETERED`, `umm-sync-relay` every 60 min requiring `CONNECTED`, both enqueued `KEEP` only while paired and cancelled on leave.

- [ ] **Step 1: Write the failing test** — `SyncWorkerTest`: not paired → no periodic work; paired → both unique works enqueued with the constraints above; leaving cancels them.
- [ ] **Step 2: Run** `:app:testDebugUnitTest --tests '*SyncWorkerTest*'` — FAIL; implement; PASS.
- [ ] **Step 3: Implement** the rest: `AppGraph` builds the sync stack with `GroupStore` on the Keystore-backed store; the device name defaults to `Build.MODEL`; sync on app foreground (`ProcessLifecycleOwner` `ON_START`) and after each dictation; `CodeScanner` uses `GmsBarcodeScanning.getClient(context, options QR only).startScan()`; when Play services is missing, `scanPairingCode` returns null so only "Paste code" shows.
- [ ] **Step 4: Run** the full test command and `assembleDebug` — PASS.
- [ ] **Step 5: Commit** `feat(app): sync on Android`.

### Task 22: Privacy policy and docs

**Files:**
- Modify: `docs/privacy/index.md` (or the existing privacy page file), `README.md`, `.superpowers/play-console-handoff.md` (gitignored; add the Data safety changes for the user)

- [ ] **Step 1:** Privacy policy gains a "Sync between your devices" section: off until you pair; what syncs (history text, stats, shared settings; the OpenRouter key only during pairing and only if you choose); direct over your network or through public Nostr relays, end-to-end encrypted with a key only your devices hold; relays can see device public keys, timing and size; how to stop (Leave group, turn off relays). README: Linux install section (tarball, deb/rpm, Flatpak, AUR soon) and a Sync section. Handoff note lists the Data safety answers that change (data shared: none to third parties in readable form; data encrypted in transit: yes; "App activity / other user-generated content" transmitted when sync is on).
- [ ] **Step 2: Commit** `docs: describe sync in the privacy policy`.

### Task 23: Phase 3 check and PR

- [ ] **Step 1:** Full test command, `assembleDebug`, desktop `--self-check` — PASS.
- [ ] **Step 2: Upgrade check on the Pixel:** with the phone's current install (schema 2, real data), install a debug build of this branch over it with a higher `-PversionCode` (as in sub-project 1's Task 6); confirm history, stats, settings and key survive and both dictation paths work. A release-signed check over a real v1.2.0 install stays on the pre-release checklist and needs the user.
- [ ] **Step 3: Pixel ↔ KDE:** pair by scanning the desktop's QR with "Send OpenRouter key" on (desktop currently has its own key — check it's replaced and the Account card says received). Then: dictate on the phone → appears in desktop History within a minute; change default level on desktop → phone shows it; delete a history item on the phone → gone on desktop; stats show both devices with the filter. Turn Wi-Fi off on the phone (mobile data): dictate → desktop gets it through relays within a minute. Remove the phone from the desktop → phone shows "Removed from the group".
- [ ] **Step 4:** Report with screenshots; on the user's OK push, PR, merge after approval. Update memory (`umm-linux-engine-status` → project status; new facts on relays chosen and any portal or JNI gotchas).
