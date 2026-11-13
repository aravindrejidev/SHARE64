# SHARE64 architecture and development notes

This page is for people who want to understand, build or change SHARE64. If you only want to use the
app, the [README](../README.md) is all you need.

## Modules

```
:core-cpp      C++17 transfer engine (CMake / NDK) + JNI bridge + Kotlin Flow wrapper
:core-network  Device discovery (NSD), network watching, one Share64Node object for the apps
:core-app      Shared by phone and TV: foreground service, notifications, settings, file export,
               in-app updater, release-notes Markdown parser
:app-mobile    Phone app: Jetpack Compose, Material 3 + dynamic color
:app-tv        Android TV / Fire TV app: Compose, dark 10-foot theme, D-pad focus
```

Both apps use the application id `com.share64.app` (an install of one replaces the other on the
same device, which is fine because each device is either a phone or a TV).

### `:core-cpp`

```
src/main/cpp/include/share64/   public headers (types.h, engine.h, sha256.h)
src/main/cpp/src/               engine, sender, receiver, session, protocol, net, SHA-256, JNI bridge
src/main/java/com/share64/core/ TransferEngine.kt (Flows) + TransferEvent.kt
src/hosttest/host_test.cpp      loopback + fault-injection suite (runs on any Linux machine)
```

### `:core-network`

```
Share64Node.kt        engine + discovery in one object (what the app modules use)
DiscoveryManager.kt   NsdManager advertise/discover, serialized resolver, TCP liveness, auto-restart
NetworkWatcher.kt     LAN IPv4 changes (Wi-Fi, Ethernet, hotspot)
PeerTxt / PeerRegistry / AddressParser / HandshakeProbe / LocalAddresses   pure Kotlin, unit-tested
LocalNetworkAccess.kt Android 17 ACCESS_LOCAL_NETWORK helper (only matters once targetSdk >= 37)
```

Devices advertise the DNS-SD service type `_share64._tcp`. The default receiver port is `52364`
(configurable from 1024 to 65535; if the port is busy the engine falls back to a free one).

### `:core-app`

```
ShareService          foreground service that owns the node; Wi-Fi and wake locks while transferring
ShareNotifications    progress, Accept / Decline for incoming files, "Saved to <location>"
FileExporter          moves finished files to the chosen folder (SAF) or Downloads/SHARE64 (MediaStore)
AppSettings           user preferences shared by phone and TV
Update*.kt            in-app updater (see below)
MarkdownLite          small Markdown subset used for release notes
```

## Transfer engine

One `Engine` is one receiver listener plus any number of outgoing transfers.

| Setting | Default | Meaning |
|---|---|---|
| `chunk_size` | 2 MB | Decided by the sender (allowed 64 KB to 16 MB); the receiver follows the offer |
| `slots` | 4 | Chunks in flight at once (the window) |
| `workers` | 2 | Disk-read / hash-verify-write workers per transfer |
| `max_sessions` | 8 | Concurrent incoming connections |
| `io_timeout_ms` | 15 000 | No-progress timeout on a socket |
| `offer_timeout_ms` | 60 000 | How long a person may take to accept |
| `max_retries` | 60 | Consecutive reconnect attempts without progress (back-off 0.4 s up to 4 s) |

**Integrity.** Every chunk travels with its own SHA-256. The receiver verifies it before writing and
acknowledges it in order. When everything is in, the receiver returns a root hash (SHA-256 over all
chunk hashes) and the sender checks it.

**Resume.** The receiver keeps `.share64/<id>.part` and `.share64/<id>.meta` (number of committed
chunks and the per-chunk hashes). The transfer id is deterministic: a SHA-256 of the file name, size,
modification time and the target device. Sending the same file to the same device again, even after
an app restart or an IP change, continues at the first unverified chunk.

**Errors.** `Io`, `Timeout`, `HashMismatch` and `Busy` are retried automatically. Everything else is
terminal and reported to the UI.

**SHA-256.** A portable implementation, plus an ARMv8 SHA-2 version on arm64. The fast path is chosen
at runtime (`HWCAP_SHA2`) and is only used after it passes a self-test.

**Native library.** Built with `-O3`, hidden symbols and section GC, and linked with 16 KB page
alignment (needed for Android 15+ devices and Play Store).

## Wire protocol v1

Raw TCP, little-endian. Every frame starts with a 16-byte header:

```
magic u32 | version u8 | type u8 | flags u16 | length u32 | aux u32
```

`magic` is `0x34365348` (the bytes `SH64`), `version` is `1`.

| Type | Direction | Payload |
|---|---|---|
| `Hello` | sender to receiver | caps u32, device name |
| `HelloAck` | receiver to sender | caps u32, device name |
| `Offer` | sender to receiver | id u64, size u64, chunk u32, mtime_ms i64, file name |
| `Accept` | receiver to sender | id u64, `resume_chunk` u64 |
| `Reject` / `Error` | either | code u32, message |
| `Chunk` | sender to receiver | `aux` = chunk index; data followed by SHA-256(data) |
| `Ack` | receiver to sender | `aux` = chunk index (in order; verified and on disk) |
| `Done` | receiver to sender | 32-byte root hash |

Strings are a `u16` length plus UTF-8 bytes. Control frames are at most 2048 bytes. A transfer may have
at most 2^22 chunks.

```
Hello -> HelloAck -> Offer -> (person accepts) -> Accept(resume_chunk)
      -> Chunk* / Ack* (several chunks in flight) -> Done(root hash)
```

The protocol has **no encryption and no authentication**. See "Security" in the README.

## Where received files go

1. While arriving: `<app storage>/Android/data/com.share64.app/files/Received/.share64/<id>.part`
2. When complete and verified: moved to the folder chosen in Settings, or `Downloads/SHARE64`
   (Android 10+, via MediaStore). The private copy is then deleted. Name clashes become
   `name (1).ext`.
3. Android 9 or older (some TVs), or if exporting fails: the file stays in
   `Android/data/com.share64.app/files/Received`.

Sending never copies anything: the original file is read in place.

## In-app updater

1. When the app opens (at most every 6 hours, can be switched off in Settings) it asks GitHub for the
   latest release of this repository. Prereleases and debug builds are ignored.
2. If the tag (for example `v1.1.0`) is newer than the installed version, an "Update available" sheet
   shows old and new version, size, ABI, date, and the notes from `Relece_notice.md`.
3. "Update now" downloads the APK that matches the device (phone or TV app, its ABI, otherwise the
   universal APK), checks size and SHA-256 against the release's `SHA256SUMS.txt`, then hands it to
   Android's installer. Android itself refuses an APK that is not signed with the same key as the
   installed app.
4. "Skip this version" is remembered.

Requirements: the repository must be public (the app reads the API and raw files without a token),
every release must be signed with the same keystore, and `AppInfo.REPO_NAME` must match the
repository name.

Release notes come from `Relece_notice.md` in the repository root (`Release_notice.md` also works).
The app reads the file at the release's tag, then falls back to `main`. HTML comments are hidden.
Supported Markdown: `#` headings, `-` bullets, `1.` lists, `**bold**`, `*italic*`, `` `code` ``,
`[links](url)`, `>` quotes and `---` rules.

## CI

| Workflow | Trigger | What it does |
|---|---|---|
| `release.yml` | manual | Validates inputs, runs the engine and unit tests, then builds one job per app and ABI (`armeabi-v7a`, `arm64-v8a`, `x86_64`) plus one universal APK per app, in parallel. Verifies every APK signature and can publish a GitHub Release with `SHA256SUMS.txt` |
| `engine-tests.yml` | manual | Builds and runs the C++ suite plain and under AddressSanitizer + UBSan, then compiles both apps (arm64 only, fast) and runs the JVM unit tests |

Release APKs are named `SHARE64-<phone|tv>-<version>-<abi|universal>-<release|debug>.apk`.

Inputs of the Release workflow: `version_name` (for example `1.0.0`), `version_code` (integer),
`target_device` (`all`, `phone` or `tv`), `build_type` (`release` or `debug`) and `publish_release`.

Optional signing secrets: `SHARE64_KEYSTORE_BASE64`, `SHARE64_KEYSTORE_PASSWORD`,
`SHARE64_KEY_ALIAS`, `SHARE64_KEY_PASSWORD`. Without them, release APKs are signed with the debug key,
which is fine for testing but not for distribution.

## Building and testing locally

Requirements: JDK 17, Android SDK 36, NDK `27.3.13750724`, CMake `3.22.1` (Android Studio's SDK
Manager installs the last two).

```bash
# Build one ABI only (much faster than all three)
./gradlew :app-mobile:assembleDebug -Pshare64.abis=arm64-v8a -Pshare64.universal=false

# JVM unit tests
./gradlew :core-network:testDebugUnitTest :core-app:testDebugUnitTest

# C++ engine tests (any Linux machine with CMake and a C++17 compiler)
cmake -S core-cpp/src/main/cpp -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
./build/share64_host_test
```

The host test covers sizes around chunk boundaries and hostile file names, killed connections and bit
flips on the wire (the transfer must resume seamlessly), a sender restart mid-transfer, manual
accept / decline / cancel, and throughput with small chunks and many slots.

Gradle properties for CI: `-Pshare64.abis=arm64-v8a,x86_64`, `-Pshare64.universal=false`,
`-Pshare64.versionName=1.2.0`, `-Pshare64.versionCode=120`.
