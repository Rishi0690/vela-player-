# agent.md — Vela Player

Guide for AI coding agents (and humans) working on this repo.

## What this is
Vela Player is an offline Android video player written in Kotlin (package `com.hark.velaplayer`).
It is inspired by CloudStream's player: aspect-ratio modes, gestures, subtitles, audio tracks, playlists, Focus Mode, and theming.

- minSdk 24, targetSdk 34, AGP 8.3.2, Kotlin 1.9.23, Media3 (ExoPlayer) 1.3.1, viewBinding
- Current version: `versionName 2.12`, `versionCode 27`

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
| `Motion.kt` | v2.12 motion system: curves (`emphasized`, `emphasizedAccel`, `decelerate`, `standard`, springs `spring`/`springSoft` via `SpringInterpolator`), durations (`MICRO` 90, `SHORT` 150, `MEDIUM` 240, `LONG` 320, `XLONG` 460, `STAGGER` 22 ms), `enabled()` (reduced motion = animator scale 0 → every helper jumps to its end state), `pressable`, `bounce`, `enter/exit/reveal`, `blurIn` (RenderEffect blur-to-sharp on API 31+, no-op ≤30), `zoomThrough` (screen zoom-in/out + blur), `crossSwap` (snapshot cross-dissolve, used for grid↔list and theme preview), `iconSwap`, `stagger`, `depthPulse`, `lightSweep` (accent light band on a view overlay) |
| `Atmosphere.kt` | v2.12 window backdrop: `AtmosphereDrawable` (3-stop vertical gradient + 2 radial glows + static 96 px grain against banding), `Atmosphere.of()` (preset attrs or `fromSeed` for custom themes), `applyTo(activity)` (window background, transparent status bar, nav bar tint; called by `ThemedActivity`), `viewBackground(view)` (window-aligned copy for opaque animated layers, e.g. settings screens) |
| `PillNavGroup.kt` | v2.12 floating pill navigation: a `ChipGroup` subclass (same chips/ids/listener as before) that draws the near-white selected pill sliding with a "liquid" spring (leading edge first) and blends each chip's text colour by pill overlap. `slideToChecked()` from the tab listener; `snap()` on layout |
| `SearchMorph.kt` | v2.12 home search morph: circular reveal of the glass search field from the search icon, back-arrow spin-in, accent light sweep, library depth pulse + nav dim; exact reverse on close (collapse is intercepted, animated, then completed), toolbar icons stagger back in. Toolbar order untouched |
| `VelaItemAnimator.kt` | v2.12 DefaultItemAnimator with staggered "rise" for added rows (search results, filters, playlist edits); used by home list and settings search |
| `SkeletonView.kt` | v2.12 loading skeleton rows with a light sweep (home first load) |
| `SettingsPanels.kt` | v2.12 `SettingsPanelDecoration`: every preference group (rows between category headers) drawn inside ONE level-2 glass panel with hairline dividers; attached in `prepareNavScreen` |
| `ThemeManager.kt` / `ThemeActivity.kt` / `ColorPickerView.kt` | v2.12: 35 presets, default `midnight_glass`; `LEGACY` map (v2.11 keys → v2.12 keys, also applied to custom `base`) + legacy seed map for pre-2.11 `accent_seed`; Palette now carries atmosphere/nav/level-2 glass; `customPalette` derives its atmosphere from the seed. (v2.11 theme system: 26 presets (`ThemeCatalog.kt`, generated), selection (`theme_key` + legacy `accent_seed`), custom themes (JSON `custom_themes`: id/name/seed/secondary/base), palettes for previews, Light/Dark/System. ThemeActivity = theme gallery (sections, live preview, saved themes edit/duplicate/rename/delete, custom color + generated palette + contrast check, collapsed Advanced, Reset). Activities extend `ThemedActivity` |
| `ThemeCatalog.kt` | GENERATED by `tools/themegen/Gen.java`: the preset list (key, label, section, seed, overlay style ids) |
| `Glass.kt` | v2.12 `Tokens` (radii XS10/S14/M20/L26/XL32/pill, spacing 4–32, float elevation) + glass levels: `surface` (L1), `elevated` (L2), `strong` (L3), `elevatedPanel`, `floatingPanel`, `pillButton`, `playerCluster`, nav colours. v2.11 glass helpers: `Glass.panel()` (fill + 1dp hairline + top sheen), `Glass.playerPanel()` (fixed dark, no blur), and `VelaDialogBuilder` (MaterialAlertDialogBuilder + glass hairline; use it for every dialog) |
| `ThemePreviewView.kt` | Canvas miniature of the app in a palette (gallery cards + big live preview) |
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
- Theme overlays: `values/accents.xml` (light), `values-night/accents.xml` (dark), `values/accents_player.xml` (player, dark). GENERATED by `tools/themegen/Gen.java` (never hand-edit). Per theme X: `Accent.X` (all roles + surfaces + glass attrs), `Glass.X` (background/surfaces/text/glass only, re-applied after DynamicColors for custom colors), `Second.X` (secondary/tertiary only, custom Advanced). Player: `AccentDark.X` / `GlassDark.X` / `SecondDark.X`. Pre-2.11 keys/seeds (violet, ocean, teal, emerald, sunset, crimson, rose, amber, slate) are kept so saved selections survive.
  Regenerate: `JAR=$(find ~/.gradle/caches -name 'material-1.11.0-runtime.jar'|head -1); javac -cp $JAR -d /tmp/tg tools/themegen/Gen.java && java -cp $JAR:/tmp/tg Gen .` (needs one gradle build first so the jar is cached)
- Glass system (v2.11): theme attrs in `values/attrs.xml` — `glassSurface` (translucent card/chip/button fill), `glassSurfaceStrong` (dialogs, sheets, popups), `glassStroke` (1dp hairline), `glassHighlight` (top sheen), `glassScrim`, `glassTint`. Styles in `values/themes.xml`: `Widget.Vela.Glass.Card` (default `materialCardViewStyle`), `.Button.Tonal`, `.Button.Tonal.Icon`, `.Button.Outlined` (default outlined style), `.Chip` (= `VelaChip`), `.Toolbar`, `.PopupMenu` / `.ListPopupWindow` / `.PopupMenu.Overflow` / `.PopupMenu.Framework(Dark)`, `.Sheet`, `.Dialog` (`MaterialAlertDialog.Vela`). Drawables: `glass_card`, `glass_popup(_dark)`, `bg_panel` (player). Colors `res/color/glass_*` selectors. Rule: real blur only via Android 12+ window blur behind dialogs/sheets (`values-v31/themes.xml`, 20dp); the player never blurs live video (translucent dark glass, `*Plain` styles)
- Animations: `res/anim/` — `nav_push/pop_enter/exit` (calm fade-through, ~6% slide, used for activities and settings), `dialog_enter/exit` (0.96 scale pop). Android 12+ blur behind dialogs/sheets is 16dp (`values-v31/themes.xml`); player uses `*Plain` styles without blur

## v2.12 Midnight Glass design system
- **Atmosphere** (every non-player screen): theme attrs `atmoTop/atmoMid/atmoBottom/atmoGlowA/atmoGlowB`, drawn by `AtmosphereDrawable` as the window background. Status bar is transparent over it. Never paint flat `colorSurface` blocks over it; opaque layers that must hide content during animations use `Atmosphere.viewBackground(view)`.
- **Glass levels**: L1 `glassSurface` (chips, tonal buttons, thumbnails placeholders, grid tiles), L2 `glassSurfaceElevated` (settings group panels, collection header, search field, skeleton), L3 `glassSurfaceStrong` (dialogs, sheets, popups). All with `glassStroke` hairline + `glassHighlight` sheen. Long lists stay clean: list rows have no card (`bg_item`), grid tiles are L1 glass (`bg_item_grid`).
- **Pill navigation**: `navContainer` capsule (`bg_nav_pill_container`), `navSelected` pill (near-white in dark, ink in light), `navOnSelected`, `navText`. Chips use `Widget.Vela.NavPill.Chip`.
- **Player**: top bar = glass capsule (`bg_player_cluster`), bottom = glass dock (`bg_player_dock`), play = glass orb (`bg_player_play`) with AVD morph (`avd_play_to_pause`/`avd_pause_to_play`), speed/aspect = `Widget.Vela.PlayerPill`; `TimelineSeekBar` gradient progress + glowing white-cored thumb + glide; `PlayerSheet` float-in spring + row stagger + accent-glass selected rows. Never blur live video; gesture/seek logic untouched.
- **Settings**: grouped glass panels (`SettingsPanelDecoration`), small-caps section headers (`TextAppearance.Vela.Section`, via `VelaPreferenceTheme`). Vault settings build the same panels in code (`header()` opens a panel, rows go inside).
- **Light mode**: generated "frosted" palette — pale blue-grey atmosphere, white glass, dark ink nav pill.
- **Theme generator** (`tools/themegen/Gen.java`) now takes per theme: accent seed, secondary, tertiary, background tint, glow A, glow B, strength, mono flag. Sections: Signature, Jewel, Warm, Cool, Neon, Soft & Light. Re-run it after changing themes (command above).
- Themes (35): Midnight Glass (default), Obsidian Noir, Aurora Glass, Arctic Silver, Graphite Mist · Sapphire Depths, Violet Nebula, Emerald Aura, Crimson Eclipse, Amethyst Night, Ruby Noir, Golden Eclipse, Royal Indigo · Sunset Ember, Rose Gold Luxe, Copper Dusk, Amber Glow, Coral Reef, Mocha Velvet, Sakura Bloom · Ocean Teal, Glacier Blue, Mint Frost, Deep Forest, Nordic Fjord, Lavender Haze, Steel Blue · Cyber Cyan, Neon Tokyo, Synthwave, Toxic Lime, Electric Blue · Pearl Champagne, Blush Quartz, Sky Porcelain.

## v2.12 motion map (one language, different gesture per interaction)
| Interaction | Motion |
|---|---|
| Tab change | nav pill slides/stretches on `spring` (520 ms), text colour travels with it; content slides (TabSwipeLayout, unchanged) |
| Search open/close | `SearchMorph` (reveal from icon, sweep, depth pulse) / exact reverse + icon stagger |
| Search results / list edits | `VelaItemAnimator` staggered rise, DiffUtil moves (no jumps) |
| Grid ↔ list | `Motion.crossSwap` + icon spin-swap |
| Open folder/playlist / back | `Motion.zoomThrough` forward (94%→100%) / back (105%→100%) + blur-to-sharp |
| Play a video | system scale-up from the tapped thumbnail (`ActivityOptionsCompat.makeScaleUpAnimation`) |
| Screens (activities, settings) | `nav_push/pop_*`: depth fade-through (95%↔103% scale, 3% drift) |
| Dialogs | `dialog_enter/exit` float-in (92% + 4% rise) |
| Hidden Vault | `vault_enter` (108%→100%) / `vault_hold_exit`; lock orb settle + blur-in; unlock = library zoomThrough |
| Theme apply | snapshot dissolves with 4% zoom; gallery cards stagger; preview crossSwap |
| Empty / loading | glass orb icon + reveal with blur; `SkeletonView` shimmer |
| Player | controls slide/scale (unchanged), AVD play/pause, sheet spring float-in |

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
- v2.10 ThemeSwitch.kt: home toolbar sun/moon (action_theme, next to search) toggles theme pref dark/light; snapshot + circular reveal overlay after recreate, icon spin-pop.
- v2.10.1 grid/list toggle (action_view) showAsAction=always so it stays on the toolbar beside the theme button.
- v2.10.2 home toolbar order fixed by user: theme, search, grid/list, sort, overflow (all always visible). Never drop home toolbar items.
- v2.12 Midnight Glass design system + cinematic motion (visual only; see sections above). Default theme Midnight Glass; v2.11 keys migrate via `ThemeManager.LEGACY` (explicit `pure_black` → Obsidian Noir; installs that never picked a theme get Midnight Glass). Not device-tested at release time.
- v2.11 Premium black glass UI + theme gallery (visual only): glass system (attrs/styles/Glass.kt/VelaDialogBuilder), deep black/charcoal surfaces per theme, frosted light mode, 26 themes in 5 sections (Glass Dark, Vivid, Warm, Cool, Light-friendly), default = Pure Black Glass (installs that never picked a theme move to it; any explicit old pick is kept), gallery with live preview (tap = preview, tap again / Apply = apply with cross-fade recreate via `ThemeSwitch.prepareCrossfade`), saved custom themes with edit/duplicate/rename/delete, Advanced (secondary accent + background/glass base, quantised to nearest tuned palette), Reset. New pref keys only: `theme_key`, `theme_secondary`, `theme_base`. Not device-tested at release time.
