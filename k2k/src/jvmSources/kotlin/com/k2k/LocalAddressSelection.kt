package com.k2k

import java.net.Inet4Address

/**
 * Chooses which of a host's IPv4 addresses to hand a peer as "reach me here".
 *
 * Extracted from [NetInterface.getLocalAddress] so the choice can be tested against a fixed set of
 * addresses. The behaviour depends entirely on what a machine happens to enumerate, which makes it
 * exactly the kind of logic that is correct on every developer's laptop and wrong on a user's phone.
 *
 * **Prefer site-local.** Picking the first non-loopback address in enumeration order is wrong on any
 * handset with mobile data active: a 464XLAT device also carries a CLAT address on its cellular
 * interface (192.0.0.0/29, RFC 7335), and enumeration can place that ahead of `wlan0`. The result is
 * a LAN transport advertising an address nothing on the LAN can route to.
 *
 * Observed on a Pixel 8, where this returned `192.0.0.4` while the actual LAN address was
 * `192.168.4.30` on `wlan0`. The user-visible symptom was reading the pairing address off the screen,
 * typing it into another device, and sync simply failing.
 *
 * `isSiteLocalAddress` covers 10/8, 172.16/12 and 192.168/16 — the RFC 1918 ranges a peer on the same
 * LAN can actually reach — and correctly rejects the CLAT address, which lives in IETF protocol
 * assignment space rather than a private range.
 *
 * A non-site-local address is kept as a fallback rather than discarded: a machine on a routable
 * public address with no RFC 1918 interface should still be reachable, and returning nothing would be
 * worse than returning the only address it has.
 */
internal fun selectLocalAddress(candidates: Iterable<Inet4Address>): String? {
    var fallback: String? = null
    for (address in candidates) {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
        val host = address.hostAddress ?: continue
        if (address.isSiteLocalAddress) return host
        if (fallback == null) fallback = host
    }
    return fallback
}
