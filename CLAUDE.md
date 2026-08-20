# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

k2k is an experimental Kotlin Multiplatform peer-to-peer library — a Ktor-based rewrite of [DATL4G/Klient2Klient](https://github.com/DATL4G/Klient2Klient) exploring UDP socket discovery and packet transfer between devices on the same LAN. The repo bundles the library with example apps that exercise it on three platforms.

Despite living under a `passwordManager/` parent directory, this codebase is the p2p library itself; nothing here implements password management.

## Modules

| Module | Type | Purpose |
|---|---|---|
| `k2k` | KMP library (jvm + android + iosArm64 + iosSimulatorArm64) | The whole build. Two independent transports: the JVM/Android mTLS + pairing + file transport in `com.k2k.test.*` (what passman uses), and the plaintext `com.k2k.client`/`com.k2k.server` transport plus `NetworkScanner` (what moviePicker uses). They do not share a wire protocol. |

`presenter`, `desk`, `droid` and `ios` are gone from the build — they demonstrated `Discovery`/`Connection`, which were removed. The directories remain on disk pending a rewrite against the current transport; see README.

### Source sets that matter

`jvmSources` is shared by the jvm and android targets and holds **all** of `com.k2k.test.*` — tls,
client and server. That placement is part of k2k's contract with passman, which compiles against
those packages from its own `jvmAndAndroidMain`. Anything left in `jvmMain` is invisible to Android
consumers and will break their build. `NetInterface`/`NetworkScanner`/`PlatformServer` actuals stay
in their per-target sets.

Android `minSdk` is **26** and must not be raised casually: `java.nio.file` (used by the upload
temp-file hardening) arrived at API 26, and moviePicker consumes `:k2k` from a module at 26. A
library minSdk above a consumer's breaks their build outright.

## Common commands

All Gradle commands run from the repo root. JVM target is 17; Kotlin 2.4.20-RC; AGP 9.3.1;
Gradle 9.7.1.

`./gradlew build` needs an Android SDK — `ANDROID_HOME`, or `sdk.dir` in a `local.properties` you
create yourself (it is gitignored). That is for `:k2k`'s own Android target now, not for an example
app. `./gradlew :k2k:jvmTest` and the iOS compile tasks do not need it.

```bash
# Build everything
./gradlew build

# Tests
./gradlew :k2k:jvmTest                                  # the suite
./gradlew :k2k:jvmTest --tests "ClassName.methodName"   # single test

# iOS targets compile without Xcode; there is no podspec step any more
./gradlew :k2k:compileKotlinIosArm64
```

Tests run with `forkEvery = 1`. Each class stands up a real Netty listener, and sharing a JVM let one
class's teardown disturb the next about one run in eight.

## Architecture notes

### Network discovery flow
- `DiscoveryClient` (UDP broadcast sender) and `DiscoveryServer` (UDP listener) in `k2k/src/commonMain/.../discover/` are `@ThreadLocal object` singletons — there is a single shared instance per platform thread/process. The public `Discovery` class wraps them with a Builder API.
- `Discovery.makeDiscoverable()` starts broadcasting a serialized `Host` over the configured UDP port every `ping` ms (default 1000). `Discovery.startDiscovery()` binds the same port and accumulates discovered hosts in `DiscoveryServer.hosts` (a `MutableStateFlow<Map<Host, Long>>`), evicting entries older than `puffer` ms.
- `Host` is the unit of identity: `name` + `filterMatch` are serialized over the wire; `hostAddress` and `port` are `@Transient` and populated from the inbound datagram's `InetSocketAddress`.
- `NetInterface` is an `expect object` resolved per-platform — JVM uses `java.net.NetworkInterface`; iOS/native has its own actual.

### Connection flow
- After discovery surfaces a peer, `Connection.Builder` is constructed with the peer set (commonly from `discovery.peersFlow` or a single host) and a port. `Connection.send()` opens a TCP socket to the peer; `startReceiving()`/`receiveData` flow let one side act as a server.
- Discovery and connection use **different ports** by convention. `MainPresenter` uses 1337 for discovery and 2323 for connection.

### Presenter pattern
`MainPresenter` (in `presenter/`) owns a `SupervisorJob`-backed `CoroutineScope`, drives the `Discovery`/`Connection` lifecycles, and exposes `foundPeers: MutableStateFlow<List<Host>>`. The desktop and Android apps subscribe via `collectAsState()`; KMP-NativeCoroutines was removed in `dbea452`; Swift peer observation is not currently wired up. Constructor flag `receiving: Boolean` switches the example between server (collect data) and client (send "Hello world") roles — `desk` is `true`, `droid` is `false`.

### Dependency versions
All versions are pinned in `gradle/libs.versions.toml` (version catalog). Update there, not in individual `build.gradle.kts` files.

## Design principle: fail loudly

Every real defect found in this library and its consumers on 2026-08-19/20 was a **silent** failure —
correct-looking behaviour with no trace:

- an upload temp file falling back to umask permissions, logging nothing
- a bind failure swallowed and surfacing as an unexplained 60s timeout weeks later
- a peer told its vault had synced while the write was still pending, so a failed write recorded
  success and was never retried
- Compose resources reaching zero APK entries with a green build
- a read timeout silently doubling as a ceiling on handler runtime

None of these announced themselves. Several survived code review, green test suites, and in two cases
a successful device run. What caught them was someone asking what a green result did *not* cover.

So: when this library degrades a guarantee rather than failing outright, it must leave a trace the
application can act on. `startServer`'s `onInsecureTempFile` exists for exactly that reason — the
POSIX fallback is legitimate, but taking it silently would mean vault bytes at umask permissions with
nothing in a bug report. Prefer an optional typed callback (following `onPeerTlsProtocol`) over a log
line; this library has no logger and a previous commit deliberately stripped `println` debugging.

This matters most for the iOS TLS work in `docs/ios-mtls.md`, where the failure modes are all quiet:
a mis-derived SPKI pin fails closed forever, and a plaintext fallback would look exactly like success.

## Known issues

### Discovery / Connection have no production consumer

Neither passman nor moviePicker calls `Discovery` or `Connection` — both built their own peer
finding instead (passman uses QR pairing with a safety number, moviePicker shows its IP for manual
entry plus `NetworkScanner`'s subnet sweep). Their only callers are this repo's own example apps.
They also carry the bugs below and have no tests. Removal is under consideration; do not invest in
them without checking that decision first.

Verified root causes, so nobody re-derives them:

- **"Address already in use" on restart.** `DiscoveryServer.stopListening()` cancels `listenJob` and
  replaces the socket *builder*, but never closes the bound socket. Ktor sockets are not children of
  the coroutine that created them — `NIOSocketImpl` builds `socketContext` as a parentless `Job` — so
  cancelling the coroutine cannot close the port. Each stop also leaks a `SelectorManager`.
- **A single malformed UDP datagram kills discovery permanently.** The `catch (Throwable)` in
  `DiscoveryServer.listen()` closes the socket, and `bind()` sits *outside* the `while(true)`, so
  there is no restart — the loop then spins on a dead channel forever. `Constants.json` sets no
  `ignoreUnknownKeys`, so a peer running a newer `Host` schema is equally fatal.
- **`ConnectionServer` never emits received data.** `availableForRead` is 0 immediately after
  `openReadChannel()`, so the read buffer is `ByteArray(0)` and the loop breaks before emitting. It
  also re-`bind()`s a new listener per accepted connection inside the accept loop.
- **`Host` equality excludes `hostAddress`/`port`** (they are body properties, not constructor
  params). Same-named devices collapse into one map entry, and because `Map.put` keeps the existing
  key, a peer that changes IP keeps refreshing the *stale* key's timestamp — so eviction never fires
  and `Connection` keeps sending to the dead address.
- **iOS `NetInterface.getAddresses()` always returns empty and leaks per call.** `nativeMain/NetInterface.kt`
  reads `ifaddrs.value` *before* `getifaddrs()` populates it, so the walk never runs; `freeifaddrs`
  then frees null while the real list leaks — once per broadcast tick. `getLocalAddress()` in the
  same file does it correctly.
- **iOS's empty peer list was probably never a Ktor bug.** The long-standing attribution to
  [KTOR-6489](https://youtrack.jetbrains.com/issue/KTOR-6489) does not survive scrutiny. Per Apple's
  TN3179, *receiving* incoming UDP **broadcast** on iOS requires local network access **and** the
  restricted `com.apple.developer.networking.multicast` entitlement, while *sending* broadcast and
  *accepting* inbound TCP do not. The example app had neither that entitlement nor an
  `NSLocalNetworkUsageDescription` (`ios/ios/ios.entitlements` carried only `app-sandbox` and
  `files.user-selected.read-only`). That is an exact match for the reported symptom: other devices
  saw iOS, iOS saw nobody. The entitlement is restricted and needs Apple approval
  (https://developer.apple.com/contact/request/networking-multicast), and it is unenforced in the
  Simulator — which is why this would have looked like a library bug. Unproven, since the code is
  now deleted, but it is the far better explanation and matters if discovery ever returns.
- The `@ThreadLocal` singletons were also **not** the cause (that annotation re-resolves only at
  explicit object references; the worker reached `listen()` through a captured dispatch receiver,
  i.e. the same instance). A genuine per-thread instance split *did* exist in
  `Connection.receiveData`, where `ConnectionServer` was referenced lazily inside a `channelFlow`
  under `flowOn(Dispatchers.Default)`.
- iOS can crash if the desktop app starts discovery first.

### Platform parity

mTLS with SPKI pinning is **JVM-only** — it lives in `jvmSources`. `ktor-network-tls` is declared in
`commonMain` and imported nowhere. iOS/native peers have no encrypted transport.

## iOS gotchas

- There is no CocoaPods step any more. The plugin was in maintenance mode and neither consumer used
  the podspec — both build their own framework from an `iosdi`-style module. `build_pods.sh` is gone.
  When the example iOS app returns it should use direct integration
  (`embedAndSignAppleFrameworkForXcode`), which is what JetBrains' own template ships and the only
  path Swift export supports.
- `iosX64` is deliberately absent. It existed only for the deleted `:presenter`, which resolved its
  target from `SDK_NAME`/`NATIVE_ARCH` and fell back to `iosX64` outside Xcode. Both consumers build
  for `iosArm64` + `iosSimulatorArm64` only.
- `kotlin.incremental=false` was removed from `gradle.properties`. It was documented as necessary for
  KMP/native and was not: native incremental compilation is governed by `kotlin.incremental.native`
  and is off by default, so that flag only ever disabled it for JVM.
- Closing the iOS TLS gap is researched but not started — see `docs/ios-mtls.md`. Headlines: mutual
  TLS is achievable including the server side via Network.framework with no custom cinterop;
  `ktor-network-tls` can never provide it (it throws on native); and the SPKI pin must be parsed out
  of the certificate DER, because no Security-framework key API returns SubjectPublicKeyInfo.
