# Vela Player

Feature-rich Android video player built with Kotlin and Media3 ExoPlayer.

- Aspect modes: Fit, Stretch, Zoom, Fill, Original, 16:9, 4:3, 21:9, Auto + pinch zoom
- Gestures: double-tap seek, brightness/volume swipe, horizontal seek, lock
- Subtitles (embedded + SRT/VTT/ASS/SSA/TTML), audio tracks, speed 0.25x-4x
- Local library, Continue watching, Favorites, Playlists
- HTTP/HLS/DASH streams, PiP, background audio

Build: `gradle assembleRelease` (minSdk 24, targetSdk 34).

## v2.0
- Timeline redesign (grows while dragging, frame preview + jump amount), soft shadows on white controls, single aspect button.
- New option panels for subtitles, audio, aspect ratio and player options (side card in landscape, bottom sheet in portrait).
- Theme editor: presets, color picker, HEX, saved themes, light/dark/system (Android 12+ builds a full palette from any color; older versions use the closest preset).
- Grid/List views per screen, playlist collage covers, multi-select with bulk actions, multi-video playlist picker (+ import files), Play all / Shuffle headers.
- Focus Mode: real-time focus timer (only while actually playing), goals, break reminders, optional Do Not Disturb, session summary and a stats dashboard. Data stays on the device.
- Settings reorganized into six categories.
