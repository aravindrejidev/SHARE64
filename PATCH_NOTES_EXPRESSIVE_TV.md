# Patch: Material 3 Expressive TV UI

TV app only (`app-tv`). The phone app, core modules, Gradle files and CI are untouched.
Merge by copying these files over the project (paths are relative to the repo root).

## Changed files (all in `app-tv/src/main/java/com/share64/app/tv/ui/`)

| File | What changed |
|---|---|
| `TvTheme.kt` | Always-dark violet / magenta / lime palette (same family as the phone app), heavy type, bigger corners. |
| `TvExpressive.kt` | **New.** Spring helper, cookie shapes (`CookieBadge`), `PulseRings`, colour-blocking `cardPalette`, `TvWavyProgress`. Repeated from the phone app on purpose: the two apps are separate modules. |
| `TvIcons.kt` | **New.** Wifi, phone, TV, devices and arrow icons as tiny vectors. |
| `TvComponents.kt` | `FocusBox` is now the expressive D-pad block: on focus it springs up, its corners tighten, its colour flips to a bright accent and a ring appears. Colour-blocked `DeviceTile`, `ActionTile`, `TvTransferRow` (wavy progress, big %), pill switch in `TvToggleRow`, rounder scrollable `TvDialog`, pill `TvButton` with optional icon. |
| `TvHomeScreen.kt` | Cookie logo header with a status pill, error banner only when something is wrong, count badges, cookie header in the incoming-file and About dialogs. Focus handling (first tile gets focus) is unchanged. |
| `TvUpdateDialog.kt` | The download bar is now the wavy progress. One-line change. |

## Notes

- Same reason as the phone patch: no official Expressive artifacts (they need Compose 1.12, AGP 9 and
  compileSdk 37), so the design is built on the current Compose 1.9 / Material 3 1.4 APIs.
- `FocusBox` now takes `restColor` and `focusColor`. Only `TvComponents.kt` uses it.
- Not compiled here (no Android SDK): checked for syntax and imports only. If CI reports an error,
  send the log.
