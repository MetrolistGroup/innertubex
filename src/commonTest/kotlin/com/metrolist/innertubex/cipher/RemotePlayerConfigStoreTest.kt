package com.metrolist.innertubex.cipher

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
                    override var sourceUrl = defaultSourceUrl
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

    @Test
    fun unusablePersistedBodyDoesNotSendValidatorAndRecovers() =
        runBlocking {
            for (body in listOf("", "{")) {
                val repository =
                    repository().apply {
                        cachedJson = body
                        cachedSourceUrl = sourceUrl
                        cachedEtag = "old-version"
                        cachedAtMs = 1L
                    }
                val engine =
                    MockEngine { request ->
                        if (request.headers[HttpHeaders.IfNoneMatch] != null) {
                            respond("", HttpStatusCode.NotModified)
                        } else {
                            respondOk(TABLE)
                        }
                    }
                val client = HttpClient(engine)
                try {
                    val store = RemotePlayerConfigStore(client, repository) { 2L }
                    assertEquals(20613, store.getSignatureTimestamp(PLAYER))
                    assertNull(engine.requestHistory.single().headers[HttpHeaders.IfNoneMatch])
                    assertEquals(TABLE, repository.cachedJson)
                    assertEquals("", repository.cachedEtag)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun notModifiedWithoutRepresentationRetriesUnconditionally() =
        runBlocking {
            val repository = repository()
            var requests = 0
            val engine =
                MockEngine {
                    if (++requests == 1) respond("", HttpStatusCode.NotModified) else respondOk(TABLE)
                }
            val client = HttpClient(engine)
            try {
                val store = RemotePlayerConfigStore(client, repository) { 100L }
                assertEquals(20613, store.getSignatureTimestamp(PLAYER))
                assertEquals(2, requests)
                assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.IfNoneMatch] == null })
            } finally {
                client.close()
            }
        }

    @Test
    fun conditionalRevalidationRetainsHintsAndEpochAndRenewsFreshness() =
        runBlocking {
            var now = 100L
            val repository = repository()
            var requests = 0
            val engine =
                MockEngine {
                    if (++requests == 1) {
                        respond(TABLE, headers = headersOf(HttpHeaders.ETag, "version-one"))
                    } else {
                        respond("", HttpStatusCode.NotModified)
                    }
                }
            val client = HttpClient(engine)
            try {
                val store = RemotePlayerConfigStore(client, repository) { now }
                store.refreshIfStale()
                val epoch = store.configEpoch
                now += 6 * 60 * 60 * 1000L
                store.refreshIfStale()
                assertEquals("version-one", engine.requestHistory.last().headers[HttpHeaders.IfNoneMatch])
                assertEquals(TABLE, repository.cachedJson)
                assertEquals(now, repository.cachedAtMs)
                assertEquals(20613, store.getCachedSignatureTimestamp(PLAYER))
                assertEquals(epoch, store.configEpoch)
                store.refreshIfStale()
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun newRepresentationWithoutEtagDropsTheOldValidator() =
        runBlocking {
            val repository =
                repository().apply {
                    cachedJson = TABLE
                    cachedSourceUrl = sourceUrl
                    cachedEtag = "old-version"
                }
            val engine = MockEngine { respondOk(TABLE.replace("20613", "20614")) }
            val client = HttpClient(engine)
            try {
                val store = RemotePlayerConfigStore(client, repository) { 100L }
                assertTrue(store.forceRefresh())
                assertEquals("old-version", engine.requestHistory.single().headers[HttpHeaders.IfNoneMatch])
                assertEquals("", repository.cachedEtag)
                assertEquals(20614, store.getCachedSignatureTimestamp(PLAYER))
            } finally {
                client.close()
            }
        }

    @Test
    fun legacyTimestampCacheIncludesRequestedHash() =
        runBlocking {
            val repository =
                repository().apply {
                    sourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/fixed.json"
                }
            val engine = MockEngine { respondOk("""{"playerHash":"aaaaaaaa","sts":20613}""") }
            val client = HttpClient(engine)
            try {
                val store = RemotePlayerConfigStore(client, repository)
                assertEquals(20613, store.getSignatureTimestamp(PLAYER))
                val other = PLAYER.replace("aaaaaaaa", "bbbbbbbb")
                assertNull(store.getCachedSignatureTimestamp(other))
                assertNull(store.getSignatureTimestamp(other))
                assertEquals(2, engine.requestHistory.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun lateLegacyFailurePreservesFreshSuccessfulTimestamp() =
        runBlocking {
            val repository =
                repository().apply {
                    sourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/{playerHash}.json"
                }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val engine =
                MockEngine {
                    if (++requests == 1) {
                        started.complete(Unit)
                        release.await()
                        respond("", HttpStatusCode.InternalServerError)
                    } else {
                        respondOk("""{"playerHash":"aaaaaaaa","sts":20613}""")
                    }
                }
            val client = HttpClient(engine)
            try {
                val store = RemotePlayerConfigStore(client, repository)
                val first = async { store.getSignatureTimestamp(PLAYER) }
                started.await()
                assertEquals(20613, store.getSignatureTimestamp(PLAYER))
                release.complete(Unit)
                assertEquals(20613, first.await())
                assertEquals(20613, store.getCachedSignatureTimestamp(PLAYER))
                assertEquals(20613, store.getSignatureTimestamp(PLAYER))
                assertEquals(2, requests)
            } finally {
                release.complete(Unit)
                client.close()
            }
        }

    @Test
    fun sourceAllowlistRejectsLiteralAndEncodedDotSegments() {
        val client = HttpClient(MockEngine { error("Validation must not fetch") })
        try {
            val store = RemotePlayerConfigStore(client, repository())
            for (prefix in listOf(
                "https://raw.githubusercontent.com/MetrolistGroup/faraday/",
                "https://cdn.jsdelivr.net/gh/MetrolistGroup/faraday@main/",
                "https://github.com/MetrolistGroup/faraday/releases/download/",
            )) {
                for (segment in listOf(".", "..", "%2e", "%2E%2e", ".%2e", "%2e.")) {
                    assertNull(store.validatedSourceUrlOrNull("$prefix$segment/attacker/player_configs.json"))
                }
            }
        } finally {
            client.close()
        }
    }

    private companion object {
        const val PLAYER = "https://www.youtube.com/s/player/aaaaaaaa/player.js"
        const val TABLE = """{"schemaVersion":1,"players":{"aaaaaaaa":{"sig":"A(1,2,INPUT)","nClass":"B","sts":20613}}}"""
    }

    private fun repository() =
        object : PlayerConfigRepository {
            override val enabled = true
            override val defaultSourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
            override var sourceUrl = defaultSourceUrl
            override var cachedJson = ""
            override var cachedAtMs = 0L
            override var cachedSourceUrl = ""
            override var cachedEtag = ""
        }
}
