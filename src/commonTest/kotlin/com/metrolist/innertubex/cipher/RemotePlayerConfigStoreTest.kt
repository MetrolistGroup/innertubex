package com.metrolist.innertubex.cipher

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemotePlayerConfigStoreTest {
    @Test
    fun sourceAndPlayerValidatorsRejectUserInfoAndNonHttpsPorts() {
        val client = HttpClient(MockEngine { error("Validation must not fetch") })
        try {
            val store = RemotePlayerConfigStore(client, repository())
            val sources =
                listOf(
                    "raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json",
                    "cdn.jsdelivr.net/gh/MetrolistGroup/faraday@main/player_configs.json",
                    "github.com/MetrolistGroup/faraday/releases/download/tag/config.json",
                )
            for (source in sources) {
                val host = source.substringBefore('/')
                val path = source.substringAfter('/')
                assertNotNull(store.validatedSourceUrlOrNull("https://$source"))
                assertNotNull(store.validatedSourceUrlOrNull("https://$host:443/$path"))
                for (authority in listOf("$host:8443", "user@$host", "user:pass@$host", "@$host")) {
                    assertNull(store.validatedSourceUrlOrNull("https://$authority/$path"))
                }
            }
            val path = "/s/player/12345678/base.js"
            assertEquals(443, validatedPlayerScriptUrl("https://www.youtube.com$path").port)
            assertEquals(443, validatedPlayerScriptUrl("https://www.youtube.com:443$path").port)
            for (authority in listOf("www.youtube.com:8443", "user@www.youtube.com", "user:pass@www.youtube.com", "@www.youtube.com")) {
                assertFailsWith<IllegalArgumentException> { validatedPlayerScriptUrl("https://$authority$path") }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsUntrustedRemoteConfigSourceBeforeRequest() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val repository =
                object : PlayerConfigRepository {
                    override val enabled = true
                    override val defaultSourceUrl = "https://example.com/player_configs.json"
                    override val sourceUrl = defaultSourceUrl
                    override var cachedJson = ""
                    override var cachedAtMs = 0L
                    override var cachedSourceUrl = ""
                    override var cachedEtag = ""
                }
            val store = RemotePlayerConfigStore(HttpClient(engine), repository)

            assertNull(store.getSignatureTimestamp("https://www.youtube.com/s/player/12345678/player.js"))
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun freshUnknownHashDoesNotImmediatelyRefetchConfigTable() =
        runBlocking {
            val engine =
                MockEngine {
                    respondOk(
                        """{"schemaVersion":1,"players":{"aaaaaaaa":{"sig":"A(1,2,INPUT)","nClass":"B","sts":1}}}""",
                    )
                }
            val store = RemotePlayerConfigStore(HttpClient(engine), repository())
            val playerUrl = "https://www.youtube.com/s/player/deadbeef/player.js"

            assertNull(store.getConfig(playerUrl))
            assertFalse(store.forceRefresh(missingHash = "deadbeef"))
            assertNull(store.getConfig(playerUrl))

            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun skippedUnknownHashRefreshDoesNotReserveFailureCooldown() =
        runBlocking {
            var nowMs = 0L
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respondOk(
                        if (requests == 1) {
                            """{"schemaVersion":1,"players":{"aaaaaaaa":{"sig":"A(1,2,INPUT)","nClass":"B","sts":1}}}"""
                        } else {
                            """{"schemaVersion":1,"players":{"deadbeef":{"sig":"A(1,2,INPUT)","nClass":"B","sts":2}}}"""
                        },
                    )
                }
            val store = RemotePlayerConfigStore(HttpClient(engine), repository()) { nowMs }
            val playerUrl = "https://www.youtube.com/s/player/deadbeef/player.js"

            assertNull(store.getConfig(playerUrl))
            assertFalse(store.forceRefresh(missingHash = "deadbeef"))

            nowMs = 60_000L
            assertTrue(store.forceRefresh(missingHash = "deadbeef"))
            assertFalse(store.forceRefresh(missingHash = "cafebabe"))
            assertEquals(2, requests)
        }

    @Test
    fun concurrentUnknownHashRefreshesMakeOneRequestDuringCooldown() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    respondOk()
                }
            val store = RemotePlayerConfigStore(HttpClient(engine), repository())
            val start = CompletableDeferred<Unit>()

            val calls =
                List(16) {
                    async(Dispatchers.Default) {
                        start.await()
                        store.forceRefresh(missingHash = "deadbeef")
                    }
                }
            start.complete(Unit)
            requestStarted.await()
            releaseResponse.complete(Unit)

            calls.awaitAll()
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun concurrentStreamRejectionRefreshesMakeOneRequestDuringCooldown() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    respondOk()
                }
            val store = RemotePlayerConfigStore(HttpClient(engine), repository())
            val start = CompletableDeferred<Unit>()

            val calls =
                List(16) {
                    async(Dispatchers.Default) {
                        start.await()
                        store.refreshAfterStreamRejection()
                    }
                }
            start.complete(Unit)
            requestStarted.await()
            releaseResponse.complete(Unit)

            calls.awaitAll()
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun cancellationReleasesUnknownHashCooldownReservation() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            var requestCount = 0
            val engine =
                MockEngine {
                    requestCount++
                    if (requestCount == 1) {
                        requestStarted.complete(Unit)
                        awaitCancellation()
                    }
                    respondOk()
                }
            val store = RemotePlayerConfigStore(HttpClient(engine), repository())
            val first = launch { store.forceRefresh(missingHash = "deadbeef") }

            requestStarted.await()
            first.cancelAndJoin()
            store.forceRefresh(missingHash = "deadbeef")

            assertEquals(2, requestCount)
        }

    private fun repository() =
        object : PlayerConfigRepository {
            override val enabled = true
            override val defaultSourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
            override val sourceUrl = defaultSourceUrl
            override var cachedJson = ""
            override var cachedAtMs = 0L
            override var cachedSourceUrl = ""
            override var cachedEtag = ""
        }
}
