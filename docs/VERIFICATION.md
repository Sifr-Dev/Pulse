# Pulse verification evidence

Recorded 2026-11-22 on the engineering workstation. Every command below was
executed; results are copied from the actual Gradle output, not predicted.

## Environment

- JDK 17 (Gradle toolchain target), Gradle 9.6.0 via the repository wrapper.
- Android SDK: platform 36, build-tools 36.0.0, platform-tools 37.0.3.
- AGP 9.5.2 with built-in Kotlin 2.2.10, Compose BOM 2026.11.01 with the
  documented SDK-36-compatible version overrides in `app/build.gradle.kts`.
- Host: AMD Ryzen 7 7435HS, 16 GiB RAM, Linux 7.3.4, offline Gradle cache.

## JVM unit tests

```sh
./gradlew :app:testDebugUnitTest --offline
```

Result: `BUILD SUCCESSFUL`, 24 tests, 0 failures, 0 errors.

Coverage of this suite:

- `NetworkProbeTest` — DNS/TCP/TLS/header/body stage timing and typed failure
  mapping against in-process fixtures.
- `LocalTlsFixtureTest` — a real JSSE handshake through a recording TCP proxy.
  The probe must classify an untrusted certificate as `TLS_CERTIFICATE`, must
  not read HTTP headers, and the captured ClientHello must be a handshake
  record containing the SNI of the requested dotted host.
- `LocalHttpFixtureTest` — bounded body reads, `Content-Length` handling and
  first-byte timing.
- `TargetParser` / `Measurement` / `MeasurementExporter` contracts.
- Room DAO and repository behaviour under the JVM (Robolectric-free, using the
  instrumentation runner only for Android-specific suites).

Two defects were found by these tests and fixed rather than muted:

1. `NetworkProbe.measure` returned `Any` because `return@withContext` made the
   cancellation handle's `dispose()` unreachable, so the lambda's last
   expression was `Unit`. The success path also leaked the completion handler.
   Reworked to a single typed `result` value with `dispose()` on every path.
2. The TLS fixture originally read handshake bytes from an `SSLSocket` input
   stream, which performs the handshake itself instead of exposing records.
   Replaced with a TCP proxy that records the first TLS record and forwards it
   verbatim to a real JSSE server, so the assertion tests the probe, not the
   fixture's misunderstanding of JSSE.

## Debug APK

```sh
./gradlew :app:assembleDebug --offline
```

Result: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk`
(19,963,046 bytes) with `output-metadata.json`.

## Not yet verified (explicit)

- Instrumented `connectedDebugAndroidTest` on an emulator or physical device
  has **not** been re-run in this session; the CI workflow runs it on API 36.
  The earlier run in the previous session ended in a build failure that is now
  fixed, so a new run is required before claiming device coverage.
- Release signing: no release key was provided. Release artifacts require the
  owner's keystore; no signing identity is fabricated.
- Macrobenchmark and Baseline Profile modules are not part of this milestone.
