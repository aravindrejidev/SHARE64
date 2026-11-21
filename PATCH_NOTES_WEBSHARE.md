# Patch: "Share with anyone" (browser download for people without SHARE64)

Merge by copying these files over the project (paths relative to the repo root).
**Apply after the Expressive UI, stability and QR patches.** No new dependencies or permissions
(one new `share64://w` link filter on `MainActivity`).

## How it works
- **Host:** Home > "+" menu > **Share with anyone** (or the same button in the "send to" sheet). Pick files; the
  phone starts a small HTTP server and shows a QR code with `http://<ip>:8080/s/<token>/`. Any camera app opens
  it in the browser. No website or hosting is involved, so it works on a hotspot without internet.
- **Browser side:** a single self-contained page (English + Malayalam, light/dark, no external requests). It asks the
  host for access, shows the file list, and each file is a plain download (Range/resume supported); several files also
  get "Download all as ZIP" (streamed, no temp file). Images, video and audio get a "View" button.
- **Approval:** each new device appears on the host phone with Allow / Decline (optional "let new devices in
  automatically"). Files and the list are only served to approved devices. Remove/decline cuts a running download.
- **SHARE64 users:** scanning the QR in the app's scanner is recognised and downloads through the app, with an
  Allow wait, file list, progress bar, resume, and saves into Downloads/SHARE64 (or your chosen folder). Android
  browsers also get an "Open in SHARE64" button on the page (`share64://w?u=...`); the app never downloads before you tap.
- **Stays alive:** the session lives in the foreground service (wake locks while a download runs, auto-stops after 30
  minutes without activity), shows a banner on Home, and stops when sharing is switched off.

## Safety
Random 71-bit token in the path (a wrong token looks like any missing page; guessers are throttled), only private
LAN clients are served at all, only the picked files are reachable (opaque ids, no paths), downloads of
HTML/SVG are forced to download, inline preview only for image/video/audio with a sandbox CSP, strict CSP on the page,
file names are inserted as text only. The page is plain HTTP (a self-signed certificate would only produce
browser warnings), so anyone who can sniff the Wi-Fi could see the link: that is why Allow is the default.

## Files
Server/page/link/client (pure Kotlin): `web/WebShareServer.kt`, `WebSharePage.kt`, `WebShareLink.kt`, `MiniHttp.kt`
(raw-socket HTTP client, so the app's cleartext policy stays strict), core-app `WebShareClient.kt`, `WebDownloader.kt`,
`WebShareHost.kt`, plus `ShareService.kt`, `ShareRuntime.kt`, `AppSettings.kt`; phone `WebShareScreen.kt`,
`WebDownloadOverlay.kt`, `MainViewModel.kt`, `App.kt`, `HomeScreen.kt`, `Sheets.kt`, `ShareIcons.kt`, `MainActivity.kt`,
manifest; 3 new test files.

## Verified here
- 112 JVM tests pass (incl. all earlier ones): server routing, approval flow, Range/HEAD/416, ZIP validity, unicode names,
  traversal attempts, throttling, remote filtering, idle expiry, 8 parallel downloads, 96 MB integrity (SHA-256) at ~550 MB/s
  on loopback; the app-side downloader against the real server (approval, decline, full, timeout, resume sends only the
  missing tail, retry after a dropped link, cancel, revoke).
- The real page runs in jsdom against the real server: waiting state, list, links return the exact bytes as attachments,
  ZIP link, hostile file name stays text, Malayalam UI, Android app link.
- curl/python cross-check: `curl -C -` resume, ranges, HEAD, 8 parallel downloads, path traversal, wrong token.
- Core modules type-check against the Android 13 SDK stubs.

## Not verified
- Compose screens, the ViewModel and `MainActivity` were checked for syntax/imports only (libraries unavailable offline).
- No real browsers: iPhone Safari / Chrome behaviour (download prompts, multiple downloads, ZIP handling) is untested.
- No device run: streaming from content URIs of every storage provider, Doze, and ZIPs over 4 GB (relies on the platform's ZIP64).

## Not included yet (ideas)
"Create hotspot" mode (Wi-Fi QR then link QR), per-file progress in the browser, a fast native-engine path for SHARE64 users,
uploading from a browser.
