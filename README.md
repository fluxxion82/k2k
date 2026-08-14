# k2k

Kotlin Multiplatform peer-to-peer networking for devices on the same LAN: UDP broadcast discovery plus client/server data and file transfer, built on Ktor. Supports mutually authenticated TLS on JVM targets. Started as a Ktor-based rewrite of [DATL4G/Klient2Klient](https://github.com/DATL4G/Klient2Klient).

k2k is the device-to-device sync transport of the [Passman](https://github.com/fluxxion82/passwordManager) password manager.

## Modules

| Module | Type | Purpose |
| --- | --- | --- |
| `k2k` | KMP library (JVM + iosArm64 + iosSimulatorArm64) | Core p2p: UDP broadcast discovery, connections, file upload/download. Public entry points are `Discovery.Builder` and `Connection.Builder` in `commonMain`. Exposed to iOS via CocoaPods as `k2k.framework`. |
| `presenter` | KMP library (JVM + iOS) | Shared presenter layer for the example apps (KMP-NativeCoroutines for iOS interop). |
| `desk` | Compose Desktop app | Example desktop app. |
| `droid` | Android app | Example Android app (acquires a multicast lock for discovery). |
| `ios` | Xcode project | Example iOS app, consuming `k2k` and `presenter` as CocoaPods. |

## How it works

- `Discovery.makeDiscoverable()` broadcasts a serialized `Host` over a UDP port at a fixed interval; `Discovery.startDiscovery()` listens on the same port and accumulates peers, evicting entries that stop pinging.
- Once a peer is discovered, `Connection.Builder` opens a client/server channel to it for data or file transfer. Discovery and connection use different ports by convention (the examples use 1337 and 2323).

## Commands

JDK 17 required. Run from the repo root.

```bash
./gradlew build                 # build everything
./gradlew :desk:run             # desktop example
./gradlew :droid:installDebug   # Android example on a connected device
./gradlew :k2k:jvmTest          # library tests
./build_pods.sh                 # regenerate podspecs before opening ios/ios.xcworkspace
```

## Known issues

- iOS shows up as discoverable to desktop/Android, but its own discovered-hosts flow doesn't populate, so the iOS example's peer list stays empty. Tracked upstream at [KTOR-6489](https://youtrack.jetbrains.com/issue/KTOR-6489).
- "Address already in use" exceptions appear when stopping and restarting discovery on the same port.
- The iOS example can crash if the desktop app starts discovery first.

## License

[Apache License 2.0](LICENSE).
