package com.k2k.tls

import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.NSURLAuthenticationMethodClientCertificate
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.create
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecIdentityCreate
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.Security.SecCertificateRef

/**
 * Wires SPKI-pinned mutual TLS into a Ktor Darwin client.
 *
 * Both halves of mutual TLS arrive at a single handler, which is not obvious and is worth stating:
 * Ktor's `KtorNSURLSessionDelegate` implements only the *task*-level challenge method, and Apple
 * documents that session-level challenges — `ServerTrust` and `ClientCertificate` among them — fall
 * through to the task delegate when no session delegate handles them. So one `handleChallenge` block
 * receives both, and it must branch on the authentication method rather than assuming which it got.
 *
 * Anything other than those two is answered with `PerformDefaultHandling`, which hands it back to
 * the system rather than silently failing or silently accepting.
 *
 * The `.convert()` on every disposition constant is NOT decoration — do not remove it. Ktor's
 * `ChallengeHandler` types its completion handler differently in the shared native metadata
 * compilation than in the per-target one when two or more iOS targets are declared, which is exactly
 * our configuration (KTOR-8828, open, JetBrains-filed; reproduced on Ktor 3.5.2 here). Without the
 * conversion, `compileKotlinIosSimulatorArm64` succeeds and `compileNativeMainKotlinMetadata` fails
 * with "actual type is 'Long', but 'Int' was expected". `convert()` satisfies both.
 *
 * There is no CA and no hostname check here by design: trust comes entirely from the pin. That is
 * why the server-trust branch never calls `SecTrustEvaluateWithError` — a self-signed peer with no
 * chain would always fail it, and passing would tell us nothing we care about. What we care about is
 * whether the leaf's SPKI is one we recorded at pairing time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun DarwinClientEngineConfig.installMutualTls(
    /** The identity this device presents when a peer asks for a client certificate. */
    identity: DeviceIdentity,
    /** Pins of peers we will talk to. Empty trusts nothing: fail closed, never fail open. */
    serverPins: Set<String>,
) {
    handleChallenge { _, _, challenge, completionHandler ->
        when (challenge.protectionSpace.authenticationMethod) {
            NSURLAuthenticationMethodServerTrust -> {
                val trust = challenge.protectionSpace.serverTrust
                val pin = trust?.let { leafPinOf(it) }
                if (pin != null && pin in serverPins) {
                    completionHandler(
                        NSURLSessionAuthChallengeUseCredential.convert(),
                        NSURLCredential.create(trust = trust),
                    )
                } else {
                    // Cancel rather than PerformDefaultHandling: the default would consult the
                    // system trust store, which for a self-signed LAN peer is both meaningless and
                    // the wrong question. An unpinned peer is a refusal, not a fallback.
                    completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                }
            }

            NSURLAuthenticationMethodClientCertificate -> {
                val certificate = identity.certificateDer.toSecCertificate()
                if (certificate == null) {
                    completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    return@handleChallenge
                }
                try {
                    val secIdentity = SecIdentityCreate(null, certificate, identity.privateKey)
                    if (secIdentity == null) {
                        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    } else {
                        try {
                            completionHandler(
                                NSURLSessionAuthChallengeUseCredential.convert(),
                                NSURLCredential.create(
                                    identity = secIdentity,
                                    certificates = null,
                                    persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceNone,
                                ),
                            )
                        } finally {
                            CFRelease(secIdentity as CFTypeRef)
                        }
                    }
                } finally {
                    CFRelease(certificate as CFTypeRef)
                }
            }

            // Basic auth, NTLM, proxy challenges: not ours to answer.
            else -> completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
        }
    }
}

/**
 * The SPKI pin of the leaf certificate in [trust], or null if the chain is unreadable.
 *
 * The leaf is index 0 of the chain. `SecTrustCopyCertificateChain` replaces
 * `SecTrustGetCertificateAtIndex`, which has been deprecated since iOS 15.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun leafPinOf(trust: SecTrustRef): String? {
    val chain = SecTrustCopyCertificateChain(trust) ?: return null
    try {
        if (CFArrayGetCount(chain) <= 0L) return null
        val leaf = CFArrayGetValueAtIndex(chain, 0)?.reinterpretAsCertificate() ?: return null
        val data = SecCertificateCopyData(leaf) ?: return null
        try {
            val length = CFDataGetLength(data).toInt()
            if (length <= 0) return null
            val bytes = CFDataGetBytePtr(data) ?: return null
            return SpkiPin.ofCertificate(bytes.readBytes(length))
        } finally {
            CFRelease(data as CFTypeRef)
        }
    } catch (_: IllegalArgumentException) {
        // A peer certificate we cannot parse is a peer we cannot pin. Refuse rather than guess.
        return null
    } finally {
        CFRelease(chain as CFTypeRef)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun kotlinx.cinterop.COpaquePointer.reinterpretAsCertificate(): SecCertificateRef =
    this.reinterpret()

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toSecCertificate(): SecCertificateRef? {
    if (isEmpty()) return null
    return usePinned { pinned ->
        val data = platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        val cfData = platform.Foundation.CFBridgingRetain(data) as platform.CoreFoundation.CFDataRef
        try {
            platform.Security.SecCertificateCreateWithData(null, cfData)
        } finally {
            CFRelease(cfData as CFTypeRef)
        }
    }
}
