# Closing the iOS TLS gap

Research notes, 2026-08-19. Everything here was verified against the iOS 26.2 SDK headers, the
Kotlin/Native 2.4.20-RC platform klibs, and the Ktor 3.5.2 klib ABI on this machine — not from
memory. Claims that could not be verified are marked as such, and they matter as much as the rest.

## The problem

`com.k2k.test.tls` (mutual TLS + SPKI pinning) is JVM-only: it is built on Netty's `SslContext`, a
custom `X509TrustManager`, and reading the verified peer certificate off the `SslHandler`. None of
that exists on Kotlin/Native. iOS peers therefore have no encrypted transport at all.

## What does not work, and why

**`ktor-network-tls` is dead weight.** It is declared in `commonMain` dependencies and imported
nowhere, and that is fortunate, because on native it does not merely lack configuration — it
refuses to run. From `TLSClientSession.nonJvm.kt` at tag 3.5.2:

```kotlin
internal actual suspend fun openTLSSession(...): Socket {
    error("TLS sessions are not supported on Native platform.")
}
```

`Socket.tls()` compiles on iOS and throws at runtime. The whole native `TLSConfigBuilder` surface is
`serverName` and nothing else; `certificates`, `trustManager`, and `addKeyStore` are all JVM-only.
**Remove the dependency** rather than leave it looking like an option.

**`ktor-server-cio` cannot terminate TLS on any platform**, JVM included — the check lives in common
code and throws `UnsupportedOperationException("CIO Engine does not currently support HTTPS")`.
Tracked as KTOR-694, open since 2020.

**ATS pinning (`NSPinnedDomains`) is unavailable to us.** It covers only the URL Loading System, not
Network.framework, CFNetwork, or BSD sockets. It looks like the obvious answer and is not one.

## What does work

**Client: the Ktor Darwin engine, both directions through one handler.** `KtorNSURLSessionDelegate`
overrides only the *task-level* challenge method, and Apple's documented behaviour is that when no
session-level delegate exists, session-level challenges fall through to the task delegate. So a
single `handleChallenge { session, task, challenge, complete -> }` receives **both**
`NSURLAuthenticationMethodServerTrust` and `NSURLAuthenticationMethodClientCertificate`. Branch on
`challenge.protectionSpace.authenticationMethod` and answer anything else with
`PerformDefaultHandling`.

**Server: Network.framework, and every symbol is already bound.** Kotlin/Native 2.4.20-RC ships
`platform.Network` and `platform.Security` klibs for `ios_arm64` containing `nw_listener_create`,
`nw_parameters_create_secure_tcp`, `sec_identity_create`, `sec_protocol_options_set_local_identity`,
`sec_protocol_options_set_peer_authentication_required`, `sec_protocol_options_set_verify_block`,
and `sec_protocol_metadata_access_peer_certificate_chain`, with Objective-C blocks mapped to Kotlin
lambdas. **No custom cinterop `.def` is required.** (Verify with `klib dump-metadata`; grepping the
klib file is meaningless, it is an archive.)

The mTLS switch is one call. From `SecProtocolOptions.h`:

> `sec_protocol_options_set_peer_authentication_required` — Enable or disable peer authentication.
> **Clients default to true, whereas servers default to false.**

Set it `true` on a listener and the server both requests and requires a client certificate. The
sibling `..._optional` is SPI, `API_UNAVAILABLE` on every platform — so peer auth is all-or-nothing,
with no "request but tolerate absence" mode. For a pairing-based LAN protocol that is fine.

`SecIdentityCreate(allocator, certificate, privateKey)` is `API_AVAILABLE(ios(11.2))` and in the
klib, despite having **no page on developer.apple.com** — which is why a docs-only search concludes
it does not exist. It builds an identity directly from a cert plus key, so the private key never has
to enter the keychain. That removes the entire `kSecAttrIsPermanent` / identity-formation dance and
its classic failure mode (a key whose `kSecAttrApplicationLabel` was set manually never pairs with a
certificate, and `SecItemCopyMatching` then returns -25300 forever).

## Computing the SPKI pin — do not use the obvious API

The JVM pins `SHA-256(cert.publicKey.encoded)`, which is the DER SubjectPublicKeyInfo. On Darwin:

- `SecKeyCopyExternalRepresentation` returns **PKCS#1** for RSA and a bare **X9.63** point
  (`04 || X || Y`) for EC. Neither is SPKI. Hashing it produces a pin that can never match the JVM.
- `SecCertificateCopySubjectPublicKeyInfoSHA256Digest` **does exist and does exactly what we want** —
  but it is SPI, absent from public headers, and present in the SDK `.tbd` with no
  `allowable-clients` restriction. It would link cleanly. That makes it a quiet App Store risk
  rather than a loud compile error. Do not use it.
- CryptoKit's `.derRepresentation` is genuinely SPKI, but CryptoKit is Swift-only and unreachable
  from Kotlin/Native cinterop, and has no RSA.

**Decision: parse the SPKI out of the certificate DER in `commonMain`.** `SecCertificateCopyData`
gives the full DER; the SPKI is a *contiguous byte substring* of it (verified empirically), reachable
by a shallow TLV walk to element 6 of the TBSCertificate. This is better than the TrustKit
prepend-a-hardcoded-ASN.1-header approach on three counts:

1. Algorithm- and size-agnostic. The header table covers six combinations; anything else (RSA-8192,
   P-521, Ed25519) is unsupported.
2. Byte-identical to the JVM by construction, so pins provably agree across platforms.
3. It fails closed *loudly*. The header table encodes a length, so an unusual key size yields a
   wrong pin with no error — an honest paired device that can never connect, and no diagnostic.

The same Kotlin runs on JVM and native, so it can be tested in `commonTest` against the JVM's
`publicKey.encoded` as ground truth. Given that the entire trust model rests on this one value
matching across platforms, that oracle is worth having.

Note also: Ktor's own `CertificatePinner` emits base64 (`sha256/<b64>`); `SpkiPinning.kt` emits
lowercase hex. Same bytes, different rendering — convert at the boundary or the pins silently never
match.

## Recommended sequencing

**Step 1 — iOS as mTLS client, JVM peer as server.** Roughly 3-5 days. Every part of it (pin
computation, identity loading, the cross-platform pin test, ATS and permission plumbing) is a
prerequisite for full parity too, so none of it is throwaway.

What it costs: iOS can no longer *receive* a push. `/sync-pull/{kind}` is already client-initiated
and returns data, so the pull direction may already be served; the `/upload`-to-iOS direction is not.

**Step 2 — a one-day spike on the backgrounding question** (see Unknowns). It decides whether
iOS-as-server is worth building at all.

**Step 3 — then choose** full parity (iOS also serves) or application-layer encryption.

On full parity, the honest cost: `CIOApplicationCall` is `internal`, so routing over a
Network.framework transport means hand-porting ~500-700 lines of Ktor's CIO engine (Apache-2.0, same
licence as k2k). `ServerIncomingConnection` and `startServerConnectionPipeline` are public
`@InternalAPI` and mean no HTTP parser has to be written. Ongoing cost is that `@InternalAPI` plus a
ported engine both break on Ktor upgrades.

On application-layer encryption: it looks like a shortcut and is not. It replaces an audited TLS 1.3
stack with a handshake we own — replay protection, forward secrecy, key confirmation, transcript
binding, downgrade resistance, nonce management. If we go there, use a Noise pattern (`Noise_KK`
maps cleanly onto "both sides already know each other's static public key", which is exactly what
SPKI pairing gives us) rather than inventing one, and get it reviewed.

## Pairing stays plaintext

It is the bootstrap *before* devices know each other's pins, so TLS has no trust anchor to check.
The route is already hardened for hostile input: rate limiting, bounded bodies, a single-accept
slot, uniform responses, and the literal socket IP rather than reverse DNS. Adding TLS there would
be theatre. One invariant to preserve through any of the options above:
`PairingBundleExchange.onPeerBundle` must keep receiving `null` for the caller pin — a plaintext
listener must never hand handlers a fabricated identity.

## Consumer-facing requirements

These are app-side and cannot be shipped by a library. They have to be documented.

- **`NSLocalNetworkUsageDescription`** — iOS 14+. Needed because making an outgoing LAN connection
  requires local network permission. *Accepting* inbound TCP does not, so an iOS server avoids the
  prompt entirely. The Simulator does not enforce local network privacy, so device testing is
  mandatory.
- **An ATS exception.** iOS 17+ blocks connections to IP literals by default, and ATS permits
  *tightening* server trust but never *loosening* it — so a self-signed peer at `https://192.168.1.5`
  needs an `NSExceptionDomains` entry, an `NSAllowsLocalNetworking` entry, or both.

## Unknowns — resolve before designing around any of these

- **Does an `NWListener` keep accepting after the app backgrounds?** Highest-value unknown. No
  entitlement grants a background TCP listener, and if accepts stop on suspension then iOS-as-server
  is a foreground-only feature regardless of code quality. Apple DTS says maintaining TCP in the
  background is "No", and the guidance is to close the listener on background and reopen on
  foreground.
- Whether `NSAllowsLocalNetworking = YES` **alone** restores IP-literal HTTPS on iOS 17+, or whether
  `NSExceptionDomains` is mandatory. Apple's page supports both readings.
- Whether ATS accepts an **IPv4** CIDR in `NSExceptionDomains` — Apple's only example is IPv6.
- Whether KTOR-8828 (a `Long`/`Int` `completionHandler` mismatch that triggers when two or more iOS
  targets are declared, which we do) still reproduces on 3.5.2. Budget a day.
- Whether a Secure Enclave-backed identity works for TLS client auth. It was definitively broken in
  2016 (Apple radar 25978027, wrong signature algorithm in `CertificateVerify`); the most recent DTS
  statement is still "should work", never affirmatively documented in ten years. SE keys are also
  P-256-only. **Do not design around it**; software-held keys are the right call for ephemeral LAN
  sessions.

## Incidental: iOS 26 moves the TLS floor

For apps linked against the iOS 26 SDK, the default minimum TLS version for `URLSession` and Network
framework rises from 1.0 to 1.2 (Apple ref 135996267). Restorable via
`sec_protocol_options_set_min_tls_protocol_version`. This lands on us independently of any work here.
