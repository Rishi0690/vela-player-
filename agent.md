# agent.md — Vela Player

Guide for AI coding agents (and humans) working on this repo.

## What this is
Vela Player is an offline Android video player written in Kotlin (package `com.hark.velaplayer`).
It is inspired by CloudStream's player: aspect-ratio modes, gestures, subtitles, audio tracks, playlists, Focus Mode, and theming.

- minSdk 24, targetSdk 34, AGP 8.3.2, Kotlin 1.9.23, Media3 (ExoPlayer) 1.3.1, viewBinding
- Current version: `versionName 2.9`, `versionCode 22`

## Golden rules
1. **Never break existing playback.** Every change must keep the player, gestures, aspect modes, subtitles, audio tracks and queue working.
2. **Bump `versionCode`** (and `versionName`) in `app/build.gradle.kts` on every release so updates install over old versions.
3. **Never change `applicationId` or the signing key.** Releases are signed with `signing/vela-release.jks` (gitignored, not in this repo). Using a different key means users must uninstall and lose their settings.
4. **Do not enable minify or shrinking.** This is the owner's choice, and the Material palette APIs rely on it.
5. **Destructive actions need confirmation.** Deleting files always asks first. Removing an item from a playlist never deletes the file.
6. Everything stays on the device. No analytics or network calls (except user-entered stream URLs).

## Build
```bash
./gradlew assembleRelease   # or: gradle assembleRelease --no-daemon
```
APK output: `app/build/outputs/apk/release/`. Release signing needs `signing/vela-release.jks`. Without it, build debug instead.
Lint (`lintRelease`) has known non-blocking warnings: hardcoded strings, RestrictedApi for the Material `Scheme` in ThemeActivity, and API-level hints.

## Source map (`app/src/main/java/com/hark/velaplayer/`)
| File | Role |
|---|---|
| `VelaApp.kt` | Application class, theme init |
| `MainActivity.kt` | Library: Folders / All videos / Favorites / Playlists, grid/list view per section, collection headers (Play all / Shuffle / Add), ActionMode multi-select, continue-watching strip |
| `VideoRepository.kt` | MediaStore queries for videos and folders |
| `Store.kt` | SharedPreferences/JSON store: playlists, favorites, watched, resume positions, bulk operations |
| `Prefs.kt` | Typed access to user settings (remember audio/sub language, gesture sides, etc.) |
| `ThumbLoader.kt` | Thumbnails with dark-frame rejection and frame fallback, playlist cover = first video thumbnail only (no collage since v2.5.1); async `fetch` |
| `PlayerActivity.kt` | Main player UI: controls, gestures, timeline, sheets, queue, PiP, Focus pill and break card. `EXTRA_SHUFFLED` for shuffled queues |
| `PlayerHolder.kt` / `PlaybackService.kt` | Shared ExoPlayer instance and media session/background service |
| `ZoomableVideoLayout.kt` + `AspectMode.kt` | Aspect-ratio modes and pinch zoom (top-priority feature) |
| `TimelineSeekBar.kt` | Custom seek bar: buffered track, expanding thumb, frame preview hookup |
| `PlayerSheet.kt` | Adaptive option panels (bottom sheet in portrait, side card in landscape) |
| `ShadowImageButton.kt` | White controls/text with soft shadows (`ShadowIcon`) |
| `SubtitleParser.kt` | External subtitle parsing and styling |
| `Motion.kt` | Shared animation curves, press springs, haptics |
| `ThemeManager.kt` / `ThemeActivity.kt` / `ColorPickerView.kt` | Theme presets, custom HEX, Light/Dark/System, dynamic palette on Android 12+, closest preset on older versions. Activities extend `ThemedActivity` |
| `FocusStore.kt` / `FocusTracker.kt` / `FocusActivity.kt` / `FocusChartView.kt` | Focus Mode: SQLite `focus.db` (sessions, per-video time, daily totals), real-time-only tracking (no pause/buffering/background, no PiP double count), goals, break reminders, optional DND, stats dashboard |
| `VideoPickerActivity.kt` | Multi-video picker for playlists: search, folder filter, ordered selection, document import, duplicate handling |
| `SettingsActivity.kt` | Category settings screens + search. `open()` guards rapid/duplicate navigation (350 ms debounce, same-tag, `isStateSaved`, `setReorderingAllowed`); `navAnim()` runs nav anims on a hardware layer and honours reduced motion |
| `AspectFrameLayout.kt`, `Fmt.kt` | Helpers |
| `Vault.kt` / `VaultAuth.kt` | Hidden Vault records (hidden list, custom order, vault-only resume) and credentials (PBKDF2 PIN/pattern, biometric flag, lockout, v2.3 setup choice none/pin/pattern, vault-only settings in `vela_vault_auth`) |
| `VaultActivity.kt` / `VaultViews.kt` / `VaultBio.kt` | Vault screen: v2.3 welcome (security or no password), lock screen, vault library (Play all/Shuffle, filter, sort incl. custom order + drag reorder, multi-select bulk actions); PIN pad, pattern view, shared PIN/pattern flows, biometric prompt |
| `VaultSettingsActivity.kt` | v2.3 vault settings, only reachable from inside the vault (security, auto-lock, "(Recommended) Main Settings" hide instructions). Unhide is per item inside the vault |
| `VaultStorage.kt` | v2.5 vault storage modes: "Hide in Vela only" vs "Move to private storage" (copy to files/vault, verify, then system delete of original; restore copies back to original folder or Movies/Vela Restored without duplicates; startup reconcile of interrupted moves). Private files are NOT encrypted and are deleted on uninstall |
| `StackedThumbLayout.kt` | v2.4 stacked thumbnail back-card for playlists/folders |
| `PlaybackChip.kt` | v2.3 bottom chip for all player messages (skip chips stay at the sides) |
| `TabSwipeLayout.kt` / `LibraryNavigator.kt` | Swipe between library tabs; library navigation helpers |
| `PlayerSession.kt` / `FrameCapture.kt` / `VolumeBoost.kt` | Player session state, frame preview capture, volume boost |
| `VelaScroller.kt` | v2.8 slim draggable scroll indicator for every RecyclerView (`VelaScroller.attach(rv)`): reads the list's real scroll offset/range, fades after 1.2 s, hidden when content fits, RTL aware, accent colour. Native ScrollViews use the theme's `scrollbar_thumb` |
| `HoldTitleView.kt` | v2.3 Home "Vela Player" title: 5 s continuous hold opens the Hidden Vault |

## Resources
- Layouts: `res/layout/` (`activity_*`, `item_*` list + `*_grid` variants, dialogs)
- Settings: `res/xml/preferences.xml` (root) plus `prefs_appearance`, `prefs_library`, `prefs_gestures`, `prefs_playback`, `prefs_playlists`, `prefs_focus`
- Theme accents: `values/accents.xml`, `values-night/accents.xml`, `values/accents_player.xml` (generated Material 3 overlays, one per preset)
- Animations: `res/anim/` — `nav_push/pop_enter/exit` (calm fade-through, ~6% slide, used for activities and settings), `dialog_enter/exit` (0.96 scale pop). Android 12+ blur behind dialogs/sheets is 16dp (`values-v31/themes.xml`); player uses `*Plain` styles without blur

## Conventions
- Kotlin, viewBinding, no Compose, no DI framework. Keep it simple.
- Use the theme accent attributes, never hardcoded accent colors.
- New option menus in the player go through `PlayerSheet`.
- New settings go in the matching `prefs_*.xml` category. Read them through `Prefs.kt`.
- Brightness and volume gesture sides are independent settings.
- Motion: subtle and fast, NOT iPhone-style big slides (user rejected it in v2.6). Respect reduced motion. Never let an animation block input or playback.
- Hidden Vault entry is only the 5 s hold on the Home title.
- Commit messages: `vX.Y: short summary of what changed`.

## Release checklist
1. Make the change. Keep playback intact.
2. Bump `versionCode` / `versionName`.
3. `assembleRelease` with the release keystore and verify the signing cert is unchanged.
4. Commit and push to `main`.
5. Upload the signed APK to the Google Drive folder "Vela Player APKs" as `VelaPlayer-vX.Y.apk`.

## Recent history
- v2.5 Vault private storage · v2.5.1 single-thumbnail covers · v2.5.2 slimmer Vault settings
- v2.6 settings transition fix + new motion · v2.6.1 calmer fade-through, lighter dialogs/blur
- v2.7 Settings navigation root-cause fixes (rapid taps, GPU layer, reduced motion)
- v2.8 Settings: categories open with hide/add (main screen no longer re-inflated on Back), search field is an overlay (no layout jump), no row cross-fade on toggles, title cross-fade. VelaScroller everywhere, themed native scrollbars, Motion.enabled/exit/reveal (reduced motion), animated empty states, Vault lock panel lowered via 40/60 spacers + ease-in, Vault settings: removed "Storage & uninstalling" and "About hiding" sections

## Pending (requested 2026-10-13, partly done in v2.8)
Full UI/UX + motion polish across the app: consistent motion system, design tokens (spacing, radii, type), light/dark/accent consistency, clearer empty/error/loading states, scroll/thumbnail performance. Keep every feature working.
- v2.8.1 Vault settings › Privacy › "Allow screenshots" (VaultAuth.allowScreenshots/applySecure; off = FLAG_SECURE in vault, vault settings, vault playback; also gates frame capture)
- v2.8.2 Settings ListPreference/EditTextPreference pop-ups replaced by Material dialogs (showVelaPrefDialog in SettingsActivity via onDisplayPreferenceDialog)
- v2.9 FitButtons.kt (Play all/Shuffle single-line fit), no reorder drag hint subtitle, rotation = 3 modes auto(FULL_SENSOR)/landscape/portrait shared via Prefs default_orientation (button cycles + saves; player listens to prefs; Settings refreshes onResume). Aspect Auto no longer forces rotation.
