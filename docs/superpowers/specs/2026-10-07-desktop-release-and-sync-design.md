# Desktop parity, Linux packaging, and device sync

Status: draft for review, 2026-10-07.

## 1. Goal

Make Umm a Linux app people can install from a release and that looks at home on the desktop, and let a user's
phones and computers share their key, settings, history and stats without an account or an Umm server.

This combines sub-projects 3 (desktop parity), 4 (packaging) and 5 (QR pairing and serverless sync) of the Linux
app. Sub-projects 1 (shared core and UI, PR #2) and 2 (Linux dictation engine, PR #4) are merged. The work ships as
three phases, each its own branch and PR to `main`, in this order: parity (§3), packaging (§4), sync (§5–§7).

### Success criteria

- Parity: on KDE and in the GNOME 50 VM, the window has a sidebar layout, follows the system accent color and
  light/dark live, and on KDE a per-app cleanup level set on the Categories page is used for dictation in that app.
- Packaging: a `vX.Y.Z` tag produces the Android APK/AAB plus a tarball, `.deb`, `.rpm` and `.flatpak`, each of which
  installs, starts at login, and dictates in the VM or on the host. `makepkg` builds the `umm-bin` PKGBUILD locally.
  The bundle is under 100 MB.
- Sync: the Pixel and the KDE desktop pair by QR; the desktop receives the phone's key; a dictation, a settings change
  and a history delete on either device show on the other within a minute over Wi-Fi, and within a minute through
  relays with the phone off Wi-Fi. A v1.2.0 install upgrades with history, stats, settings and key intact.

### Non-goals

- Submitting to the AUR or Flathub (the user does that once registration reopens / when ready).
- X11 sessions and wlroots compositors (Sway, Hyprland), macOS, Windows.
- Per-app levels on GNOME (GNOME exposes no focused-window information to apps).
- Syncing audio recordings, the OpenRouter key after pairing, or device-only settings (§5).
- Android targetSdk 37 and its local-network permission.

## 2. Decisions

| Topic | Decision |
|---|---|
| Delivery | One spec and one plan; three phases, three PRs (parity → packaging → sync). |
| Desktop layout | Sidebar of destinations with a capped-width content pane; Android keeps its current navigation. |
| Desktop colors | XDG Settings portal `color-scheme` and `accent-color`, seeded into Material 3 with MaterialKolor; Umm purple without an accent. |
| Packages | Tarball, `.deb`, `.rpm`, Flatpak bundle, and an `umm-bin` PKGBUILD kept in the repo. |
| Sync scope | OpenRouter key (at pairing only), stats, history, shared settings. |
| Sync model | Per-device change log; per-device ownership of history and stats rows; last writer wins per shared setting. Rejected: full-state snapshots (relay size limits, awkward deletes) and a CRDT library (heavy, weak Kotlin support). |
| Transports | Same-network direct (mDNS + TCP) and Nostr relays, both on by default. |
| Group size | Any number of devices; any member can pair a new one. |
| Deletes | Propagate to every device. Stats "Delete data" asks whether to delete this device's stats or all devices' stats. |

## 3. Phase 1: desktop parity

### Layout

- The desktop window opens at 1040×720, resizable, minimum 720×520. A `NavigationRail`-style sidebar lists Home,
  Stats, History, Categories, Models, Dictation, Languages, Appearance, Account, Sync (phase 3), Desktop and About.
  Below 720 dp of width it shows icons only.
- Pages render in a content pane with a maximum width of 720 dp, centered. Page composables stay in `ui`; only the
  frame differs. Desktop pages drop their back arrows (the sidebar replaces drill-down); Android is unchanged.
- The Home "Try it here" field stays on desktop as a plain practice field. Bubble settings stay Android-only. The
  existing desktop page keeps hotkey, microphone, sounds and start at login.

### Colors

- New `linux` adapter `PortalAppearance` reads `org.freedesktop.appearance` `color-scheme` (0 none, 1 dark, 2 light)
  and `accent-color` (three doubles in 0..1, out-of-range meaning unset) via `org.freedesktop.portal.Settings.ReadOne`,
  and emits changes from the `SettingChanged` signal as a `Flow`.
- `DesktopPlatform.dynamicColors(dark)` returns a MaterialKolor scheme seeded from the accent, or `null` without one.
  The theme's "System" mode follows the portal's `color-scheme` (falling back to light when it is 0).
- "Use Dynamic Theme" means "use system accent" on desktop; the "Needs Android 12 or newer" note shows only on
  Android.

### Per-app levels

- `DesktopPlatform.installedApps()` scans `applications/` under `$XDG_DATA_HOME` and each `$XDG_DATA_DIRS` entry
  (which includes Flatpak exports), parses `[Desktop Entry]`, and skips `NoDisplay=true`, `Hidden=true`,
  non-`Application` types and Umm itself. ID is the desktop-file ID without `.desktop` (the name KWin reports as
  `desktopFileName`); label is the localized `Name`. Earlier directories win on duplicate IDs. `appLabel(id)` uses the
  same index.
- `Seeds.apps` gains desktop IDs: `org.mozilla.Thunderbird`, `thunderbird`, `org.gnome.Evolution` → Email;
  `com.slack.Slack`, `slack`, `com.discordapp.Discord`, `discord`, `org.signal.Signal`, `signal-desktop`,
  `im.riot.Riot`, `element-desktop` → Messaging; `md.obsidian.Obsidian`, `obsidian`, `org.kde.kate`,
  `org.gnome.gedit`, `org.gnome.TextEditor` → Notes. Seeds apply to new databases only; the 2→3 migration (§5)
  inserts any of these that are missing.
- Outside KDE, the Categories page shows one line: "Per-app levels need KDE Plasma; other desktops use the default
  level."

## 4. Phase 2: packaging

### Build

- Desktop takes `-PversionName` like Android (default `0.1.0-dev`), so `appVersion()` stops reading "dev" in
  releases.
- `includeAllModules` is replaced by an explicit `modules(...)` list found with `jdeps` plus reflection-loaded modules
  (`java.desktop` sound, `jdk.crypto.ec`, `java.net.http` if used, `jdk.unsupported`), pinned in
  `desktop/build.gradle.kts`.
- A CI step runs the packaged launcher with `--type-test` (headless smoke check that exits 0) so a missing module
  fails the release build.

### Formats

| Asset | Built by | Installs |
|---|---|---|
| `umm-X.Y.Z-linux-x86_64.tar.gz` | jpackage app image + `install.sh`/`uninstall.sh` | `~/.local` (default) or `/opt/umm` + `/usr/local/bin` with `--system`; desktop file, icon |
| `umm_X.Y.Z_amd64.deb`, `umm-X.Y.Z-1.x86_64.rpm` | jpackage | `/opt/umm`, desktop file and icon in system dirs |
| `io.github.agopalareddy.Umm.flatpak` | `flatpak-builder` from `packaging/flatpak/` | per-user or system Flatpak |
| `packaging/aur/umm-bin/` | `scripts/bump-aur.sh X.Y.Z` | `/opt/umm`, `/usr/bin/umm`, desktop file, icon, license |

- `.desktop.in` moves to `packaging/` shared by all formats, minus `MimeType=` (§4 cleanups).
- `release.yml` adds a Linux job on the tag that builds all four assets and attaches them to the same GitHub
  Release as the APK/AAB.

### Flatpak

- Manifest `packaging/flatpak/io.github.agopalareddy.Umm.yml`: runtime `org.freedesktop.Platform//25.08`, SDK
  extension `openjdk17`, builds from source offline with `packaging/flatpak/gradle-sources.json` generated by a
  repo script.
- `finish-args`: `--socket=wayland`, `--socket=fallback-x11`, `--socket=pulseaudio`, `--share=network`,
  `--share=ipc`, `--talk-name=org.freedesktop.secrets`, `--talk-name=org.freedesktop.Notifications`,
  `--talk-name=org.kde.StatusNotifierWatcher`, `--talk-name=org.kde.KWin`, `--own-name=org.kde.StatusNotifierItem-*`.
- Start at login inside Flatpak uses the Background portal (`RequestBackground` with `autostart=true`); outside
  Flatpak the autostart file stays. Detection: `/.flatpak-info` exists.

### Cleanups carried from sub-project 2

- Remove the `umm://` sign-in path: `UmmLink`, `SingleInstance`'s `OpenUrl` message, the `MimeType=` line and the
  `xdg-mime` call.
- Find the source of the launcher's "pure virtual method called" message; fix it, or document it if it is the JDK's.
- Quote paths written by `installDev`.
- App ID stays `io.github.agopalareddy.Umm` everywhere, so existing shortcut and typing permissions survive.

## 5. Phase 3: sync data

### Identity

- Device: random 128-bit ID, editable name (phone model / hostname), secp256k1 key pair (secp256k1-kmp). Stored in
  the key-value store; the private key goes through the existing secret storage (Keystore on Android, keyring on
  desktop).
- Group: random ID, 32-byte group key, key epoch, member list (device ID, name, platform, public key), relay list.
  Sync payloads are sealed with AES-256-GCM under the current group key.

### What syncs

| Data | Rule |
|---|---|
| History | Rows owned by their origin device; others hold copies. 50 kept per origin device; the list merges by time. Audio is not synced, so Retry shows only on the origin device. |
| Stats | Same ownership; kept for life. Stats and History get a device filter (default: all devices). |
| Shared settings | Last writer wins per key: default level, default language, keyboard languages, model mode, manual STT and cleanup models, ZDR only, silence timeout, category levels and scripts, app assignments. |
| Device-only | Theme mode, dynamic color, bubble settings, switch back, dashboard range/order/hidden, stats visible and recording, everything on the desktop page, OpenRouter key. |
| Deletes | Delete one history item → delete everywhere. "Clear history" and stats "Delete data" write a clear-before-T marker (data type, optional origin device) that also drops late arrivals older than T. Stats "Delete data" offers "This device's stats" (marker scoped to this origin; removed on every device) and "All devices' stats". |

### Change log

- New table `sync_ops(originDevice, seq, hlc, type, payload)` with primary key `(originDevice, seq)`. Local writes
  that sync (history add/update/delete, stats add, setting change, category/app change, clear marker, member change)
  append an op in the same transaction as the data change.
- Hybrid logical clock per device: `hlc = max(wallMs, lastHlc + 1, highest received hlc + 1)`; ties break by device ID.
- Exchange: each side sends its per-origin high-water marks; the other replies with missing ops in seq order,
  including ops relayed from third devices. Applying an op already applied (or older than a setting's current hlc) is
  a no-op.
- Ops older than 90 days are pruned. A peer whose high-water mark for an origin is below that origin's oldest kept op,
  and any newly joined device, receives a snapshot (current rows, settings with hlc, clear markers), then ops.

### Schema

- Room 2 → 3, hand-written migration: `history` and `dictation_stats` gain `originDevice TEXT NOT NULL`,
  `originId INTEGER NOT NULL`, `appLabel TEXT` with a unique index on `(originDevice, originId)`; existing rows get
  this device's ID and their own ID. New tables `sync_ops`, `sync_peers` (high-water marks, last seen, transport),
  `sync_settings_hlc` (key → hlc, origin). Tested with `MigrationTestHelper`, and on the Pixel over a real v1.2.0
  install.
- `appLabel` is filled at dictation time from `Platform.appLabel`, so synced rows show "Gmail" on desktop.

## 6. Phase 3: pairing and transports

### Pairing

1. A device opens Sync → "Pair a device", which shows a QR code and the same payload as a copyable code:
   `umm-pair:1:` + base64url of {device ID, name, public key, 128-bit one-time secret, LAN addresses and port, relay
   list, expiry 10 minutes}.
2. The other device scans it (Android: Google code scanner, no camera permission) or pastes it (both platforms).
3. The scanner connects over the LAN, falling back to the relays. Both derive a session key from secp256k1 ECDH and
   the one-time secret (HKDF-SHA256) and prove it with a key-confirmation MAC.
4. The member side sends the group (creating one first if neither device is in a group), and the OpenRouter key when
   "Send OpenRouter key" is checked (default on). The joiner stores the key and shows "Key received from <name>".
5. The joiner receives a snapshot; both screens show "Paired with <name>".

Expired, reused or wrong codes fail with "This code has expired or was already used. Show a new one."

### Removing a device

"Remove" (any member) or "Leave group" (on the device) writes a member-change op and rotates the group key: the new
key and epoch are sealed to each remaining member's public key (ECDH + HKDF + AES-GCM) and distributed as an op under
the old key. The removed device keeps its data and stops syncing; payloads under older epochs are rejected after a
24-hour grace period.

### Same-network transport

- mDNS service `_umm-sync._tcp` with TXT `g=<first 8 bytes of SHA-256(group ID)>` and `d=<device ID>`. Android:
  `NsdManager`; desktop: JmDNS.
- TCP, frames of 4-byte length + AES-GCM sealed JSON, max 1 MB per frame. Session: hello (device ID, epoch,
  high-water marks) → ops both ways → done.
- Desktop listens and browses continuously. Android syncs on app open, after each dictation, and every 15 minutes
  via WorkManager.

### Relay transport

- Nostr kind 30078 (app data, NIP-78), signed by the device's key; `d` tag `umm:<group hash>:<seq range>`; content is
  gzip + AES-GCM sealed op batches under 32 KB; NIP-40 `expiration` 30 days. Subscriptions filter on kind 30078 and
  the members' public keys.
- Three default relays, chosen during implementation by verifying they accept and return kind 30078 events;
  editable under Sync → Advanced.
- Desktop holds open WebSocket subscriptions (OkHttp). Android connects at the same moments as LAN sync plus a 1-hour
  WorkManager job on any network.
- The pairing screen states that encrypted copies pass through public relays; relays see public keys, timing and
  size, not content.

## 7. Phase 3: UI and policy

- Sync page (shared `ui`): status card ("Synced 2 min ago via Wi-Fi", or the last error), device list with platform,
  last seen, Rename and Remove; "Pair a device", "Sync now", Wi-Fi and Relays switches, Advanced relay list,
  "Leave group". Android adds "Scan code" and "Paste code"; desktop has "Paste code".
- History rows from other devices show "from <device name>". Stats and History get the device filter.
- Stats "Delete data" offers "This device's stats" and "All devices' stats" when in a group.
- Privacy policy (`docs/privacy/`) gains a sync section; the user updates the Play Data safety answers.

## 8. Modules

- `core`: Room 3 schema and migration, HLC, op log, merge/apply rules, snapshot builder, crypto helpers.
- New `sync` module (KMP, android + desktop): pairing protocol, frame codec, LAN and relay transports, `SyncEngine`
  that schedules exchanges; platform discovery (`NsdManager` / JmDNS) behind an interface.
- `ui`: desktop frame, Sync page, device filters, delete-scope dialog.
- `linux`: `PortalAppearance`, desktop-entry index, Background portal.
- `desktop`: wiring, packaging, Flatpak detection. `app`: WorkManager jobs, code scanner, wiring.

## 9. Errors

- Portal missing: colors fall back to Umm defaults; nothing else changes.
- No peers or relays reachable: status card shows "Not synced since <time>"; data stays local and syncs later.
- A relay rejects events: try the others; status names the failing relay under Advanced.
- Undecryptable payload (wrong epoch, tampering): dropped and counted; after a removed-device rotation, the device
  shows "Removed from the group".
- Migration failure: Room's fallback is not used; the app reports the error rather than wiping data.

## 10. Testing and verification

- Unit: `.desktop` parsing, portal color mapping, HLC, merge rules (last writer wins, clear-before-T beats a late
  insert, idempotent apply, A→B→C relay, snapshot then ops, skewed clock), frame codec, pairing handshake, relay
  event encoding, key rotation.
- Integration: two desktop instances with separate data dirs syncing over loopback LAN; desktop sync through an
  in-process fake relay.
- Manual: KDE host and GNOME VM screenshots for parity; package installs in the VM; Pixel ↔ KDE pairing and sync over
  Wi-Fi and relays only; v1.2.0 → new build upgrade on the Pixel.

## 11. Risks

- Public relays may reject kind 30078, rate-limit, or vanish; mitigated by three relays, an editable list, and LAN.
- secp256k1-kmp ships JNI libraries; the Flatpak and jlink builds must include the Linux one.
- Flathub's offline Gradle build may need dependency-list fixes per release.
- A hand-written Room migration on a live user base: tested on device before release.
- Stats sync multiplies row counts; dashboard queries need indexes on `originDevice` and `createdAt`.
