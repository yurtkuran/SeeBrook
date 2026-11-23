package cc.seebrook.shared.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun platformEngine(): HttpClientEngine = Darwin.create()
