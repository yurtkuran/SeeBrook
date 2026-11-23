package cc.seebrook.shared.client

import io.ktor.client.engine.HttpClientEngine

/** The backend never calls its own API; Android chooses its engine when it gets its own target (ED-16). */
internal actual fun platformEngine(): HttpClientEngine =
    throw UnsupportedOperationException("No HTTP engine on the JVM target yet")
