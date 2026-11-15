# Patch: "Bulletproof stability" (network, resume, service, on/off switch)

Merge by copying these files over the project (paths relative to the repo root).

**Apply after the Material 3 Expressive phone patch.** `HomeScreen.kt`, `Components.kt` and
`SettingsScreen.kt` here are the Expressive versions plus the new features.

## What was already in the engine (kept, not rewritten)
- The receiver listens on every interface (dual-stack), so a new IP never needs a re-bind.
- TCP keep-alive, `TCP_USER_TIMEOUT`, 15 s I/O timeouts; sender retry with back-off (up to 60
  attempts without progress); receiver keeps `.part`/`.meta`; transfer ids are deterministic, so a
  restart resumes from the last verified chunk.

## What this patch adds

| Request | Change |
|---|---|
| 1. Smart network detection | `NetworkProfile.kt` (new): classifies Wi-Fi / Ethernet / **phone hotspot (client)** / **your hotspot (host)** / USB tethering from `ConnectivityManager` transport + gateway IP (`192.168.43.1`, `172.20.10.1`, ...) + metered flag + interface names. `NetworkWatcher` feeds it (callbacks, hotspot/tether broadcasts, 3 s poll). `DiscoveryStatus.Running` now carries the kind, shown on Home and in the notification. On hotspot-like networks, when DNS-SD finds nobody, `SubnetScanner` (new) handshakes with the subnet's /24 on the default port (`scan:` peers, replaced as soon as DNS-SD identifies them). |
| 2. Heartbeat | Keep-alive probes now start after 3 s idle, every 1 s, 3 misses = dead peer in about 6 s (was 11 s), and `TCP_USER_TIMEOUT` is capped at 10 s. Configurable (`Config::keepalive_*`). No new wire frames, so older versions still interoperate. Plus an instant path: when Android reports the link gone, `Engine::network_changed` closes dead connections at once. |
| 3. Auto-retry / resume | `Engine::network_changed(up, drop)`: while offline a sender **waits without using up its retries** (up to 30 min, `offline_patience_ms`) and reconnects the moment the network is back. `Engine::update_peer_address`: a peer that re-appears on a new IP is picked up by running sends immediately (back-off skipped). Receiver sessions dropped by a network loss keep their partial data. |
| 4. Service hardening | `ShareService`: safe `startForeground` (no crash when Android refuses), `START_STICKY` when background receiving is on, shutdown/start race fixed, wake lock renewed every 10 min (a 10-hour transfer no longer loses it after 4 h), a receive that stays interrupted releases the CPU after 5 min, Wi-Fi high-performance lock added next to low-latency, coroutine exception handlers. New `BatteryOptimization.kt` + a "Keep running with the screen off" row in Settings (permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). |
| 5. Error states | `TransferEngine` calls are no-ops once closed and can no longer reach freed native memory; plain-language statuses ("Waiting for the network…", "Network lost — waiting for the sender", "No network for too long — send it again…"); friendlier DNS-SD errors; Home explains what to do without a network. |
| 6. On/off switch | Material 3 switch in the Home hero card (`AppSettings.sharingEnabled`). Off: sockets closed, wake/Wi-Fi locks released, service and notification gone, FAB hidden; `ShareService.start()` is a no-op while off (so app opens and the TV app respect it). Turning it off with transfers running asks first. On: everything starts again. |

## Files
C++: `types.h`, `engine.h/.cpp`, `net.h/.cpp`, `sender.h/.cpp`, `receiver.h/.cpp`, `jni_bridge.cpp`, `host_test.cpp` (6 new tests).
Kotlin: `TransferEngine.kt`, core-network (`NetworkProfile`, `SubnetScanner`, `NetworkWatcher`, `DiscoveryManager`,
`Share64Node`, `PeerRegistry`, `LocalAddresses` + 4 new test files), core-app (`ShareService`, `AppSettings`,
`BatteryOptimization`, `TransferPresenter` + tests, manifest), app-mobile (`MainViewModel`, `HomeScreen`,
`Components`, `SettingsScreen`).

## Verified here
- Engine: full suite plus the new network tests pass in a normal build and under AddressSanitizer,
  UndefinedBehaviorSanitizer and ThreadSanitizer (no reports); three repeat runs.
- Pure Kotlin logic and the presenter run on a JVM with `kotlinc`: 45 tests pass (35 in core-network
  including the old ones, 10 in the presenter).
- All Android-dependent Kotlin in core-cpp / core-network / core-app type-checks against the Android 13 SDK stubs.
- JNI bridge syntax-checked against a stub `jni.h`.

## Not verified
- The four Compose files (`HomeScreen`, `Components`, `SettingsScreen`, `MainViewModel`) were checked for syntax
  and imports only, not compiled.
- Nothing ran on a device. Real hotspot behaviour (interface names, broadcasts, DNS-SD) depends on the ROM;
  the classifier falls back to Wi-Fi/"Local network" when unsure and the manual address always works.

## Behaviour to know
- When an address vanishes (hopped access point, hotspot switched off) all live connections are closed,
  including healthy ones on another interface; they reconnect and resume within about half a second.
- The subnet sweep only runs on hotspot-like networks, only while nobody was found, every 10 s, default port only.
- The TV app gets the engine, network and service improvements automatically; it has no on/off switch.
