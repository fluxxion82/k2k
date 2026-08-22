package com.k2k

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The address this picks is the one a peer is told to connect to, so getting it wrong does not
 * degrade sync — it prevents it, with no error anywhere. The user reads an address off one screen,
 * types it into another device, and nothing happens.
 *
 * These cases are fixed address sets rather than whatever the test machine enumerates, because the
 * bug this guards against is precisely that the old logic was correct on every development machine
 * and wrong on a handset.
 */
class LocalAddressSelectionTest {
    private fun v4(literal: String): Inet4Address =
        InetAddress.getByName(literal) as Inet4Address

    /**
     * The exact address set from the Pixel 8 that surfaced this, in the order it enumerated them.
     * The CLAT address comes first, which is the whole problem.
     */
    @Test
    fun handsetOnMobileData_prefersTheLanAddressOverTheClatAddress() {
        val enumerated = listOf(
            v4("192.0.0.4"),    // v4-rmnet17, 464XLAT CLAT — enumerated first, routes nowhere on the LAN
            v4("127.0.0.1"),    // lo
            v4("192.168.4.30"), // wlan0 — the address a peer can actually reach
        )

        assertEquals("192.168.4.30", selectLocalAddress(enumerated))
    }

    @Test
    fun loopbackAndLinkLocalAreNeverReturned() {
        assertNull(selectLocalAddress(listOf(v4("127.0.0.1"), v4("169.254.10.1"))))
    }

    /** All three RFC 1918 ranges count as reachable-on-the-LAN. */
    @Test
    fun everySiteLocalRangeIsPreferred() {
        for (siteLocal in listOf("10.1.2.3", "172.16.5.6", "192.168.1.100")) {
            assertEquals(
                siteLocal,
                selectLocalAddress(listOf(v4("192.0.0.4"), v4(siteLocal))),
                "$siteLocal is RFC 1918 and should win over the CLAT address",
            )
        }
    }

    /**
     * A host with only a routable public address should still be reachable. Returning nothing
     * because nothing was site-local would be worse than returning the only address it has.
     */
    @Test
    fun aNonSiteLocalAddressIsUsedWhenNothingBetterExists() {
        assertEquals("203.0.113.7", selectLocalAddress(listOf(v4("203.0.113.7"))))
    }

    /** First non-site-local wins the fallback, so the choice is at least deterministic. */
    @Test
    fun theFallbackIsTheFirstNonSiteLocalCandidate() {
        assertEquals(
            "192.0.0.4",
            selectLocalAddress(listOf(v4("192.0.0.4"), v4("203.0.113.7"))),
        )
    }

    @Test
    fun noCandidatesYieldsNull() {
        assertNull(selectLocalAddress(emptyList()))
    }
}
