package com.k2k.test

import io.ktor.server.engine.EmbeddedServer

/**
 * Starts the server on whatever port the OS hands it and returns that port.
 *
 * Tests must not pick a port with `ServerSocket(0).use { it.localPort }` and bind it later: the
 * probe socket closes before the server binds, so any other test class starting in that window can
 * take the port. The loser then talks to a stranger's listener and fails with something unrelated
 * to what it was testing — an intermittent failure that reads like a product bug.
 *
 * Passing port 0 through to the engine and reading the resolved connector closes that window,
 * because the port is never unbound between choosing and using it. It also removes the need to poll
 * for readiness: the connector resolves only once the socket is accepting.
 */
internal suspend fun EmbeddedServer<*, *>.startOnEphemeralPort(): Int {
    start(wait = false)
    return engine.resolvedConnectors().first().port
}
