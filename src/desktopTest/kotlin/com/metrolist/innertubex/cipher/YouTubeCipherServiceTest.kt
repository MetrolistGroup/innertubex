package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class YouTubeCipherServiceTest {
    @Test
    fun partialRemoteSuccessStillReusesCachedPreprocessedPlayer() =
        runBlocking {
            val code =
                """
                (function(g){
                  g.X=class {
                    constructor(url){this.n=url.split('=')[1]}
                    get(){return this.n === 'value' ? this.n : 'valid_n_value'}
                  };
                  function S(a,b,input){return 'sig_'+input}
                })(_yt_player);
                """.trimIndent()
            val repository =
                object : PlayerConfigRepository {
                    override val enabled = true
                    override val defaultSourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
                    override val sourceUrl = defaultSourceUrl
                    override var cachedJson = ""
                    override var cachedAtMs = 0L
                    override var cachedSourceUrl = ""
                    override var cachedEtag = ""
                }
            var playerDownloads = 0
            val http =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.host == "www.youtube.com" -> {
                                playerDownloads++
                                respond(code)
                            }

                            request.url.encodedPath.endsWith("/player_configs.json") -> {
                                respond("""{"schemaVersion":1,"players":{"abcdef12":{"sig":"S(1,2,INPUT)","nClass":"X","sts":1}}}""")
                            }

                            else -> {
                                respond("unavailable", HttpStatusCode.NotFound)
                            }
                        }
                    },
                )
            val service = YouTubeCipherService(http, RemotePlayerConfigStore(http, repository))
            var reads = 0
            service.setPreprocessedPlayerCache(
                read = {
                    reads++
                    "_result.n = function(c) { return c + '_done'; };"
                },
                write = { _, _ -> },
            )
            try {
                val format =
                    PlayerResponse.StreamingData.Format(
                        itag = 140,
                        signatureCipher = "s=abc&sp=sig&url=https%3A%2F%2Fexample.googlevideo.com%2Fvideoplayback%3Fn%3Dvalue",
                        mimeType = "audio/mp4",
                    )
                val solved = service.processFormats("https://www.youtube.com/s/player/abcdef12/base.js", listOf(format))
                assertEquals(1, solved.size)
                val url = io.ktor.http.Url(solved.single().url!!)
                assertEquals("sig_abc", url.parameters["sig"])
                assertEquals("value_done", url.parameters["n"])
                assertEquals(1, playerDownloads)
                assertEquals(1, reads)
            } finally {
                service.dispose()
                http.close()
            }
        }

    @Test
    fun cancelledDownloadReleasesWaitersAndReservationUnderLockContention() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            var attempts = 0
            val client =
                HttpClient(
                    MockEngine {
                        if (++attempts == 1) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                        respond("retry code")
                    },
                )
            val service = YouTubeCipherService(client)
            val owner = async { service.playerCode(PLAYER_URL) }
            try {
                started.await()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { service.playerCode(PLAYER_URL) }
                val mutex = privateField<Mutex>(service, "cacheMutex")
                mutex.lock()
                try {
                    owner.cancel()
                    withTimeout(5_000) {
                        assertFailsWith<CancellationException> { waiter.await() }
                    }
                } finally {
                    mutex.unlock()
                }
                withTimeout(5_000) {
                    owner.join()
                    assertEquals("retry code", service.playerCode(PLAYER_URL))
                }
                assertEquals(2, attempts)
            } finally {
                owner.cancelAndJoin()
                service.dispose()
                client.close()
            }
        }

    @Test
    fun partialRemoteSolveReusesPreprocessedAndCompiledPlayer() =
        runBlocking {
            val bootstrap = EjsChallengeSolver(QuickJsEngine(), InnerTubeLogger.NONE)
            val preprocessed =
                try {
                    assertNotNull(bootstrap.solve(PLAYER_URL, PLAYER_CODE, listOf("sig" to listOf("warm"))).preprocessedPlayer)
                } finally {
                    bootstrap.dispose()
                }
            val client = HttpClient(MockEngine { respond(REMOTE_PLAYER_CODE) })
            val store = RemotePlayerConfigStore(client, configRepository())
            val service = YouTubeCipherService(client, store)
            var reads = 0
            try {
                service.setPreprocessedPlayerCache({
                    reads++
                    preprocessed
                }, { _, _ -> })
                repeat(3) { i ->
                    val formats = listOf(format("$MEDIA_URL?n=remote"), format("$MEDIA_URL?n=local$i"))
                    val result = service.processFormats(PLAYER_URL, formats)
                    assertEquals("$MEDIA_URL?n=remote_done", result[0].url)
                    assertEquals("$MEDIA_URL?n=local${i}_done", result[1].url)
                }
                assertEquals(1, reads)
                val engine = privateField<QuickJsEngine>(service, "engine")
                assertEquals("1", engine.evaluate("__itxPlayers.size", maxResultLength = 8))
                assertEquals(1, (client.engine as MockEngine).requestHistory.count { it.url.encodedPath.endsWith(".js") })
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun rawPlayerCacheKeepsOnlyTheCurrentPlayer() =
        runBlocking {
            var downloads = 0
            val httpClient =
                HttpClient(
                    MockEngine {
                        downloads++
                        respond("player code", headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
                    },
                )
            val service = YouTubeCipherService(httpClient)
            try {
                val first = "https://www.youtube.com/s/player/12345678/player.js"
                val second = "https://www.youtube.com/s/player/87654321/player.js"
                service.initialize()
                assertEquals("player code", service.playerCode(first))
                assertEquals("player code", service.playerCode(first))
                assertEquals(1, downloads)
                service.playerCode(second)
                service.playerCode(first)
                assertEquals(3, downloads)
            } finally {
                service.dispose()
                httpClient.close()
            }
        }

    @Test
    fun directFormatsDoNotWaitForPlayerPreload() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val service =
                YouTubeCipherService(
                    HttpClient(
                        MockEngine {
                            requestStarted.complete(Unit)
                            awaitCancellation()
                        },
                    ),
                )
            val preload =
                async {
                    service.preloadPlayerCode(
                        "https://www.youtube.com/s/player/12345678/player_ias.vflset/en_GB/base.js",
                    )
                }

            try {
                requestStarted.await()
                val format =
                    PlayerResponse.StreamingData.Format(
                        itag = 140,
                        url = "https://example.googlevideo.com/videoplayback?expire=1",
                        mimeType = "audio/mp4",
                    )

                val result = withTimeout(1_000) { service.processFormats("unused", listOf(format)) }

                assertEquals(listOf(format), result)
                assertFalse(preload.isCompleted)
            } finally {
                preload.cancelAndJoin()
            }
        }

    @Test
    fun processFormatsRejectsNFormatsWhenPlayerScriptUnavailable() =
        runBlocking {
            val httpClient =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "not found",
                            status = HttpStatusCode.NotFound,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val service = YouTubeCipherService(httpClient)
            val format =
                PlayerResponse.StreamingData.Format(
                    itag = 140,
                    url = "https://rr1---sn-test.c.youtube.com/videoplayback?expire=1&n=Qabc12XYZ&signature=abc",
                    mimeType = "audio/mp4",
                )

            val result =
                service.processFormats(
                    playerUrl = "https://www.youtube.com/s/player/66a6ea83/player_ias.vflset/en_GB/base.js",
                    formats = listOf(format),
                )

            assertEquals(1, result.size)
            assertNull(result.first().url)
        }

    @Test
    fun processFormatsDoesNotFetchPlayerScriptsFromUntrustedHosts() =
        runBlocking {
            val engine = MockEngine { error("Untrusted player URL must not be requested") }
            val service = YouTubeCipherService(HttpClient(engine))
            val format =
                PlayerResponse.StreamingData.Format(
                    itag = 140,
                    url = "https://example.googlevideo.com/videoplayback?n=challenge",
                    mimeType = "audio/mp4",
                )

            val result = service.processFormats("https://example.com/s/player/hash/base.js", listOf(format))

            assertNull(result.single().url)
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun cipherQueryValuesAreEncodedWithoutMovingFragments() {
        val service = YouTubeCipherService(HttpClient(MockEngine { error("No request expected") }))

        assertEquals(
            "https://example.googlevideo.com/videoplayback?expire=1&sig=a%26b%2Bc%23d#fragment",
            service.appendQueryParameter(
                "https://example.googlevideo.com/videoplayback?expire=1#fragment",
                "sig",
                "a&b+c#d",
            ),
        )
        assertNull(service.appendQueryParameter("https://example.test/video", "sig&other", "value"))
        assertNull(service.appendQueryParameter("https://example.test/video", "sig", ""))
        assertNull(service.appendQueryParameter("https://example.test/videoplayback", "sig", "value"))

        val url = "https://example.googlevideo.com/videoplayback?n=old&x=1#fragment"
        val match = Regex("[&?]n=([^&#]+)").find(url)!!
        assertEquals(
            "https://example.googlevideo.com/videoplayback?n=a%26b%2Bc&x=1#fragment",
            service.replaceQueryParameter(url, match, "a&b+c"),
        )

        val fragmentUrl = "https://example.googlevideo.com/videoplayback?x=1#fragment&n=old"
        val fragmentMatch = Regex("[&?]n=([^&#]+)").find(fragmentUrl)!!
        assertNull(service.replaceQueryParameter(fragmentUrl, fragmentMatch, "new"))
    }

    private fun format(url: String) = PlayerResponse.StreamingData.Format(itag = 140, url = url, mimeType = "audio/mp4")

    private fun configRepository(): PlayerConfigRepository =
        object : PlayerConfigRepository {
            override val enabled = true
            override val sourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
            override val defaultSourceUrl = sourceUrl
            override var cachedJson = """{"schemaVersion":1,"players":{"12345678":{"sig":"S(1,2,INPUT)","nClass":"X","sts":20613}}}"""
            override var cachedAtMs = Clock.System.now().toEpochMilliseconds()
            override var cachedSourceUrl = sourceUrl
            override var cachedEtag = ""
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(
        instance: Any,
        name: String,
    ): T =
        instance.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(instance) as T

    private companion object {
        const val PLAYER_URL = "https://www.youtube.com/s/player/12345678/player.js"
        const val MEDIA_URL = "https://example.googlevideo.com/videoplayback"
        val REMOTE_PLAYER_CODE =
            """
            (function(g){
              function S(a,b,input){return input.split("").reverse().join("");}
              g.X=class{constructor(input){this.input=input.split("n=")[1];}get(){
                return this.input.indexOf("local") === 0 ? this.input : this.input + "_done";
              }};
            })(_yt_player);
            """.trimIndent()
        val PLAYER_CODE =
            """
            (function() {
              function PlayerUrl(url, key, signature) {
                this.values = {s: signature || null, n: null};
              }
              PlayerUrl.prototype.set = function(key, value) { this.values[key] = value; };
              PlayerUrl.prototype.get = function(key) { return this.values[key]; };
              PlayerUrl.prototype.clone = function() { return this; };
              PlayerUrl.prototype.apply = function() {
                if (this.values.s) this.values.s = this.values.s.split("").reverse().join("");
                if (this.values.n) this.values.n += "_done";
              };
              function transform(url, key, signature) {
                var value = new PlayerUrl(url, key, signature);
                value.set("alr", "yes");
                return value;
              }
            }).call(this);
            """.trimIndent()
    }
}
