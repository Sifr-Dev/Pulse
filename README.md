# Resonance

**Deliberate, on-demand HTTP(S) measurements on Android, with real sockets and typed failures.**

[![Kotlin](https://img.shields.io/badge/KOTLIN-7f52ff?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=1b1f27)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/ANDROID-API%2026%2B-3ddc84?style=for-the-badge&logo=android&logoColor=0b0d10&labelColor=1b1f27)](https://developer.android.com/)
[![CI](https://github.com/Sifr-Dev/Pulse/actions/workflows/verification.yml/badge.svg)](https://github.com/Sifr-Dev/Pulse/actions/workflows/verification.yml)
[![License: MIT](https://img.shields.io/badge/LICENSE-MIT-2ea043?style=for-the-badge&labelColor=1b1f27)](LICENSE)

> The GitHub repository remains `Sifr-Dev/Pulse`; Resonance is the product name.
> The Android application ID and Kotlin namespace remain
> `io.pulse.observability` so installs and data survive an update.

## What it is

Resonance is an Android app for measuring one HTTP or HTTPS request at a time.
It resolves the host through the platform resolver, opens a real TCP socket,
performs verified TLS when the scheme requires it, reads a bounded response, and
shows DNS, TCP, TLS, header and body timings separately. Measurements happen
only after a user action; there is no polling, analytics or background monitor.

## Current capabilities

- **Real DNS, TCP, TLS and HTTP measurement** with SNI, hostname verification
  and platform trust. Proof: `app/src/main/java/io/pulse/observability/probe/
  AndroidNetworkProbe.kt`, `.../probe/NetworkProbe.kt`,
  `app/src/test/.../probe/LocalTlsFixtureTest.kt`.
- **Typed failure boundaries** for DNS, connect, certificate, protocol,
  timeout and body-limit failures; HTTP 4xx/5xx is a completed measurement.
  Proof: `NetworkProbe.kt`;
  `app/src/test/.../probe/LocalHttpFixtureTest.kt`.
- **Bounded body reads** with a 64 KiB cap and no payload persistence.
  Proof: `NetworkProbe.readBody`;
  `LocalHttpFixtureTest` (`declared oversized body fails before reading body`,
  `chunked body larger than limit fails`).
- **Interim 1xx handling**: `100 Continue` and `103 Early Hints` are consumed
  until the final status, counted, and included in header timing; `101` and
  unbounded interim responses are typed protocol failures.
  Proof: `app/src/test/.../probe/InterimResponseTest.kt` (8 cases).
- **Redirect classification without following**: the first `Location` is
  reduced to `scheme://authority` and classified as same-origin, cross-origin,
  upgrade, downgrade or unresolved, so credentials and paths are not stored.
  Proof: `.../probe/RedirectClassifier.kt`,
  `app/src/test/.../probe/RedirectBoundaryTest.kt`.
- **Cancellation of an active attempt** without storing it.
  Proof: `NetworkProbe.closeActiveSocket`;
  `LocalHttpFixtureTest` (`cancelled blocked body is not converted to failure`).
- **Local history and export**: Room retains up to 2,000 attempts; versioned
  JSONL and CSV export goes through the Android document picker.
  Proof: `.../data/MeasurementDao.kt`, `.../data/HistoryRepository.kt`,
  `.../export/MeasurementExporter.kt`,
  `app/src/test/.../export/MeasurementExporterTest.kt`.
- **No backup of measurements or settings**; backups exclude both stores.
  Proof: `app/src/main/res/xml/backup_rules.xml`,
  `app/src/main/res/xml/data_extraction_rules.xml`.

## Quick start

Requirements: JDK 17 as the declared compatibility baseline, Android SDK
platform 36 and Build Tools 36. Set `ANDROID_HOME` / `ANDROID_SDK_ROOT` or use
`local.properties`. The repository wrapper supplies Gradle.

```sh
git clone https://github.com/Sifr-Dev/Pulse.git
cd Pulse
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --max-workers=2
# With a booted API 26+ emulator or device:
./gradlew :app:connectedDebugAndroidTest --max-workers=2
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. No
release signing credentials are committed; without `keystore.properties` the
release build produces an unsigned artifact.

## Verification

Executed on 2026-11-28 on Linux x86-64 with the repository Gradle wrapper and
Android SDK 36:

```sh
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Observed: `BUILD SUCCESSFUL`; **47 unit tests, 0 failures, 0 errors, 0
skipped**; `lintDebug` passed; `app-debug.apk` assembled.

Instrumented tests were **not** run in this environment (no booted emulator was
used). The repository records a previous API 36 emulator run of 16 passing
instrumented tests in [docs/VERIFICATION.md](docs/VERIFICATION.md), and CI runs
`connectedDebugAndroidTest` in a separate job.

## Architecture

```text
app/src/main/java/io/pulse/observability
  probe/NetworkProbe.kt          staged DNS/TCP/TLS/HTTP measurement engine
  probe/AndroidNetworkProbe.kt   platform resolver, sockets and trust wiring
  probe/RedirectClassifier.kt    redirect boundary reduction
  probe/Measurement.kt           result model and typed outcomes
  data/                          Room database, DAO, history and settings
  export/MeasurementExporter.kt  versioned JSONL and CSV export
  ui/                            Compose screen and view model
app/src/test                     JVM unit and loopback fixture tests
app/src/androidTest              Room, TLS, probe and Compose device tests
docs/                            architecture, verification and engineering log
```

## Benchmarks

No benchmark is published. The app reports timings for a single user-triggered
measurement; it is not a throughput or network-qualification benchmark.

## Limitations

- One resolved address is attempted; there is no Happy Eyeballs or multi-address
  fallback.
- Redirects are classified but not followed.
- HTTP/1.0 and HTTP/1.1 only; ALPN and HTTP/2 are not implemented.
- The response body is discarded after 64 KiB and never persisted.
- This is not a browser reachability test, ICMP ping, or a measurement of
  physical Wi-Fi, cellular or VPN quality.
- No background monitoring, scheduled probes, analytics or cloud sync.
- Instrumented coverage depends on an emulator/device and was not executed in
  the local verification above.
- Room retains at most 2,000 attempts; older rows are pruned.

## Roadmap (NOT IMPLEMENTED)

- Multi-address connection racing with documented ordering.
- HTTP/2 and ALPN support.
- Optional scheduled measurements with explicit foreground constraints.
- Aggregate trend views over locally stored history.
- Additional export schemas and import.
- Physical-network qualification.

## License

MIT; see [LICENSE](LICENSE).
