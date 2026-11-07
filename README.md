# SHARE64

High-performance local Wi-Fi file sharing for Android (phone + TV). Package: `com.share64.app`.

## Part 1: project skeleton + `:core-cpp`

```
core-cpp/src/main/cpp/     C++17 engine (CMake/NDK) + JNI bridge
core-cpp/src/main/java/    TransferEngine.kt (Flows) + TransferEvent.kt
core-cpp/src/hosttest/     loopback + fault-injection test suite (runs on any Linux box)
.github/workflows/engine-tests.yml   manual: builds and runs that suite
```

## Part 2: `:core-network`

```
core-network/src/main/java/com/share64/network/
  Share64Node.kt        engine + discovery in one object (what the app modules use)
  DiscoveryManager.kt   NsdManager advertise/discover, serialized resolver, TCP liveness, auto-restart
  NetworkWatcher.kt     LAN IPv4 changes (Wi-Fi, Ethernet, hotspot)
  PeerTxt / PeerRegistry / AddressParser / HandshakeProbe / LocalAddresses   pure Kotlin, unit-tested
  LocalNetworkAccess.kt Android 17 ACCESS_LOCAL_NETWORK helper (only matters once targetSdk >= 37)
```

## Part 3: `:core-app` + `:app-mobile`

```
core-app/     shared by phone and TV: ShareService (foreground service owning the node), notifications
              with Accept/Decline, Wi-Fi/wake locks while transferring, export to Downloads/SHARE64,
              Uri -> transfer helper, formatting + transfer presenter (unit-tested)
app-mobile/   Jetpack Compose, Material 3 + dynamic color: device grid, transfer cards, bottom sheets,
              incoming-file dialog, settings, "Share to SHARE64" from any app (applicationId com.share64.app)
```

Build one ABI only: `./gradlew :core-cpp:assembleRelease -Pshare64.abis=arm64-v8a`
Run the engine tests locally:
`cmake -S core-cpp/src/main/cpp -B build && cmake --build build && ./build/share64_host_test`

## Wire protocol v1 (raw TCP, little-endian)

16-byte header `magic u32 | version u8 | type u8 | flags u16 | length u32 | aux u32`.
`Hello/HelloAck -> Offer -> Accept(resume_chunk) -> Chunk* / Ack* -> Done(root hash)`.
`Chunk` = data || SHA-256(data). Receiver keeps `.share64/<id>.part` + `.meta` (committed count and
per-chunk hashes); a reconnect with the same transfer id resumes at the first unverified chunk.
