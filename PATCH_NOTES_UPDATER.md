# Patch: in-app updater

Unzip over the repo root. Nothing has to be deleted.

## How it works
1. When the app opens (at most every 6 hours; can be switched off in Settings) it asks GitHub for the
   latest *release* of aravindrejidev/SHARE64 (prereleases and debug builds are ignored).
2. If the tag (e.g. v1.1.0) is newer than the installed version, an "Update available" sheet opens:
   old -> new version, size, ABI, date, and the notes from **Relece_notice.md** (repo root).
3. "Update now" downloads the APK that matches the device (phone/TV app, its ABI, else universal),
   checks size + SHA-256 against the release's SHA256SUMS.txt, then hands it to Android's installer.
   Android itself refuses any APK that is not signed with the same key as the installed app.
4. Settings -> Updates: "Check for updates" and "Check when the app opens". "Skip this version" is remembered.

## Release notes file
- `Relece_notice.md` in the repo root (the spelling you asked for; `Release_notice.md` also works).
- The app reads it at the release's tag, then falls back to the `main` branch. Edit it BEFORE running the
  Release workflow. HTML comments are hidden. Supported: # headings, - bullets, 1. lists, **bold**,
  *italic*, `code`, [links](url), > quotes, --- rules.
- A starter file for 1.0.0 is included - replace its text for each release.

## Requirements / things to know
- The GitHub repo must be PUBLIC (the app reads the API and raw files without a token).
- Every release must be signed with the SAME keystore. If an APK signed with the debug key is installed,
  uninstall it once before installing a release-signed one.
- First update: Android asks once to allow "Install unknown apps" for SHARE64; the app guides you there.
- AppInfo.REPO_NAME must match your repository name (default SHARE64).

## Files
core-app: Version, MiniJson, ReleaseModels, MarkdownLite, UpdateModels, UpdateClient, UpdateInstaller,
          UpdateInstallReceiver, UpdateManager (+ AppSettings, AppInfo, manifest) and 6 unit-test files
app-mobile: ui/UpdateSheet, ui/Markdown, App, SettingsScreen, ShareIcons, MainActivity, MainViewModel
app-tv: ui/TvUpdateDialog, ui/TvHomeScreen, TvMainActivity, TvViewModel
