package com.k2k.tls

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_connection_t
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create_with_port
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_cancelled
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_reuse_local_address
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.sec_identity_create
import platform.Security.sec_protocol_metadata_t
import platform.Security.sec_protocol_options_set_local_identity
import platform.Security.sec_protocol_options_set_min_tls_protocol_version
import platform.Security.sec_protocol_options_set_peer_authentication_required
import platform.Security.sec_protocol_options_set_verify_block
import platform.Security.sec_trust_copy_ref
import platform.Security.sec_trust_t
import platform.Security.tls_protocol_version_TLSv12
import platform.darwin.dispatch_get_global_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import kotlin.concurrent.AtomicReference

/**
 * A TLS listener that requires and pins its clients, built on Network.framework.
 *
 * This exists because Ktor cannot provide it. `ktor-server-cio` refuses HTTPS on every platform —
 * the check lives in common code and throws `UnsupportedOperationException` — and `ktor-network-tls`
 * has no native implementation at all. Network.framework is the only way an iOS process can
 * terminate TLS, let alone demand a client certificate.
 *
 * The mutual-TLS switch is a single call, and its default is the trap:
 * `sec_protocol_options_set_peer_authentication_required` defaults to **true for clients and false
 * for servers**. Left alone, a listener negotiates perfectly ordinary one-way TLS and never asks the
 * peer to prove anything — which looks like success. Setting it explicitly is what makes this mutual.
 *
 * Note there is no "request but tolerate absence" mode available:
 * `sec_protocol_options_set_peer_authentication_optional` is SPI, `API_UNAVAILABLE` on every
 * platform. Peer auth is all-or-nothing, which for a pairing-based protocol is the behaviour we want
 * anyway.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NativeTlsListener(
    private val identity: DeviceIdentity,
    /**
     * Pins of devices allowed to connect. **Empty rejects everything**: an empty allow-list must
     * never mean "allow all", or a device that has not finished pairing would accept the world.
     */
    private val allowedClientPins: Set<String>,
    private val onConnection: (nw_connection_t) -> Unit,
    private val onFailure: (String) -> Unit = {},
) {
    private val listener = AtomicReference<nw_listener_t?>(null)
    private val readyPort = AtomicReference<Int?>(null)

    /**
     * Binds and starts accepting. [port] may be 0 to let the OS choose, in which case [boundPort]
     * reports what it picked once the listener reaches ready.
     */
    fun start(port: Int) {
        require(listener.value == null) { "listener already started" }

        val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)
        val parameters = nw_parameters_create_secure_tcp(
            configure_tls = { tlsOptions ->
                val options = tlsOptions?.let { nw_tls_copy_sec_protocol_options(it) }
                if (options != null) {
                    // sec_identity_t is an Objective-C object, not a CFTypeRef -- it is
                    // ARC-managed and must NOT be CFReleased. The SecIdentityRef it was built from
                    // is a CPointer and is released inside asSecIdentity().
                    identity.asSecIdentity()?.let { sec_protocol_options_set_local_identity(options, it) }
                    // Servers default to FALSE here. Without this line the handshake succeeds and no
                    // client certificate is ever requested — one-way TLS wearing a mutual-TLS name.
                    sec_protocol_options_set_peer_authentication_required(options, true)
                    sec_protocol_options_set_min_tls_protocol_version(options, tls_protocol_version_TLSv12)
                    sec_protocol_options_set_verify_block(
                        options,
                        { _: sec_protocol_metadata_t?, trust: sec_trust_t?, complete: ((Boolean) -> Unit)? ->
                            complete?.invoke(trust != null && isPinned(trust))
                        },
                        queue,
                    )
                }
            },
            configure_tcp = { },
        ) ?: run {
            onFailure("could not create TLS parameters")
            return
        }

        // Without this, a restart on the same port fails while the previous socket lingers in
        // TIME_WAIT — the "address already in use" this project has hit before.
        nw_parameters_set_reuse_local_address(parameters, true)

        val created = nw_listener_create_with_port(port.toString(), parameters) ?: run {
            onFailure("could not bind port $port")
            return
        }
        listener.value = created

        nw_listener_set_queue(created, queue)
        nw_listener_set_state_changed_handler(created) { state, _ ->
            when (state) {
                nw_listener_state_ready -> readyPort.value = nw_listener_get_port(created).toInt()
                nw_listener_state_failed -> onFailure("listener failed")
                nw_listener_state_cancelled -> readyPort.value = null
                else -> Unit
            }
        }
        nw_listener_set_new_connection_handler(created) { connection ->
            if (connection != null) onConnection(connection)
        }
        nw_listener_start(created)
    }

    /** The bound port once ready, or null before that. */
    val boundPort: Int? get() = readyPort.value

    fun stop() {
        listener.value?.let { nw_listener_cancel(it) }
        listener.value = null
        readyPort.value = null
    }

    /**
     * True when the peer's leaf certificate carries a pin we accept.
     *
     * Deliberately does NOT call `SecTrustEvaluateWithError`. These are self-signed certificates with
     * no chain and no CA, so a trust evaluation would always fail and would be answering a question
     * we do not care about. The question we care about is whether this is a device we paired with.
     */
    private fun isPinned(trust: sec_trust_t): Boolean {
        if (allowedClientPins.isEmpty()) return false
        val secTrust = sec_trust_copy_ref(trust) ?: return false
        return try {
            leafPinOf(secTrust) in allowedClientPins
        } finally {
            CFRelease(secTrust as CFTypeRef)
        }
    }
}
