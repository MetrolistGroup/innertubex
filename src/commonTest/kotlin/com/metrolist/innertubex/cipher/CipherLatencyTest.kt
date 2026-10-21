package com.metrolist.innertubex.cipher

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CipherLatencyTest {
    @Test
    fun staleTableServesKnownPlayersWithoutWaitingForRefresh() =
        runBlocking {
            val engine = MockEngine { CompletableDeferred<Unit>().await().let { error("unreachable") } }
            val repository =
                object : PlayerConfigRepository {
                    override val enabled = true
                    override val defaultSourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
                    override val sourceUrl = defaultSourceUrl
                    override var cachedJson = """{"schemaVersion":1,"players":{"aaaaaaaa":{"sig":"A(1,2,INPUT)","nClass":"B","sts":1}}}"""
                    override var cachedAtMs = 0L
                    override var cachedSourceUrl = defaultSourceUrl
                    override var cachedEtag = ""
                }
            val store = RemotePlayerConfigStore(HttpClient(engine), repository)
            val config = withTimeout(2_000) { store.getConfig("https://www.youtube.com/s/player/aaaaaaaa/player.js") }
            assertNotNull(config)
            Unit
        }

    @Test
    fun oversizedPlayerScriptIsNotRetried() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("x", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, (9 * 1024 * 1024).toString()))
                }
            val service = YouTubeCipherService(HttpClient(engine))
            try {
                assertFailsWith<IllegalStateException> {
                    service.playerCode(
                        "https://www.youtube.com/s/player/aaaaaaaa/player_ias.vflset/en_US/base.js",
                    )
                }
                assertEquals(1, engine.requestHistory.size)
            } finally {
                service.dispose()
            }
        }
}
