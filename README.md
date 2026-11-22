# Pulse

Pulse is an Android app for deliberate, on-demand HTTP(S) network measurements. It uses real DNS resolution, TCP sockets, verified TLS, and bounded HTTP response reads—never static sample results or background polling.

## Use

1. Enter a complete `http://` or `https://` URL.
2. Tap **Run measurement**.
3. Inspect the resolved address and DNS, TCP, optional TLS, HTTP-header, and response-body timings.
4. Cancel an active attempt without storing it.
5. Open **History** to see locally persisted attempts or export versioned JSONL/CSV to a user-selected document.

Only one resolved address is attempted. Redirects are not followed. HTTP 4xx/5xx responses are completed HTTP measurements; DNS, connection, certificate, protocol, timeout, and body-limit failures are explicitly typed. The body is discarded after a maximum of 64 KiB and never persisted.

## Build and test

Use JDK 17 (newer JDKs may work but are not the declared compatibility baseline). Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` to an SDK containing platform 36, Build Tools 36 and platform-tools, or use the normal unversioned `local.properties`.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --max-workers=2
# With a booted emulator or attached API 26+ device:
./gradlew :app:connectedDebugAndroidTest --max-workers=2
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. No release signing credentials are included. See [architecture](docs/ARCHITECTURE.md), [verification evidence](docs/VERIFICATION.md), and the [engineering log](docs/ENGINEERING.md).

## Privacy and product boundaries

Measurements happen only after a user action. Room retains up to 2,000 local attempts; DataStore remembers the last target. No analytics, body payloads, cookies, credentials, or response headers are stored. There is no WorkManager job, background service, continuous monitor, or benchmark module in this change. Android backups exclude both the database and preferences.

TLS uses platform trust, SNI, and hostname verification. The app supports HTTP/1.0/1.1 without ALPN. It is not a browser reachability test, ICMP ping, multi-address Happy Eyeballs implementation, or physical Wi-Fi/cellular/VPN qualification.
