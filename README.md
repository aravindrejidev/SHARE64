# SHARE64

High-performance local Wi-Fi file sharing for Android (phone + TV). Package: `com.share64.app`.

## Part 1 (this archive): project skeleton + `:core-cpp`

```
core-cpp/src/main/cpp/     C++17 engine (CMake/NDK) + JNI bridge
core-cpp/src/main/java/    TransferEngine.kt (Flows) + TransferEvent.kt
core-cpp/src/hosttest/     loopback + fault-injection test suite (runs on any Linux box)
.github/workflows/engine-tests.yml   manual: builds and runs that suite
```

Build one ABI only: `./gradlew :core-cpp:assembleRelease -Pshare64.abis=arm64-v8a`
Run the engine tests locally:
`cmake -S core-cpp/src/main/cpp -B build && cmake --build build && ./build/share64_host_test`

## Wire protocol v1 (raw TCP, little-endian)

16-byte header `magic u32 | version u8 | type u8 | flags u16 | length u32 | aux u32`.
`Hello/HelloAck -> Offer -> Accept(resume_chunk) -> Chunk* / Ack* -> Done(root hash)`.
`Chunk` = data || SHA-256(data). Receiver keeps `.share64/<id>.part` + `.meta` (committed count and
per-chunk hashes); a reconnect with the same transfer id resumes at the first unverified chunk.
