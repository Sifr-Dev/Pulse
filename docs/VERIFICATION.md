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

## Instrumented tests (API 36 x86_64 emulator, local KVM)

```sh
./gradlew :app:connectedDebugAndroidTest
```

Result: `BUILD SUCCESSFUL`, 16 tests, 0 failures, 0 errors.

The suite runs on a booted API 36 emulator and covers Room persistence,
atomic retention and clear, the real probe against local TCP/TLS servers, the
platform resolver, and Compose navigation/state rendering. Three failures were
found on the first run and fixed:

1. Room assigns a surrogate id; the persistence round-trip assertion now
   compares the measured payload with the persisted id.
2. Android's `DnsResolver` is a network resolver and does not serve
   `/etc/hosts`, so `localhost` is NXDOMAIN on the emulator. The resolver test
   now asserts numeric loopback handling and typed failure for a guaranteed
   non-existent `*.invalid` name.
3. `createComposeRule` allows one `setContent` per test; the success and
   failure rendering assertions were split into two tests.

## Release build (0.1.0)

```sh
./gradlew :app:assembleRelease :app:bundleRelease
```

Result: `BUILD SUCCESSFUL`. R8 minification with resource shrinking; signed
APK (1,479,542 bytes) and AAB produced. `apksigner verify` passes strictly;
signer certificate SHA-256
`81228a3437dce094721b78842a2f373ed718219c27c5b8a04f9b05b5d9d3c8bf`.
The keystore is a generated project-local key (PKCS12, RSA-4096); the build
reads it through a gitignored `keystore.properties` and produces an unsigned
artifact when it is absent, so CI never needs the secret.

### On-device release validation

The signed release APK was installed on the API 36 emulator
(`adb install -r`, `Success`), launched (`pidof` alive, no FATAL in logcat),
and driven through one real measurement against a local HTTP server bound on
the host and reached via the emulator alias `10.0.2.2:8901`. The UI rendered
`HTTP 200`, total 1028.6 ms, with per-stage timings `dns 0.1 ms`,
`tcp 527.6 ms`, `http_headers 499.6 ms`, `http_body 0.4 ms` and the resolved
address. This is a real DNS/TCP/HTTP measurement path executed by the release
binary, not sample data.

## Diagnostics-reliability change (2026-11-26)

Branch `product/diagnostics-reliability`. Two real defects were found by reading the
implementation against its own documented contract, then fixed with loopback fixtures.

### Defect 1 - interim 1xx recorded as the final status

`readResponseHead` treated the first head as final. A server sending
`103 Early Hints` (or `100 Continue`) before its `200 OK` therefore produced a
completed **successful** measurement with `responseCode = 103` and
`bodyBytes = 0`, because `hasBody` is false for 1xx. The recorded status was not
the status the server finally answered with. `docs/ARCHITECTURE.md` claimed 1xx
was "rejected as unsupported protocol input"; the code accepted it.

Fix: interim 1xx heads are consumed until a final status arrives, the count is
persisted as `interimResponses`, and the header stage timing spans the whole
interim exchange. `101 Switching Protocols`, more than
`ProbeConfig.maxInterimResponses` (8) interim heads, and interim-only-then-EOF
are typed failures instead of successes.

### Defect 2 - redirect security boundary was invisible

Redirects are intentionally not followed, but nothing recorded what the server
pointed at. A `301` to `http://...` from an `https://` origin - a TLS
downgrade - was stored as an ordinary success with no indication of the
crossing.

Fix: `RedirectClassifier` reduces the first `Location` header to
`scheme://authority` and classifies the crossing as `same_origin`,
`cross_origin`, `upgrade`, `downgrade`, or `unresolved`. Path, query, fragment,
and userinfo are dropped, so redirect tokens and credentials are never
persisted. Classification happens at header time, so a 3xx whose body read
fails still reports where it pointed.

### Tests added

`InterimResponseTest` (JVM, real loopback `ServerSocket`): 103 then 200, 100
then 204, three interims counted in order, zero interims reported as 0 rather
than null, 101 rejected as `HTTP_PROTOCOL`, 12 interims rejected, interim-only
EOF as `HTTP_FAILURE`, and header timing covering a 150 ms interim wait
(injecting `System.nanoTime()` because the JVM unit-test stub for
`android.os.SystemClock` returns 0 for every call).

`RedirectBoundaryTest` (JVM, real loopback `ServerSocket`): same-origin
relative `Location`, cross-origin with a token in the query (asserting the
token never reaches `redirectTarget`), https-to-http downgrade, https-to-https
cross-origin, userinfo rejection, relative resolution, non-default ports,
non-HTTP(S)/unparsable/CRLF-injecting locations, non-redirect statuses, a 3xx
without `Location`, a redirect recorded through a body failure, and repeated
`Location` headers not rewriting the boundary.

`DatabaseMigrationTest` (instrumented): opens a genuine schema-1 file, runs the
shipped `MIGRATION_1_2`, and lets Room's own `runMigrationsAndValidate` compare
the result against the exported schema 2; asserts pre-existing rows keep null
for the new columns rather than a backfilled value.

`AndroidRealProbeTest` gained device-runtime coverage of the 103-then-200 path
and of a cross-origin redirect recorded without being followed.

`MeasurementExporterTest` gained a schema-2 assertion that a redirect exports
its sanitized origin in both JSONL and CSV.

## Not yet verified (explicit)
- Release signing: no release key was provided. Release artifacts require the
  owner's keystore; no signing identity is fabricated.
- Macrobenchmark and Baseline Profile modules are not part of this milestone.
