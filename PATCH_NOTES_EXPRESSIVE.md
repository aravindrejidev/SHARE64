# Patch: Material 3 Expressive phone UI

Phone app only (`app-mobile`). The TV app, the C++ engine, Gradle files and CI are untouched.
Merge by copying these files over the project (paths are relative to the repo root).

## Changed files (all in `app-mobile/src/main/java/com/share64/app/mobile/ui/`)

| File | What changed |
|---|---|
| `Theme.kt` | Vivid violet / magenta / lime fallback palette, heavy type (Black/ExtraBold display + headline), 40 dp extra-large corners. Dynamic color and AMOLED unchanged. |
| `Expressive.kt` | Rewritten building blocks: spring motion helper, cookie shapes (`BlobShape`, `CookieBadge`, `CookieIconButton`), `PulseRings`, `popIn`, `BouncyCard`, `ExpressiveToggleGroup` (connected button group), `ScreenHeader`, colour-blocking `cardPalette`. `ExpressiveButton`, `WavyProgress`, `ExpressiveLoading`, `ExpressiveFabMenu`, `segmentedShape`, `SectionLabel` keep their signatures. |
| `Components.kt` | New `StatusHero`, colour-blocked `DeviceCard`, radar `EmptyDevices`, `TransferCard` with wavy progress and big percentage, rounder dialogs. `StatusBanner` was replaced by `StatusHero`. |
| `HomeScreen.kt` | Big heavy title with cookie settings button, hero card, springy staggered device cards, count badges, `animateItem()` reordering. |
| `Sheets.kt` | Send sheet with a spinning cookie header and colour-blocked device rows. |
| `SettingsScreen.kt` | Big title, cookie icon badges per group, connected toggle group for the theme. |
| `AboutScreen.kt` | Colour-blocked hero with spinning cookie logo, pill chips, cookie-shaped avatars. |
| `ShareIcons.kt` | Added `Wifi`, `Phone`, `Tv`, `Devices`, `ArrowUp`, `ArrowDown`, `Upload`. |

`UpdateSheet.kt` is unchanged: it picks up the new look through the shared components.

## Why custom components instead of the official Expressive artifacts

material3 1.5.x (the line that ships the Expressive APIs, currently `1.5.0-rc01`) depends on
Compose 1.12, which requires compileSdk 37 and AGP 9. This patch deliberately stays on the current
toolchain (material3 1.4.0, Compose 1.9.4, AGP 8.12, Kotlin 2.2) and builds the same design ideas
on stable APIs. A later toolchain upgrade can swap them for the official components one by one.

## Not verified

Written without an Android SDK, so it was checked for syntax and imports only, not compiled.
If CI reports a compile error, send the log and the fix will be a small patch.
