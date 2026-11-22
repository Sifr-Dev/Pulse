# Pulse architecture

Pulse is a one-shot, user-triggered Android network observability application. It has no service, periodic job, push handler, startup network call, telemetry SDK, or ambient traffic.

## Toolchain

- AGP 9.5.2 with its embedded Kotlin 2.2.10; no `org.jetbrains.kotlin.android` plugin.
- Gradle wrapper 9.6.0 and JDK-compatible Java 17 bytecode settings.
- compile/target SDK 36, min SDK 26; Build Tools 36.
- Compose BOM 2026.11.01 is retained, with explicit resolution to Compose 1.10.0 / Material3 1.4.0 / Material 1.7.8 and Lifecycle 2.10.0 for SDK 36. The BOM's default Compose 1.13 / Material3 1.5 / Lifecycle 2.11 graph requires compile SDK 37–37.1; upgrading SDK or suppressing metadata checks was rejected. Room 2.8.5 and DataStore Preferences 1.3.0 are unchanged.
- KSP 2.2.10-2.0.2 for Room. AGP's explicitly experimental `android.disallowKotlinSourceSets=false` is currently needed because this KSP release registers generated source directories using `kotlin.sourceSets`. This is a known compatibility switch, not an assertion of native KSP/AGP integration.

## Measurement semantics

1. `TargetParser` accepts absolute HTTP(S) URLs only, limits length to 2,048 characters, validates the authority and port, normalizes DNS names through IDN, supports bracketed IPv6, and rejects userinfo, fragments, whitespace, and malformed IP/DNS input. No URL credentials are accepted or persisted.
2. `SystemResolver` uses Android `DnsResolver` on API 29+ (A and AAAA through the system cache/resolver) and `InetAddress.getAllByName` as the labelled API 26–28 fallback. Numeric addresses skip DNS.
3. `NetworkProbe` chooses the first returned address, measures real `Socket.connect`, and wraps that measured connection in a platform `SSLSocket` for HTTPS. Supplying the host to the socket factory enables SNI. The platform trust manager validates the chain, then `HttpsURLConnection.getDefaultHostnameVerifier()` verifies the endpoint identity. There is no trust-all manager, disabled certificate checking, or custom permissive hostname policy.
4. One HTTP/1.0 or HTTP/1.1 GET requests the exact path/query with identity encoding and `Connection: close`. ALPN is not offered. Redirects are recorded as completed HTTP responses, not followed. HTTP status 4xx/5xx is a successful network measurement; transport/TLS/protocol failures are failures.
5. Header and body reads have bounded time and byte limits. Headers are capped at 32 KiB; bodies are capped at 64 KiB and discarded while counting. Content-Length, EOF framing and chunked framing are supported. Invalid/ambiguous framing is rejected. Interim 1xx responses are currently rejected as unsupported protocol input; the first response byte timestamp still precedes that error. A 45-second overall coroutine deadline bounds the whole measurement.
6. Every completed stage uses `SystemClock.elapsedRealtimeNanos()`. Stage timings do not overlap and exclude subsequent stages. Header timing ends after the header terminator; body timing starts afterward. First-response-byte is measured from probe start. Total includes DNS, TCP, optional TLS, request writing, headers, and bounded body consumption.
7. Cancellation closes the active socket and propagates `CancellationException`. Cancelled attempts are not persisted. On API 26–28 legacy resolver cancellation is cooperative only; on API 29+ `CancellationSignal` is used. Cancellation during blocking I/O closes the socket; application exit should not be mistaken for a completed measurement.

The first resolved address is intentional, explicit scope—not Happy Eyeballs, browser connection pooling, network diagnosis, ICMP, or reachability across every address family. Wi-Fi/cellular/VPN/IPv6 behavior still needs physical-device qualification.

## Persistence and export

Room stores completed successes and failures in a single measurement table with nullable stage and failure metadata, up to 2,000 newest entries. In-memory Room tests exercise real SQLite on Android. DataStore stores only the last normalized target. Response bodies, cookies, authorization, request headers, and TLS private/session secrets are never stored.

History navigation and exports are user initiated. The Storage Access Framework is the only export destination. JSONL has a version-1 envelope and a schema version on each measurement; CSV has a schema-version column. Exporters stream UTF-8 and correctly escape JSON/CSV. See [VERIFICATION.md](VERIFICATION.md) for actual rather than intended validation.

## Out of scope in this change

No WorkManager/background schedule, benchmark module, baseline profile, production signing, public performance claims, API 26 image qualification, or benchmark evidence is included. These were not silently substituted with static content.
