# Patch: QR-code pairing (show + scan, fully in-app)

Replaces typing an IP address. Merge by copying these files over the project (paths relative to the repo root).

**Apply after the Expressive UI patches and the stability patch** (it builds on both).

## How it works
- **Show my code:** the QR text is `share64://c?v=1&i=<device id>&n=<name>&t=<p|t|o>&p=<port>&a=<ip>,<ip>`
  (about 100 bytes, a version 6 to 8 code). It lists every LAN address of the device (home Wi-Fi *and* hotspot).
- **Scan code:** in-app camera. The scanner probes all addresses in the code at once, keeps the one that answers,
  and adds the device under its real id (so resume survives IP changes).
- **Both sides see each other:** the scan's handshake makes the shown-code device add the scanner back
  (new engine event `PeerSeen` + a reverse probe on the default port). Discovery that only worked one way heals itself too.
- **Safe by design:** only private LAN addresses (10/8, 172.16/12, 192.168/16) are accepted from a code, a code
  from a newer format asks for an update, other QR codes are rejected politely, scanning your own code is caught.
- **No protocol change.** Older peers still interoperate; the extra reverse probe is an ordinary handshake.

## Animations
Screen rises like a sheet; tabs slide with a spring; the code builds module by module (corner eyes first, then a
ripple from the centre with overshoot) with pulse rings behind it; the viewfinder has breathing corner brackets and a
sweeping scan line that spring inward on lock-on, with haptic feedback; connecting spinner; failed scans shake the
error card; success pops a cookie, bursts dots and draws the check mark.

## Changed / new files
| Area | Files |
|---|---|
| Engine (C++) | `types.h` (`PeerSeen` event), `net.h/.cpp` (`peer_ipv4`), `receiver.cpp` (emit it), `host_test.cpp` (+1 test) |
| Core | `QrCode.kt`, `QrPayload.kt`, `QrConnect.kt` (new, pure Kotlin), `Share64Node.kt` (`connectFromQr`, `connectInfo`, visitor reverse-probe), `DiscoveryManager.kt` (`addSeenPeer`), `TransferEngine.kt` / `TransferEvent.kt` (`peerSeen` flow), 3 new test files |
| Phone | `ConnectScreen.kt`, `QrViews.kt`, `QrScanner.kt` (new), `App.kt` (navigation), `HomeScreen.kt`, `Components.kt`, `Sheets.kt`, `ShareIcons.kt`, `MainViewModel.kt`, `AndroidManifest.xml`, `build.gradle.kts` |
| TV | `TvQr.kt` (new), `TvHomeScreen.kt`, `TvViewModel.kt`: the TV shows its code (it has no camera) and closes the dialog when the phone connects |
| Gradle | `gradle/libs.versions.toml` |

## New dependencies
CameraX 1.4.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) and ZXing core 3.5.3 (pure Java, no Google
services; used only to *read* codes). The code generator is our own. About +1.5 MB. New permission: `CAMERA` (the camera
feature is optional, so phones/tablets without one still install and can show their code).

## Home screen changes
"Add by address" became "Connect with QR" (section header, FAB menu, send sheet). Typing an address is still there as a
small fallback: Connect > Scan code > "Can't scan? Type the address". On the TV the manual-address dialog is replaced by the QR dialog.

## Verified here
- **QR generator:** matrices identical to an independent encoder (python-qrcode, same version/level/mask) for 202 cases
  covering every version 1 to 40 and all four levels; the codes decode with OpenCV's reader. 8 tests.
- Payload format, address filtering and parallel probing: 16 more tests. All 78 JVM tests (incl. the old ones) pass.
- Engine: full suite incl. the new `PeerSeen` test passes under ASan + UBSan and ThreadSanitizer (no reports).
- Core-module Kotlin type-checks against the Android 13 SDK stubs.

## Not verified
- Compose UI, CameraX and ZXing code: the libraries are not available offline, so these files were checked for syntax and
  imports only, not compiled. The new Gradle dependencies were not resolved here.
- Nothing ran on a device: camera focus/exposure, scan speed on a real screen, and hotspot behaviour are untested.
