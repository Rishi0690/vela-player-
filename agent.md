# agent.md — Vela Player

Guide for AI coding agents (and humans) working on this repo.

## What this is
Vela Player is an offline Android video player written in Kotlin (package `com.hark.velaplayer`).
It is inspired by CloudStream's player: aspect-ratio modes, gestures, subtitles, audio tracks, playlists, Focus Mode, and theming.

- minSdk 24, targetSdk 34, AGP 8.3.2, Kotlin 1.9.23, Media3 (ExoPlayer) 1.3.1, viewBinding
- Current version: `versionName 2.18.1`, `versionCode 38` (last updated 2026-10-14)
- Repo: `Rishi0690/vela-player-` (private, branch `main`). Owner works from an Android phone only (no PC); keep instructions simple.

## Golden rules
1. **Never break existing playback.** Every change must keep the player, gestures, aspect modes, subtitles, audio tracks and queue working.
2. **Bump `versionCode`** (and `versionName`) in `app/build.gradle.kts` on every release so updates install over old versions.
3. **Never change `applicationId` or the signing key.** Releases are signed with `signing/vela-release.jks` (gitignored, not in this repo). Using a different key means users must uninstall and lose their settings.
4. **Do not enable minify or shrinking.** This is the owner's choice, and the Material palette APIs rely on it.
5. **Destructive actions need confirmation.** Deleting files always asks first. Removing an item from a playlist never deletes the file.
6. **Never remove anything from the Home page/toolbar when adding features.** Toolbar order (owner's choice): theme, search, grid/list, sort, overflow, all always visible.
7. **Always `git pull --ff-only` before editing** — the repo is also edited outside this workspace.
8. Everything stays on the device. No analytics or network calls (except user-entered stream URLs).

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
| `PlayerActivity.kt` | Main player UI: controls, gestures, timeline, sheets, queue, PiP, Focus pill and break card. `EXTRA_SHUFFLED` for shuffled queues. Window insets (system bars + display cutout) pad `controls`/`edgeOverlay`. `fitButtonRow()` adapts the bottom button row (`p23ButtonRow`) to its real width: pills drop icons, then icon buttons shrink 48→36dp, so the right lock button (`btnLockRight`) never overflows on narrow portrait screens |
| `PlayerHolder.kt` / `PlaybackService.kt` | Shared ExoPlayer instance and media session/background service |
| `ZoomableVideoLayout.kt` + `AspectMode.kt` | Aspect-ratio modes and pinch zoom (top-priority feature) |
| `TimelineSeekBar.kt` | Custom seek bar: buffered track, expanding thumb, frame preview hookup |
| `ItemSheet.kt` | v2.13 item options sheet for long-press / ⋮ on videos, folders, playlists (MainActivity `showSheet`, groups split by the `GROUP` marker) and Vault items (`VaultActivity.options`). Floating L3 glass card in a transparent `BottomSheetDialog` (`R.style.VelaFloatingSheet`: blur behind on 12+, dim-only window anim), header thumbnail (16:9) or accent icon orb + title + subtitle, groups = L1 glass panels separated by spacing, rows = accent icon chip + label, danger rows tinted with `colorError`, `springSoft` float-in + row stagger, `Motion.pressable`. Copies `FLAG_SECURE` from the activity (Vault). Tap = dismiss + run (same as before) |
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
| `WidgetModel.kt` | v2.14 widgets: `WidgetKind` (5 widgets, gallery sizes), `Shortcut`, per-widget `WidgetConfig` (follow theme / accent, glass opacity, corners, progress, prev/next, shortcuts, source) saved per appWidgetId in `WidgetStore` (prefs `vela_widgets`, + gallery "pending" config), `WidgetPalette` (theme → plain ints) |
| `WidgetData.kt` | `WItem`, `NowState` (LIVE player / RESUME last session / EMPTY; main-thread snapshot), `WidgetData` (real Store/PlayerSession/MediaStore data, vault + .ts filtering, missing-file check), `WidgetArt` (small rounded thumbnails with baked progress/duration, Now Playing art + scrim, progress bars, themed placeholders) |
| `WidgetRenderer.kt` | Builds every widget's RemoteViews (real widgets AND in-app previews, `Spec.preview`), glass tinting, PendingIntents |
| `WidgetSystem.kt` | `WidgetUpdater` (player listener, 15 s progress tick, theme/data/app hooks), `VelaWidgetProvider` + 5 providers, `WidgetActionReceiver` (controls → player's PiP control broadcast; pin callback; MY_PACKAGE_REPLACED), `WidgetListService` (list/grid factory) |
| `WidgetLaunchActivity.kt` | No-UI trampoline for widget taps: builds the real queue at tap time (same intent as `MainActivity.play`), opens library tabs (`MainActivity.EXTRA_OPEN`), Vault (own lock), player |
| `WidgetsActivity.kt` / `WidgetDetailActivity.kt` | In-app Widgets gallery (live previews, `WidgetPreview`, `WidgetStage`, `WidgetPreviewFrame`) and detail / configure screen ("Add to home screen" via `requestPinAppWidget`, or "Save widget" as the launcher's configure activity) |
| `Artwork.kt` | v2.14 theme-tinted "luminous glass forms" artwork (Widgets hero, Home empty state, Vault welcome) |

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
- **Player**: top/bottom bars have NO glass boxes since v2.15 (scrims only); play = glass orb (`bg_player_play`, dark contrast halo since v2.16.1) with AVD morph (`avd_play_to_pause`/`avd_pause_to_play`), speed/aspect = `Widget.Vela.PlayerPill`; `TimelineSeekBar` gradient progress + glowing white-cored thumb + glide; `PlayerSheet` float-in spring + row stagger + accent-glass selected rows. Never blur live video; gesture/seek logic untouched.
- **Settings**: grouped glass panels (`SettingsPanelDecoration`), small-caps section headers (`TextAppearance.Vela.Section`, via `VelaPreferenceTheme`). Vault settings build the same panels in code (`header()` opens a panel, rows go inside).
- **Light mode**: generated "frosted" palette — pale blue-grey atmosphere, white glass, dark ink nav pill.
- **Theme generator** (`tools/themegen/Gen.java`) now takes per theme: accent seed, secondary, tertiary, background tint, glow A, glow B, strength, mono flag. Sections: Signature, Jewel, Warm, Cool, Neon, Soft & Light. Re-run it after changing themes (command above).
- Themes (35): Midnight Glass (default), Obsidian Noir, Aurora Glass, Arctic Silver, Graphite Mist · Sapphire Depths, Violet Nebula, Emerald Aura, Crimson Eclipse, Amethyst Night, Ruby Noir, Golden Eclipse, Royal Indigo · Sunset Ember, Rose Gold Luxe, Copper Dusk, Amber Glow, Coral Reef, Mocha Velvet, Sakura Bloom · Ocean Teal, Glacier Blue, Mint Frost, Deep Forest, Nordic Fjord, Lavender Haze, Steel Blue · Cyber Cyan, Neon Tokyo, Synthwave, Toxic Lime, Electric Blue · Pearl Champagne, Blush Quartz, Sky Porcelain.

## Launcher icon (current: v2.16.1)
- Adaptive icon: `mipmap-anydpi-v26/ic_launcher(_round).xml` → `drawable/ic_launcher_background.xml` (WHITE since v2.16.3, owner's choice), `drawable-nodpi/ic_launcher_fg.png`, `drawable-nodpi/ic_launcher_monochrome.png` (themed icons, Android 13). Legacy PNGs `mipmap-{mdpi..xxxhdpi}/ic_launcher(_round).png` for API 24–25.
- Source = owner's artwork (gradient ring + play triangle, transparent): `tools/icongen/user_icon_source.svg` → rendered to `user_icon_source.png` with cairosvg (1254 px). Generate everything with `python3 tools/icongen/gen_user_icon.py` (numpy + Pillow). Never hand-edit outputs. To replace the icon: render the new art to `user_icon_source.png` and re-run.
- `tools/icongen/gen_icon.py` is the OLD v2.13 procedural icon generator (unused now).
- No letters/app name in the icon (the name may change). No in-app place shows the logo.

## v2.14 Home-screen widgets
- **Widgets** (AppWidgetProvider + RemoteViews, all real data, no fake controls):
  - **Now Playing** (merges "Playback controls"): Compact bar (4x1/2x1: art, title, play/pause, next/prev when wide), Square (2x2: full-bleed art + scrim, play orb), Medium (4x2: art, label, title, live Chronometer time, progress, prev/play/next), Large (4x3+: 16:9 art). Size picked from launcher options (`npSize`: h<100 bar, w<200 square, h<200 medium, else large), re-rendered on resize. States: LIVE (real player), RESUME (last `PlayerSession`, play = resume through the app), EMPTY (opens library). Vault videos show "Private video" with no artwork.
  - **Continue Watching** (ListView via `WidgetListService`; thumbnails with baked progress, "x min left"; tap = player with the continue queue, `SOURCE_CONTINUE`).
  - **Quick Access** (4 configurable shortcuts: Resume, Continue, Videos, Folders, Favorites, Playlists, Vault (opt-in, opens behind its lock); row layout when h<100, grid otherwise; first tile = accent "hero").
  - **Recently Added** (GridView, columns auto_fit 128dp → 2 compact / 3–4 large; needs video permission, otherwise an honest empty state; tap = play that video, `SOURCE_ALL`).
  - **Playlist & Favorites** (configure chooses Favorites or a playlist; Play all / Shuffle in the header; deleted playlist → "tap to choose another" reopens settings).
- **Controls**: `WidgetActionReceiver` re-sends `PlayerActivity.ACTION_PIP` + `CTRL_*` (the PiP path: savePosition, queue rules). Play acts in place only when the player is visible or background audio is on (`WidgetUpdater.canPlayInPlace`); otherwise it opens the player (`EXTRA_WIDGET_PLAY` starts playback, never in vault mode). Prev/next are dimmed and inert at queue ends, hidden without a player.
- **Updates**: `PlayerHolder.create/release` → `WidgetUpdater.attach/detach`; player events re-render Now Playing (debounced 250 ms); while playing with the screen on, a partial update every 15 s refreshes time + progress (the Chronometer ticks by itself). Store / vault changes → list widgets (throttled to 20 s). Library grid: app start, theme, `updatePeriodMillis` 3 h. `PlayerActivity.onNewIntent` with no queue now only brings the player to the front (also fixes the media-notification tap resetting vault mode).
- **Theme sync**: `WidgetPalette.resolve` reads `ThemeManager.selection` → `ThemeManager.palette` / `customPalette` for an explicit light/dark configuration context (custom colours included); never a second theme system. `VelaApp` → `WidgetUpdater.install` listens to `theme`, `theme_key`, `accent_seed`, `theme_secondary`, `theme_base`, `theme_version` and re-renders all widgets; also app start, `VelaApp.onConfigurationChanged` (night mode), MY_PACKAGE_REPLACED. In "System" mode on Android 12+ both palettes are sent (`RemoteViews.setColorInt` day/night) so widgets switch with the system even when Vela isn't running (bitmaps keep the mode they were drawn in until the next update). Per-widget "Follow app theme" (default) or a custom accent (contrast-nudged to ≥3:1).
- **Glass in RemoteViews** (no blur possible): `layout/wg_bg.xml` = six WHITE layers (fill, bottom wash, two light pools, sheen, 1 dp hairline) tinted at render time (`ImageView.setColorFilter` + `setImageAlpha`). Corner sets `r1` (24 dp; Android 12+ `@android:dimen/system_app_widget_background_radius`), `r2` 34 dp, `r3` 12 dp. GENERATED by `tools/widgetgen/gen_widget_res.py` (also the plain white `wgi_*` icons: launcher-inflated views can't resolve `?attr` tints, so never use the app's `ic_*` icons in widgets). Only RemoteViews-safe classes in `wg_*` layouts (FrameLayout, LinearLayout, ImageView, TextView, Chronometer, ListView, GridView; no `<View>`/`<Space>`).
- **Bitmaps**: one art bitmap per update (≤ ~400 px long side), list thumbnails per item (88×50 dp, ≤2.5x density) → far below the binder limit. `WidgetArt` caches Now Playing art so ticks never re-decode.
- **Widgets page**: Settings › Widgets (`open_widgets`) and Home › ⋮ › Widgets (`action_widgets`, overflow only; toolbar untouched). Gallery cards render the REAL layouts with real data in the current theme (`WidgetPreview.render` → `RemoteViews.apply`, lists filled inline via `previewItems`). Detail: big live preview, size chips (preview only; real size = resize on the home screen), Style (Follow app theme / accent swatches, Glass: Clear/Balanced/Frosted/Solid, Corners: Launcher/Round/Crisp), Content options per widget, "Add to home screen" (`requestPinAppWidget`, callback → `ACTION_PINNED` adopts the pending config; unsupported launchers get step-by-step instructions). It is also every widget's `android:configure` activity (`reconfigurable|configuration_optional`).
- **Picker previews** (v2.16.3): `previewImage` = `drawable-nodpi/wg_preview_*.webp`: OPAQUE glass card filling the whole image (16dp corners), drawn natively at the Xiaomi HyperOS picker box aspect (that picker ignores previewLayout and FIT_CENTERs the image in a white box sized to ITS sizes: 4x1/4x2 -> ~2.16:1, 4x3 -> ~1.03:1). Any see-through pixel shows the white box, so cards are flattened onto an opaque base and, on Android 12+, `previewLayout` = `layout/wg_pvl_*.xml` (glass `drawable/wg_pv_bg.xml` that fills any picker box + transparent content image `wg_pv_*.webp` fitCenter on top) so the picker never shows white strips. All images GENERATED by `tools/widgetgen/gen_previews.py . [showcase.png]` (needs numpy + Pillow); regenerate after layout changes.
- **In-app previews**: `WidgetPreview.render` applies RemoteViews with the APPLICATION context (the activity inflater has AppCompat's factory, whose AppCompatImageView/TextView setters are not @RemotableViewMethod, so apply threw and previews were blank). If a live preview still fails, `WidgetPreview.staticPreview` shows the picker artwork.

## v2.14 Artwork
- "Luminous glass forms" (original, procedural): lit glass ring, two stacked glass screens with a play glint, light ribbons, bokeh. `tools/artgen/gen_art.py . [preview.png]` (numpy + Pillow) → `drawable-nodpi/art_forms_a|b|hi.webp` (white alpha layers) + `art_luminous_forms.webp` (merged, for widgets).
- `Artwork.drawable()` tints the layers with the live theme (accent, second glow; deeper ink in light mode). Used on the Widgets hero, the Home empty state (behind the glass orb) and the Vault welcome (behind the vault mark). Widget placeholders (`WidgetArt`) use the same art when a video has no thumbnail. Real thumbnails always win.

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
| Item options (long-press / ⋮) | `ItemSheet`: dim fades, glass card rises 56dp + 94%→100% on `springSoft`, header then rows stagger (`STAGGER`) |
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
- Hidden Vault entry is the 5 s hold on the Home title. v2.14 adds one opt-in exception: a "Vault" Quick Access widget shortcut the user must pick themselves (off by default); it opens VaultActivity, which still authenticates first.
- Commit messages: `vX.Y: short summary of what changed`.

## Release checklist
1. Make the change. Keep playback intact.
2. Bump `versionCode` / `versionName`.
3. `assembleRelease` with the release keystore and verify the signing cert is unchanged.
4. Commit and push to `main`.
5. Upload the signed APK to Google Drive as `VelaPlayer-vX.Y.apk` (anyone-with-link reader) and update this file's version line + history.

Signing cert SHA-256 starts `407d7a6d75c25fb2` (alias `androiddebugkey`). Check with `apksigner verify --print-certs`.

## Recent history
- v2.5 Vault private storage · v2.5.1 single-thumbnail covers · v2.5.2 slimmer Vault settings
- v2.6 settings transition fix + new motion · v2.6.1 calmer fade-through, lighter dialogs/blur
- v2.7 Settings navigation root-cause fixes (rapid taps, GPU layer, reduced motion)
- v2.8 Settings: categories open with hide/add (main screen no longer re-inflated on Back), search field is an overlay (no layout jump), no row cross-fade on toggles, title cross-fade. VelaScroller everywhere, themed native scrollbars, Motion.enabled/exit/reveal (reduced motion), animated empty states, Vault lock panel lowered via 40/60 spacers + ease-in, Vault settings: removed "Storage & uninstalling" and "About hiding" sections

## Version log (v2.8.1 → v2.14)
Original 2026-10-13 ask, mostly delivered: full UI/UX + motion polish across the app: consistent motion system, design tokens (spacing, radii, type), light/dark/accent consistency, clearer empty/error/loading states, scroll/thumbnail performance. Keep every feature working.
- v2.8.1 Vault settings › Privacy › "Allow screenshots" (VaultAuth.allowScreenshots/applySecure; off = FLAG_SECURE in vault, vault settings, vault playback; also gates frame capture)
- v2.8.2 Settings ListPreference/EditTextPreference pop-ups replaced by Material dialogs (showVelaPrefDialog in SettingsActivity via onDisplayPreferenceDialog)
- v2.9 FitButtons.kt (Play all/Shuffle single-line fit), no reorder drag hint subtitle, rotation = 3 modes auto(FULL_SENSOR)/landscape/portrait shared via Prefs default_orientation (button cycles + saves; player listens to prefs; Settings refreshes onResume). Aspect Auto no longer forces rotation.
- v2.10 ThemeSwitch.kt: home toolbar sun/moon (action_theme, next to search) toggles theme pref dark/light; snapshot + circular reveal overlay after recreate, icon spin-pop.
- v2.10.1 grid/list toggle (action_view) showAsAction=always so it stays on the toolbar beside the theme button.
- v2.10.2 home toolbar order fixed by user: theme, search, grid/list, sort, overflow (all always visible). Never drop home toolbar items.
- v2.12 Midnight Glass design system + cinematic motion (visual only; see sections above). Default theme Midnight Glass; v2.11 keys migrate via `ThemeManager.LEGACY` (explicit `pure_black` → Obsidian Noir; installs that never picked a theme get Midnight Glass). Not device-tested at release time.
- v2.13 Item options sheet restyled (`ItemSheet.kt`, also Vault item options which were a plain list dialog), grouped rows (Delete moved to its own last group; all actions and behaviour unchanged), multi-select ActionMode bar = glass capsule (`bg_action_mode`, `Widget.Vela.ActionMode`), rename/new-playlist fields = glass (`Glass.styleField`). New launcher icon (see "Launcher icon"). Not device-tested at release time.
- v2.14 Premium widgets (Now Playing, Continue Watching, Quick Access, Recently Added, Playlist & Favorites), Widgets gallery + detail/configure screen, theme-synced widget glass, original artwork (Widgets hero, empty state, Vault welcome). See "v2.14 Home-screen widgets". Not device-tested at release time.
- v2.11 Premium black glass UI + theme gallery (visual only): glass system (attrs/styles/Glass.kt/VelaDialogBuilder), deep black/charcoal surfaces per theme, frosted light mode, 26 themes in 5 sections (Glass Dark, Vivid, Warm, Cool, Light-friendly), default = Pure Black Glass (installs that never picked a theme move to it; any explicit old pick is kept), gallery with live preview (tap = preview, tap again / Apply = apply with cross-fade recreate via `ThemeSwitch.prepareCrossfade`), saved custom themes with edit/duplicate/rename/delete, Advanced (secondary accent + background/glass base, quantised to nearest tuned palette), Reset. New pref keys only: `theme_key`, `theme_secondary`, `theme_base`. Not device-tested at release time.

## v2.15 (versionCode 30)
- Player: removed the big glass boxes behind topBar (bg_player_cluster) and bottomBar (bg_player_dock); taller scrims keep icons readable.
- Settings: RootFragment.padForSearch re-anchors to top when padding arrives after first layout (was the "opens scrolled down" bug); fresh open scrolls to 0.
- "Hide in vault" removed from main-app selection menu (sel_hide) and folder sheet; hiding is only via Vault › Add videos (VideoPicker EXTRA_VAULT_PICK). confirmHide kept unused.
- Launcher icon = user's artwork (tools/icongen/gen_user_icon.py + user_icon_source.png), white background, PNG fg/monochrome in drawable-nodpi.

## v2.16 (versionCode 31)
- Search light bug fixed in Motion.lightSweep: was unclipped rotated rect (square light past pill ends, drifting), and overlapping sweeps on rapid open/close. Now clipped to rounded shape, one per view (tag_light_sweep), fade in/out, edge rim light, bounds follow resize.
- SearchMorph: layered glass field, focus rim light (once per focus), glyph lift, clear-button pop, field rise on open.

## v2.16.1 (versionCode 32)
- Icon from user transparent SVG (tools/icongen/user_icon_source.svg -> png via cairosvg), transparent adaptive bg.
- Play orb: dark halo + core under glass (bg_player_play) for bright frames.
- PlayerActivity.fitButtonRow: bottom button row adapts (pill icons drop, icon buttons 48->36dp) so btnLockRight never overflows; bottomBar margins reduced.

## v2.16.2 (versionCode 33)
- Widgets gallery/detail previews were blank (RemoteViews.apply on the AppCompat inflater threw): now applied with app context + static fallback.
- Launcher widget picker: transparent card-only previewImage at target aspect + Android 12+ previewLayout with full-bleed glass background (no white side strips).

## v2.16.3 (versionCode 34)
- Widget picker white strips (HyperOS screenshot): previewImage now opaque, at the picker's 2.16:1 / 1.03:1 box aspect, flattened (no semi-transparent hairline/chips).
- Launcher icon background white (adaptive bg #FFFFFF, legacy PNGs on a white plate); transparent bg rendered black on launchers.

## v2.17 (versionCode 35)
- Video long-press (all library tabs, folders, playlists, favorites, Vault): long-press = select (starts selection or adds); long-press on an already-selected video = its options sheet; ⋮ still opens options directly; tap toggles while selecting; reorder drag unchanged. Settings `long_press_action` choice replaced by an info row (pref still read for playlist long-press). Continue-watching strip has no selection mode, so its long-press still opens options.

## v2.17.1 (versionCode 36)
- Long-press on an already-selected video while 2+ are selected opens a selection options sheet (MainActivity.onSelectionOptions / VaultActivity.selectionOptions); every action runs on the whole selection via shared runSelAction(id) + selActionVisible(id) (same code as the action bar). Rename is single-only (omitted, noted in header); Properties/Details show a combined summary. With 1 selected, the normal single-video sheet opens. ⋮ always opens that one video's sheet.

## v2.18 Background photos (versionCode 37)
- `Backdrop.kt` = the ONE app-wide background system. `Atmosphere.applyTo` / `Atmosphere.viewBackground` call `Atmosphere.backdrop()` which returns `BackdropDrawable` (photo) when a photo is set, else `AtmosphereDrawable`. Never add per-screen backgrounds. Player excluded.
- Prefs: `bg_source` ("" theme | "b:<id>" | "custom"), `bg_style` clear|glass, `bg_blur` 0-100, `bg_dim` 0-90, `bg_custom_stamp`, `bg_version` (ThemedActivity recreates stale screens on resume; the changing screen calls `backdropChangedHere()`).
- Drawable: centre-crop to window frame (align for settings layers), adaptive theme-tinted scrim = max(user Dim, what photo luminance needs), top/bottom bands, glass = downscale+stack blur, 1.18 saturation, theme glow + grain. Processing cached in memory; prewarm in VelaApp.
- 12 built-ins `drawable-nodpi/bgp_*.webp` (AI-generated originals: 6 "Vela Originals", 6 "Cinematic collection"). Custom photo copied to files/backdrop/custom.jpg (<=1600px, EXIF rotation) via GetContent (no permission).
- v2.18.1: multiple gallery photos (`c:<file>` sources, files/backdrop/c_*.jpg; legacy `custom` still works), `Backdrop.customs/deleteCustom` (active photo deleted -> default background), `restoreDefaults` (theme bg + Blurred glass + default blur/dim, photos kept). UI: "Restore to default" button under the intro, bin badge + long-press delete on photo tiles, both confirmed.
- UI: `BackgroundActivity` (Settings › Appearance › Background `background_editor`, Theme editor › Background photo button). Not device-tested.

## Open work (not done yet)
- Search redesign remainder (asked 2026-10-13): dedicated results layout with richer thumbnails/metadata, initial (no-query) state, richer empty state, recent searches only if backed by real data, fuller open/close choreography beyond the circular reveal.
- Nothing is device-tested in CI; there is no emulator in the build sandbox. Owner tests on the phone and reports back.
