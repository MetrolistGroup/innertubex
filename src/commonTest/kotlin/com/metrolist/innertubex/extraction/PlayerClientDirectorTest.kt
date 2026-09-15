package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlayerClientDirectorTest {
    @Test
    fun premiumHintReachesSelectionAndAllowsUntokenizedPlayback() =
        runBlocking {
            val client = client { PLAYER_RESPONSE }
            val innerTube = InnerTube(client, retryDelay = {})
            val manifest = checkNotNull(PlaybackClientCatalog.findManifest("WEB_REMIX"))
            var selectionRequest: ClientSelectionRequest? = null
            val director =
                PlayerClientDirector(
                    innerTube,
                    object : ClientFallbackStrategy {
                        override fun resolveClients(hints: ContentHints) = listOf(manifest.client)

                        override fun selectClients(request: ClientSelectionRequest): ClientSelectionResult {
                            selectionRequest = request
                            return ClientSelectionResult(listOf(SelectedClient(manifest.client, manifest)))
                        }
                    },
                    NoTokenProvider,
                )

            val result =
                director.fetchPlayerResponses(
                    "video",
                    PlayerConfig("player.js", null, null, null),
                    ContentHints(playbackClientOverrideId = "WEB_REMIX").withPremium(),
                )

            assertTrue(selectionRequest?.premium == true)
            assertEquals("WEB_REMIX", result.playableResponses.single().clientName)
            client.close()
        }

    @Test
    fun requiredPoTokenIsBoundToTheCorrectVisitorAndBinding() =
        runBlocking {
            val client = client { TOKEN_PLAYER_RESPONSE }
            val innerTube = InnerTube(client, retryDelay = {}).also { it.visitorData = "visitor" }
            var receivedVisitor = ""
            val provider =
                object : TokenProvider {
                    override val capabilities =
                        TokenProviderCapabilities(
                            setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION),
                            usesWebView = true,
                        )

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ): PoTokenResult {
                        receivedVisitor = visitorData
                        return PoTokenResult("player-token", "stream-token", visitorData)
                    }
                }
            val manifest = checkNotNull(PlaybackClientCatalog.findManifest("WEB_SABR"))
            val director = PlayerClientDirector(innerTube, fixed(manifest), provider)
            val result =
                director.fetchPlayerResponses(
                    "video",
                    PlayerConfig("https://www.youtube.com/s/player/x/base.js", null, null, null),
                    ContentHints(playbackClientOverrideId = "WEB_SABR"),
                )

            assertEquals("visitor", receivedVisitor)
            assertEquals("stream-token", result.playableResponses.single().streamingDataPoToken)
            client.close()
        }

    @Test
    fun tokenVisitorChangeCancelsBeforeSendingOldCredentials() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(PLAYER_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )
            val innerTube = InnerTube(client, retryDelay = {}).also { it.visitorData = "old-visitor" }
            val manifest = checkNotNull(PlaybackClientCatalog.findManifest("WEB_REMIX"))
            val provider =
                object : TokenProvider {
                    override val capabilities = TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ): PoTokenResult {
                        innerTube.visitorData = "new-visitor"
                        return PoTokenResult("player", "stream", visitorData)
                    }
                }
            val director = PlayerClientDirector(innerTube, fixed(manifest), provider)
            assertFailsWith<CancellationException> {
                director.fetchPlayerResponses(
                    "video",
                    PlayerConfig("player.js", null, null, null),
                    ContentHints(playbackClientOverrideId = "WEB_REMIX"),
                )
            }
            assertEquals(0, requests)
            client.close()
        }

    @Test
    fun unavailablePoTokenIsRequestedOnlyOncePerBatch() =
        runBlocking {
            val client = client { PLAYER_RESPONSE }
            val innerTube =
                InnerTube(client, retryDelay = {}).also {
                    it.visitorData = "visitor"
                    it.cookie = "SAPISID=test"
                }
            var tokenRequests = 0
            val provider =
                object : TokenProvider {
                    override val capabilities =
                        TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ): PoTokenResult? {
                        tokenRequests++
                        return null
                    }
                }
            val manifests =
                listOf("WEB_REMIX", "WEB_CREATOR").map { id ->
                    checkNotNull(PlaybackClientCatalog.findManifest(id))
                }
            val director =
                PlayerClientDirector(
                    innerTube,
                    object : ClientFallbackStrategy {
                        override fun resolveClients(hints: ContentHints) = manifests.map { it.client }

                        override fun selectClients(request: ClientSelectionRequest) =
                            ClientSelectionResult(manifests.map { SelectedClient(it.client, it) })
                    },
                    provider,
                )

            director.fetchPlayerResponses("video", PlayerConfig("player.js", null, null, null), ContentHints())

            assertEquals(1, tokenRequests)
            client.close()
        }

    @Test
    fun stalledClientTimesOutAndFallsThrough() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        if (request.headers["X-YouTube-Client-Name"] == YouTubeClient.VISIONOS.clientId) delay(100)
                        respond(PLAYER_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val director =
                PlayerClientDirector(
                    InnerTube(client, retryDelay = {}),
                    object : ClientFallbackStrategy {
                        override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.VISIONOS, YouTubeClient.ANDROID_VR_1_65_10)
                    },
                    NoTokenProvider,
                    playerRequestTimeoutMs = 25,
                )
            val batch = director.fetchPlayerResponses("video", PlayerConfig("player.js", null, null, null), ContentHints())
            assertEquals(2, requests)
            assertEquals(YouTubeClient.ANDROID_VR_1_65_10.clientName, batch.playableResponses.single().clientName)
            client.close()
        }

    @Test
    fun playerResponseBodyFormatCountIsBounded() =
        runBlocking {
            val formats =
                (1..2050).joinToString(",") {
                    "{\"itag\":$it,\"url\":\"https://r.googlevideo.com/videoplayback\",\"mimeType\":\"audio/mp4\"}"
                }
            val client = client { "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"adaptiveFormats\":[$formats]}}" }
            val director =
                PlayerClientDirector(
                    InnerTube(client, retryDelay = {}),
                    object : ClientFallbackStrategy {
                        override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.VISIONOS)
                    },
                    NoTokenProvider,
                )
            assertTrue(
                director
                    .fetchPlayerResponses(
                        "video",
                        PlayerConfig("player.js", null, null, null),
                        ContentHints(),
                    ).playableResponses
                    .isEmpty(),
            )
            client.close()
        }

    @Test
    fun playerResponseBodyByteLimitRejectsOversizedPayload() =
        runBlocking {
            val client = client { "x".repeat(4 * 1024 * 1024 + 1) }
            val director =
                PlayerClientDirector(
                    InnerTube(client, retryDelay = {}),
                    object : ClientFallbackStrategy {
                        override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.VISIONOS)
                    },
                    NoTokenProvider,
                )

            val batch = director.fetchPlayerResponses("video", PlayerConfig("player.js", null, null, null), ContentHints())

            assertTrue(batch.playableResponses.isEmpty())
            assertTrue(batch.requestFailures.isNotEmpty())
            client.close()
        }

    @Test
    fun hlsResponseIsReturnedForLiveContent() =
        runBlocking {
            val client =
                client {
                    "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"hlsManifestUrl\":\"https://video.google.com/live.m3u8\"}}"
                }
            val director =
                PlayerClientDirector(
                    InnerTube(client, retryDelay = {}),
                    object : ClientFallbackStrategy {
                        override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.VISIONOS)
                    },
                    NoTokenProvider,
                )

            val batch = director.fetchPlayerResponses("video", PlayerConfig("player.js", null, null, null), ContentHints(isLive = true))

            assertEquals(1, batch.playableResponses.size)
            assertEquals(
                "https://video.google.com/live.m3u8",
                batch.playableResponses
                    .single()
                    .response.streamingData
                    ?.hlsManifestUrl,
            )
            client.close()
        }

    private fun fixed(manifest: com.metrolist.innertubex.extraction.strategy.PlaybackClientManifest) =
        object : ClientFallbackStrategy {
            override fun resolveClients(hints: ContentHints) = listOf(manifest.client)

            override fun selectClients(request: ClientSelectionRequest) =
                ClientSelectionResult(listOf(SelectedClient(manifest.client, manifest)))
        }

    private fun client(body: () -> String) =
        HttpClient(
            MockEngine {
                respond(body(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private object NoTokenProvider : TokenProvider {
        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ) = null
    }

    private companion object {
        val PLAYER_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback","mimeType":"audio/webm","bitrate":128000}]}}
            """.trimIndent()
        val TOKEN_PLAYER_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"serverAbrStreamingUrl":"https://r.googlevideo.com/videoplayback","adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","bitrate":128000}]},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}}
            """.trimIndent()
    }
}
