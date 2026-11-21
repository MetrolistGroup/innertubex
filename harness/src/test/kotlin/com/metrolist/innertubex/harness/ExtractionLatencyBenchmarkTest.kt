package com.metrolist.innertubex.harness

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractionCipherService
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Controlled 150 ms stages keep network variation out of cold-start comparisons. */
class ExtractionLatencyBenchmarkTest {
    @Test
    fun nativeVideoSkipsWatchConfig() =
        runBlocking {
            var configs = 0
            val http = http(SABR_RESPONSE)
            try {
                val tube = InnerTube(http).also { it.visitorData = "fixture-visitor" }
                val extractor =
                    InnerTubeExtractor(
                        parser {
                            configs++
                            delay(STAGE_MS)
                            config()
                        },
                        YouTubeCipherService(http),
                        tube,
                    )
                val start = TimeSource.Monotonic.markNow()
                val stream = assertNotNull(extractor.extract("video", ContentHints(wantVideo = true)))
                println("latency native-video ms=${start.elapsedNow().inWholeMilliseconds}")
                assertEquals("VISIONOS_SABR__nopo", stream.profileId)
                assertEquals(0, configs)
            } finally {
                http.close()
            }
        }

    @Test
    fun prewarmCompilesPublicAndAuthenticatedPlayersAsConfigsArrive() =
        runBlocking {
            for (failPublic in listOf(false, true)) {
                val publicReady = CompletableDeferred<Unit>()
                val warmed = mutableSetOf<String>()
                val http = http(DIRECT_RESPONSE)
                try {
                    val tube = InnerTube(http).also { it.cookie = "SAPISID=fixture" }
                    val cipher =
                        object : ExtractionCipherService {
                            override suspend fun initialize() {}

                            override suspend fun prewarmEjs() {}

                            override suspend fun preloadPlayerCode(playerUrl: String) {
                                warmed += playerUrl
                                if (playerUrl == PUBLIC_PLAYER) {
                                    publicReady.complete(Unit)
                                    if (failPublic) error("Fixture public preload failure")
                                }
                            }

                            override suspend fun processFormats(
                                playerUrl: String,
                                formats: List<PlayerResponse.StreamingData.Format>,
                            ) = formats
                        }
                    val extractor =
                        InnerTubeExtractor(
                            parser { authenticated ->
                                if (authenticated) withTimeout(2_000) { publicReady.await() }
                                config(if (authenticated) AUTH_PLAYER else PUBLIC_PLAYER)
                            },
                            cipher,
                            tube,
                        )
                    extractor.prewarm()
                    assertEquals(setOf(PUBLIC_PLAYER, AUTH_PLAYER), warmed)
                } finally {
                    http.close()
                }
            }
        }

    @Test
    fun prewarmLeavesPublicCipherWarm() =
        runBlocking {
            val http = http(DIRECT_RESPONSE)
            val cipher = YouTubeCipherService(http)
            try {
                val tube = InnerTube(http).also { it.cookie = "SAPISID=fixture" }
                val extractor =
                    InnerTubeExtractor(
                        parser { authenticated -> config(if (authenticated) AUTH_PLAYER else PUBLIC_PLAYER) },
                        cipher,
                        tube,
                    )
                extractor.prewarm()
                val before = (http.engine as MockEngine).requestHistory.size
                val start = TimeSource.Monotonic.markNow()
                assertNotNull(cipher.processNParameter(PUBLIC_PLAYER, "$MEDIA?n=abc"))
                println("latency prewarmed-public ms=${start.elapsedNow().inWholeMilliseconds}")
                assertEquals(before, (http.engine as MockEngine).requestHistory.size)
            } finally {
                cipher.dispose()
                http.close()
            }
        }

    @Test
    fun coldCipherPreparationOverlapsPlayerRequest() =
        runBlocking {
            val http = http(DIRECT_RESPONSE.replace("videoplayback", "videoplayback?n=abc"), requireCipherOverlap = true)
            try {
                val tube = InnerTube(http).also { it.visitorData = "fixture-visitor" }
                val extractor =
                    InnerTubeExtractor(
                        parser { config() },
                        YouTubeCipherService(http),
                        tube,
                        fallbackStrategy = fixed("WEB_REMIX"),
                        tokenProvider = provider(0),
                    )
                val start = TimeSource.Monotonic.markNow()
                assertNotNull(extractor.extract("video", ContentHints(playbackClientOverrideId = "WEB_REMIX")))
                val elapsed = start.elapsedNow().inWholeMilliseconds
                println("latency cold-cipher ms=$elapsed")
                assertEquals(1, (http.engine as MockEngine).requestHistory.count { it.url.encodedPath.endsWith(".js") })
            } finally {
                http.close()
            }
        }

    @Test
    fun forcedGvsMintOverlapsPlayerRequestAndKeepsTokenRequired() =
        runBlocking {
            val http = http(DIRECT_RESPONSE)
            try {
                val tube = InnerTube(http).also { it.visitorData = "fixture-visitor" }
                val extractor =
                    InnerTubeExtractor(
                        parser { config() },
                        YouTubeCipherService(http),
                        tube,
                        fallbackStrategy = fixed("WEB_REMIX"),
                        tokenProvider = provider(STAGE_MS),
                    )
                val start = TimeSource.Monotonic.markNow()
                val stream = assertNotNull(extractor.extract("video", ContentHints(playbackClientOverrideId = "WEB_REMIX")))
                val elapsed = start.elapsedNow().inWholeMilliseconds
                println("latency forced-gvs ms=$elapsed")
                assertTrue(stream.profileId.endsWith("_po"))
            } finally {
                http.close()
            }
        }

    private fun parser(fetch: suspend (Boolean) -> PlayerConfig) =
        object : YtConfigParser {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) = fetch(useLoginCookies)
        }

    private fun config(url: String = PUBLIC_PLAYER) = PlayerConfig(url, 123, null, null)

    private fun fixed(id: String) =
        object : ClientFallbackStrategy {
            val manifest = checkNotNull(PlaybackClientCatalog.findManifest(id))

            override fun resolveClients(hints: ContentHints) = listOf(manifest.client)

            override fun selectClients(request: ClientSelectionRequest) =
                ClientSelectionResult(listOf(SelectedClient(manifest.client, manifest)))
        }

    private fun provider(latency: Long) =
        object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD))

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult {
                delay(latency)
                return PoTokenResult("", "fixture-gvs", visitorData)
            }
        }

    private fun http(
        response: String,
        requireCipherOverlap: Boolean = false,
    ): HttpClient {
        val playerStarted = CompletableDeferred<Unit>()
        val scriptStarted = CompletableDeferred<Unit>()
        return HttpClient(
            MockEngine { request ->
                val script = request.url.encodedPath.endsWith(".js")
                if (requireCipherOverlap) {
                    (if (script) scriptStarted else playerStarted).complete(Unit)
                    withTimeout(2_000) { (if (script) playerStarted else scriptStarted).await() }
                }
                delay(STAGE_MS)
                respond(
                    if (script) PLAYER_CODE else response,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private companion object {
        const val STAGE_MS = 150L
        const val PUBLIC_PLAYER = "https://www.youtube.com/s/player/12345678/player.js"
        const val AUTH_PLAYER = "https://www.youtube.com/s/player/abcdef12/player.js"
        const val MEDIA = "https://example.googlevideo.com/videoplayback"
        const val DIRECT_RESPONSE =
            """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://example.googlevideo.com/videoplayback","mimeType":"audio/webm","bitrate":128000}]}}"""
        const val SABR_RESPONSE =
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","lengthSeconds":"60"},"streamingData":{"serverAbrStreamingUrl":"https://example.googlevideo.com/videoplayback?sabr=1","adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","bitrate":128000,"contentLength":1234,"lastModified":"100","approxDurationMs":"60000"},{"itag":160,"mimeType":"video/mp4","bitrate":100000,"width":256,"height":144,"contentLength":2345,"lastModified":"200"}]},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}}"""
        const val PLAYER_CODE =
            """
            var g={};
            var S=function(a,b,input){return input.split('').reverse().join('');};
            g.X=function(raw,enabled){this.raw=raw;this.V={};};
            g.X.prototype.set=function(k,v){this.V[k]=v;};
            g.X.prototype.get=function(k){applyN(7,19,this);return this.V[k];};
            var applyN=function(a,b,o){var key=a^b;if(key==20){o.set('n',o.V.n + '_done');}};
            var decode=function(a,b,x){return x;};
            var landmark=function(M,k,X){M=new g.X(M,!0);M.set("alr","yes");X&&(X=S(1,2,decode(3,673,X)));};
            """
    }
}
