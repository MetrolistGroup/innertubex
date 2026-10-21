package com.metrolist.innertubex

import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRequestTest {
    @Test
    fun failedUnmaskedRetryKeepsMaskEnabled() =
        runBlocking {
            for (retryStatus in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden)) {
                val masks = mutableListOf<String?>()
                val client =
                    HttpClient(
                        MockEngine { request ->
                            masks += request.headers["X-Goog-FieldMask"]
                            respond("", if (masks.size % 2 == 1) HttpStatusCode.BadRequest else retryStatus)
                        },
                    ) { install(ContentNegotiation) { json() } }
                val innerTube = InnerTube(client)
                try {
                    repeat(2) {
                        val error = assertFailsWith<InnerTubeHttpException> { playerText(innerTube) }
                        assertEquals(retryStatus, error.status)
                    }
                    assertEquals(listOf(PLAYER_RESPONSE_FIELD_MASK, null, PLAYER_RESPONSE_FIELD_MASK, null), masks)
                } finally {
                    innerTube.close()
                    client.close()
                }
            }
        }

    @Test
    fun oversizedUnmaskedRetryKeepsMaskEnabled() =
        runBlocking {
            val masks = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        masks += request.headers["X-Goog-FieldMask"]
                        when (masks.size) {
                            1 -> respond("", HttpStatusCode.BadRequest)
                            2 -> respond("x".repeat(4097))
                            else -> respond("{}")
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            val innerTube = InnerTube(client)
            try {
                assertFailsWith<ResponseTooLargeException> { playerText(innerTube) }
                assertEquals("{}", playerText(innerTube))
                assertEquals(listOf(PLAYER_RESPONSE_FIELD_MASK, null, PLAYER_RESPONSE_FIELD_MASK), masks)
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun tvBearerRejectionDoesNotReadUnfinishedBody() =
        runBlocking {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                val body = ByteChannel()
                val engine = MockEngine { respond(body, status) }
                val client = HttpClient(engine)
                val innerTube = InnerTube(client)
                try {
                    withTimeout(2_000) {
                        assertNull(
                            innerTube.playerWithTvBearerSessionBound(
                                YouTubeClient.TVHTML5,
                                "video",
                                null,
                                innerTube.sessionSnapshot(),
                                "test-token",
                            ),
                        )
                    }
                    assertEquals(1, engine.requestHistory.size)
                    assertTrue(body.isClosedForRead)
                } finally {
                    body.cancel(null)
                    innerTube.close()
                    client.close()
                }
            }
        }

    @Test
    fun sapisidUsesFirstNonblankValueInPriorityOrder() {
        val client = HttpClient(MockEngine { error("Session updates must not fetch") })
        val innerTube = InnerTube(client)
        try {
            val cases =
                listOf(
                    "SAPISID=first; __Secure-3PAPISID=third; __Secure-1PAPISID=one" to "first",
                    "SAPISID=; __Secure-3PAPISID=third; __Secure-1PAPISID=one" to "third",
                    "SAPISID= ; __Secure-3PAPISID= ; __Secure-1PAPISID=one" to "one",
                    "__Secure-3PAPISID=third; __Secure-1PAPISID=one" to "third",
                    "SAPISID= ; __Secure-3PAPISID= ; __Secure-1PAPISID= " to null,
                )
            for ((cookie, expected) in cases) {
                innerTube.cookie = cookie
                assertEquals(expected, innerTube.sessionSnapshot().sapisid)
                assertEquals(expected != null, innerTube.hasSapCookieAuth())
                innerTube.replaceSession(cookie, null, null, "0", false)
                assertEquals(expected, innerTube.sessionSnapshot().sapisid)
                assertEquals(expected != null, innerTube.hasSapCookieAuth())
            }
        } finally {
            innerTube.close()
            client.close()
        }
    }

    @Test
    fun nextSendsContentChecksWithDefaultsDisabled() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(JsonPrimitive(true), body["contentCheckOk"])
                    assertEquals(JsonPrimitive(true), body["racyCheckOk"])
                    respond("{}")
                }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { encodeDefaults = false }) } }
            val innerTube = InnerTube(client)
            try {
                innerTube.next(YouTubeClient.WEB, videoId = "video")
                assertEquals(1, engine.requestHistory.size)
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun pageTimestampIsReadBeforeAnyRemoteRefresh() =
        runBlocking {
            val fields = configFields("visitorData")
            val html = fields.padEnd(32 * 1024) + "{\"STS\":20668}".padEnd(32 * 1024)
            val engine =
                MockEngine { request ->
                    assertEquals("/watch", request.url.encodedPath)
                    respond(ByteReadChannel(html))
                }
            val client = HttpClient(engine)
            val innerTube = InnerTube(client)
            try {
                val store = RemotePlayerConfigStore(client, repository())
                val config = YtConfigParserImpl(client, innerTube, store).fetchConfig("video")
                assertEquals(20668, config.signatureTimestamp)
                assertEquals(1, engine.requestHistory.size)
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun missingPageTimestampUsesRemoteLookupAfterEof() =
        runBlocking {
            val page = ByteReadChannel(configFields("visitorData").padEnd(32 * 1024))
            val paths = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    paths += request.url.encodedPath
                    if (request.url.encodedPath == "/watch") {
                        respond(page)
                    } else {
                        assertTrue(page.isClosedForRead)
                        assertEquals("/MetrolistGroup/faraday/main/player_configs.json", request.url.encodedPath)
                        respond(configTable)
                    }
                }
            val client = HttpClient(engine)
            val innerTube = InnerTube(client)
            try {
                val store = RemotePlayerConfigStore(client, repository())
                val config = YtConfigParserImpl(client, innerTube, store).fetchConfig("video")
                assertEquals(20668, config.signatureTimestamp)
                assertEquals(listOf("/watch", "/MetrolistGroup/faraday/main/player_configs.json"), paths)
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun cachedTimestampCompletesPageWithoutRefresh() =
        runBlocking {
            val page = ByteChannel(autoFlush = true)
            val producer = launch { page.writeStringUtf8(configFields("visitorData").padEnd(32 * 1024)) }
            val engine =
                MockEngine { request ->
                    assertEquals("/watch", request.url.encodedPath)
                    respond(page)
                }
            val client = HttpClient(engine)
            val innerTube = InnerTube(client)
            try {
                val repository = repository().apply { cachedJson = configTable }
                val store = RemotePlayerConfigStore(client, repository) { 0L }
                val config = withTimeout(2_000) { YtConfigParserImpl(client, innerTube, store).fetchConfig("video") }
                assertEquals(20668, config.signatureTimestamp)
                assertEquals(1, engine.requestHistory.size)
                assertTrue(page.isClosedForRead)
            } finally {
                producer.cancelAndJoin()
                page.cancel(null)
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun cacheOnlyTimestampLookupNeverFetches() =
        runBlocking {
            val engine = MockEngine { error("Cache-only lookup must not fetch") }
            val client = HttpClient(engine)
            try {
                val repository = repository()
                val store = RemotePlayerConfigStore(client, repository) { 24 * 60 * 60 * 1000L }
                assertNull(store.getCachedSignatureTimestamp(playerUrl))
                repository.cachedJson = configTable
                assertEquals(20668, store.getCachedSignatureTimestamp(playerUrl))
                assertNull(store.getCachedSignatureTimestamp("https://www.youtube.com/s/player/deadbeef/base.js"))
                val disabledStore = RemotePlayerConfigStore(client, PlayerConfigRepository.disabled())
                assertNull(disabledStore.getCachedSignatureTimestamp(playerUrl))
                assertTrue(engine.requestHistory.isEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun cacheOnlyLegacyTimestampHonorsExpiryWithoutFetching() =
        runBlocking {
            var now = 0L
            val engine = MockEngine { respond("""{"playerHash":"12345678","sts":20668}""") }
            val client = HttpClient(engine)
            try {
                val repository =
                    repository("https://github.com/MetrolistGroup/faraday/releases/download/player-{playerHash}/{playerHash}.json")
                val store = RemotePlayerConfigStore(client, repository) { now }
                assertNull(store.getCachedSignatureTimestamp(playerUrl))
                assertTrue(engine.requestHistory.isEmpty())
                assertEquals(20668, store.getSignatureTimestamp(playerUrl))
                assertEquals(20668, store.getCachedSignatureTimestamp(playerUrl))
                now = 6 * 60 * 60 * 1000L
                assertNull(store.getCachedSignatureTimestamp(playerUrl))
                assertEquals(1, engine.requestHistory.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun uppercaseVisitorCompletesWatchAndEmbedBeforeEof() =
        runBlocking {
            for (embedded in listOf(false, true)) {
                val page = ByteChannel(autoFlush = true)
                val fields = configFields("VISITOR_DATA") + "{\"STS\":20668,\"encryptedHostFlags\":\"flags\"}"
                val producer = launch { page.writeStringUtf8(fields.padEnd(32 * 1024)) }
                val engine = MockEngine { respond(page) }
                val client = HttpClient(engine)
                val innerTube = InnerTube(client)
                try {
                    val parser = YtConfigParserImpl(client, innerTube)
                    val config =
                        withTimeout(2_000) {
                            if (embedded) parser.fetchEmbeddedConfig("video") else parser.fetchConfig("video")
                        }
                    assertEquals("visitor", config.visitorData)
                    assertEquals(20668, config.signatureTimestamp)
                    assertEquals(1, engine.requestHistory.size)
                    assertTrue(page.isClosedForRead)
                } finally {
                    producer.cancelAndJoin()
                    page.cancel(null)
                    innerTube.close()
                    client.close()
                }
            }
        }

    @Test
    fun prefixReadAcceptsOversizedDeclaredLengthsWithoutOverflow() =
        runBlocking {
            for (length in listOf(65537L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
                val prefix = "x".repeat(32 * 1024)
                val client =
                    HttpClient(
                        MockEngine {
                            respond(ByteReadChannel(prefix), headers = headersOf(HttpHeaders.ContentLength, length.toString()))
                        },
                    )
                try {
                    client.prepareGet("https://example.test").execute { response ->
                        var checked = false
                        assertEquals(
                            prefix,
                            response.bodyAsTextLimited(64 * 1024) {
                                checked = true
                                true
                            },
                        )
                        assertTrue(checked)
                    }
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun prefixReadStillEnforcesConsumedByteLimit() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            ByteReadChannel("x".repeat(64 * 1024 + 1)),
                            headers = headersOf(HttpHeaders.ContentLength, Long.MAX_VALUE.toString()),
                        )
                    },
                )
            try {
                var checked = false
                assertFailsWith<ResponseTooLargeException> {
                    client.prepareGet("https://example.test").execute { response ->
                        response.bodyAsTextLimited(64 * 1024) {
                            checked = true
                            false
                        }
                    }
                }
                assertTrue(checked)
            } finally {
                client.close()
            }
        }

    @Test
    fun fullBodyStillRejectsOversizedDeclaredLengthBeforeReading() =
        runBlocking {
            val body = ByteChannel()
            val client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentLength, Long.MAX_VALUE.toString())) })
            try {
                withTimeout(2_000) {
                    assertFailsWith<ResponseTooLargeException> {
                        client.prepareGet("https://example.test").execute { it.bodyAsTextLimited(64 * 1024) }
                    }
                }
                assertTrue(body.isClosedForRead)
            } finally {
                body.cancel(null)
                client.close()
            }
        }

    private suspend fun playerText(innerTube: InnerTube): String =
        innerTube.playerTextWithSessionBound(YouTubeClient.VISIONOS, "video", null, null, innerTube.sessionSnapshot(), null, 4096)

    private fun configFields(visitorKey: String): String =
        """{"jsUrl":"/s/player/12345678/base.js","$visitorKey":"visitor","INNERTUBE_CLIENT_VERSION":"2.0"}"""

    private fun repository(
        source: String = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json",
    ): PlayerConfigRepository =
        object : PlayerConfigRepository {
            override val enabled = true
            override val defaultSourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
            override val sourceUrl = source
            override var cachedJson = ""
            override var cachedAtMs = 0L
            override var cachedSourceUrl = sourceUrl
            override var cachedEtag = ""
        }

    private val playerUrl = "https://www.youtube.com/s/player/12345678/base.js"
    private val configTable = """{"schemaVersion":1,"players":{"12345678":{"sig":"A(1,2,INPUT)","nClass":"B","sts":20668}}}"""
}
