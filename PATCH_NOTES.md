# Patch: Expressive UI + new Settings

Unzip over the repo root (overwrites the files listed below, adds the new ones). No file needs deleting.

## Changed
- core-network/.../Share64Node.kt          preferredPort parameter
- core-app/.../AppSettings.kt              port, download folder, theme mode, dynamic color, AMOLED
- core-app/.../FileExporter.kt             export to a chosen folder (SAF) or Downloads/SHARE64
- core-app/.../ShareRuntime.kt             remembers where each received file was saved
- core-app/.../ShareService.kt             uses the port + folder settings
- core-app/.../ShareNotifications.kt       "Saved to <location>" text
- core-app/.../TransferPresenter.kt        shows the save location on finished transfers
- app-tv/.../TvViewModel.kt                shows the save location (TV UI otherwise unchanged)
- app-mobile/.../MainActivity.kt, MainViewModel.kt
- app-mobile/.../ui/Theme.kt, Components.kt, Sheets.kt, HomeScreen.kt

## New
- core-app/.../ThemeMode.kt, PortRules.kt, DownloadLocations.kt (+ unit tests)
- app-mobile/.../ui/SettingsScreen.kt, App.kt, Expressive.kt, ShareIcons.kt

## Where do received files go?
1. While arriving: <app storage>/Android/data/com.share64.app/files/Received/.share64/<id>.part
2. When complete and SHA-256 verified: moved to the Settings > Download folder (default:
   Downloads/SHARE64) and the app-private copy is deleted. Name clashes become "name (1).ext".
3. Android 9 or older (some TVs): the file stays in Android/data/com.share64.app/files/Received.
Sending never copies anything: the original file is read in place.
