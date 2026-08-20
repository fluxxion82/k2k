# k2k

Kotlin Multiplatform LAN transport for device-to-device sync, built on Ktor: file upload/download
and a bounded pairing exchange, with mutually authenticated TLS and SPKI-pinned device certificates
on JVM targets. Started as a Ktor-based rewrite of
[DATL4G/Klient2Klient](https://github.com/DATL4G/Klient2Klient).

k2k is the device-to-device sync transport of the
[Passman](https://github.com/fluxxion82/passwordManager) password manager.

## Modules

| Module | Type | Purpose |
| --- | --- | --- |
| `k2k` | KMP library (JVM + iosArm64 + iosSimulatorArm64) | The whole library. See Layout below. |

## Layout

Two transports live here, and they do not share a wire protocol.

| Package | Targets | What it is |
| --- | --- | --- |
| `com.k2k.test.{server,client,tls}` | JVM only | The current transport: multipart upload, download, sync-pull, and a bounded pairing-bundle exchange, over mutual TLS with SPKI pinning and per-operation authorization. This is what Passman uses. |
| `com.k2k.{client,server}` | common | An older plaintext HTTP upload/download path, plus `PlatformServer`. Used by moviePicker. No TLS, no caps, no authorizer. |
| `com.k2k.NetInterface` | common | Local/broadcast address lookup. The one symbol both consumers share. |
| `com.k2k.NetworkScanner`, `PlatformSocket` | Android + native | Subnet sweep for peer finding. The JVM actual is `TODO()` — do not call it from JVM. |

The `com.k2k.test.*` naming is historical and misleading: that is production code in `src/jvmMain`,
not a test source set. Renaming it to `com.k2k.transport.*` is worthwhile but has to be coordinated
with Passman, whose production code imports those packages.

## Consumers

Both apps include k2k as a Gradle subproject by path rather than as a published artifact, so `libs`
inside `k2k/build.gradle.kts` resolves against **the consumer's** version catalog, not this repo's.
This repo's catalog is only used for the standalone build.

- **Passman** — vendors k2k as a git submodule and uses the mTLS transport.
- **moviePicker** — uses the plaintext transport and `NetworkScanner`.

## Commands

JDK 17 required. Run from the repo root.

```bash
./gradlew build          # build everything
./gradlew :k2k:jvmTest   # library tests
```

## Example apps

Temporarily absent, and coming back. `presenter`, `desk`, `droid`, and `ios` demonstrated
`Discovery`/`Connection`, which were removed (see Scope below), so they no longer compiled against
anything. They are out of `settings.gradle.kts` but still on disk.

They are a first-class deliverable, not a test harness: someone who finds this repo should be able
to read the example, see how pairing and transfer actually work, and lift it into their own project.
That is what the examples are for, and no amount of coverage inside a consuming app substitutes for
it.

Planned order, so the library leads and consumers follow rather than each solving it separately:

1. Close the iOS TLS gap in the library.
2. Rewrite the examples against the current transport — pairing exchange, then upload/download over
   mutual TLS — on all three platforms.
3. Passman and moviePicker adopt what the example demonstrates.

## Scope

`Discovery` and `Connection` (UDP broadcast discovery and a raw TCP channel) were removed. Neither
consumer used them: Passman pairs out-of-band via QR with a safety number, and moviePicker shows its
IP for manual entry plus a subnet sweep. For a password manager, broadcast discovery is also the
wrong posture — it announces device presence and invites the man-in-the-middle that safety numbers
exist to prevent.

Their verified root causes are recorded in `CLAUDE.md` under Known issues, so if broadcast discovery
is ever wanted again, resurrect from git history rather than re-deriving the bugs.

## Known issues

- **mTLS is JVM-only.** `ktor-network-tls` is declared in `commonMain` and imported nowhere. iOS and
  native peers have no encrypted transport. This is the largest open gap.
- **`NetInterface.getAddresses()` returns empty on iOS and leaks per call.** `nativeMain/NetInterface.kt`
  reads the `ifaddrs` pointer before `getifaddrs()` populates it, so the walk never runs, and
  `freeifaddrs` then frees null while the real list leaks. `getLocalAddress()` in the same file is
  correct — copy its shape.
- **No Android target.** `androidTarget()` is absent pending an AGP bump, so `src/androidMain` is
  carried but not compiled and has drifted. This blocks moviePicker, which calls
  `NetworkScanner(androidContext())` — only the Android actual takes a `Context`.

## License

[Apache License 2.0](LICENSE).
