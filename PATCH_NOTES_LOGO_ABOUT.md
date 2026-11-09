# Patch: your logo + About page

Unzip over the repo root (overwrites/adds the files below; nothing has to be deleted).

## Logo
- Phone + TV launcher icon: adaptive icon (your mark on #070607) + themed-icon (monochrome) layer
  (`mipmap-*/ic_launcher_fg.png`, `ic_launcher_mono.png`, `mipmap-anydpi-v26/*.xml`,
  `values/ic_launcher_background.xml`). TV also gets legacy PNG icons for Android 7-7.1.
- TV banner (Leanback launcher): `app-tv/.../drawable-xhdpi/tv_banner.png` made from your full logo.
- Notification icon: `core-app/.../drawable-*/ic_stat_logo.png` (white silhouette of the mark).
- In-app logo: `core-app/.../drawable-nodpi/logo_mark.png` (transparent).
- `branding/`: icon-512.png (Play Store / GitHub), tv-banner-1280x720.png, logo-full.png.

## About
- Settings -> "About SHARE64" opens the About page: logo, version chip, ABI chip (ARM64 / ARM32 / x86_64),
  links (Source code, Releases, Report a bug), tagline, DEVELOPER card, CONTRIBUTORS list.
- Contributors come from the GitHub API for aravindrejidev/SHARE64 (public repo needed). Offline, private
  repo or no contributors -> an "open source / welcome" card is shown instead. Avatars come from GitHub
  with an initial-letter fallback. This is the only place the app uses the internet.
- TV: "About" button in the header opens a simple dialog (version, developer, repo URL).
- All names and links live in `core-app/.../AppInfo.kt` (REPO_NAME = "SHARE64" - change it if your
  repository is called something else).

## Optional clean-up (unused now)
- app-mobile/src/main/res/drawable/ic_launcher_foreground.xml
- app-tv/src/main/res/drawable/ic_launcher_foreground.xml
- core-app/src/main/res/drawable/ic_stat_share.xml
