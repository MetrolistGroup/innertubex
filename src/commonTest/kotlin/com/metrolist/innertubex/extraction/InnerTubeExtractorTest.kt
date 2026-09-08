package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InnerTubeExtractorTest {
    @Test
    fun directAudioPathSelectsFormatWithoutRecursiveSelectorWrapper() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)
            val extractor = extractor(client)
            val stream =
                extractor.extract(
                    "video",
                    ContentHints(isExplicit = true),
                    audioQuality = AudioQuality.AUTO,
                    clientPlaybackNonce = "abcdefghijklmnop",
                )

            assertNotNull(stream)
            assertEquals(251, stream.itag)
            assertEquals("audio/webm", stream.mimeType)
            assertTrue(stream.audioUrl.contains("cpn=abcdefghijklmnop"))
            assertEquals("Track title", stream.mediaMetadata?.title)
            assertEquals(60L, stream.mediaMetadata?.durationSeconds)
            assertEquals(-8.5, stream.perceptualLoudnessDb)
            assertTrue(!stream.toString().contains("Track title"))
            client.close()
        }

    @Test
    fun transformedNParameterRemainsUsable() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE.replace("expire=9999999999", "expire=9999999999&n=source"))
            val innerTube = InnerTube(client, retryDelay = {})
            val stream =
                makeExtractor(
                    client,
                    innerTube,
                    CountingParser(),
                    cipherService = NTransformCipherService,
                ).extract("video", ContentHints(isExplicit = true))

            assertNotNull(stream)
            assertTrue(stream.audioUrl.contains("n=solved"))
            client.close()
        }

    @Test
    fun maxVideoHeightIsPassedToDirectSelection() =
        runBlocking {
            val client = jsonClient(VIDEO_RESPONSE)
            val extractor = extractor(client)
            val stream = extractor.extract("video", ContentHints(isExplicit = true, wantVideo = true, maxVideoHeight = 720))

            assertNotNull(stream)
            assertEquals(720, stream.videoHeight)
            assertEquals(136, stream.videoItag)
            client.close()
        }

    @Test
    fun defaultVideoHeightAllows2160p() =
        runBlocking {
            val client = jsonClient(VIDEO_RESPONSE)
            val stream = extractor(client).extract("video", ContentHints(isExplicit = true, wantVideo = true))

            assertNotNull(stream)
            assertEquals(2160, stream.videoHeight)
            assertEquals(313, stream.videoItag)
            client.close()
        }

    @Test
    fun adaptiveVideoIsPreferredOverHigherBitrateProgressiveVideo() =
        runBlocking {
            val client = jsonClient(PROGRESSIVE_AND_ADAPTIVE_VIDEO_RESPONSE)
            val stream = extractor(client).extract("video", ContentHints(isExplicit = true, wantVideo = true))

            assertNotNull(stream)
            assertEquals(136, stream.videoItag)
            client.close()
        }

    @Test
    fun progressiveVideoStillProvidesResolutionsMissingFromAdaptiveVideo() =
        runBlocking {
            val response =
                PROGRESSIVE_AND_ADAPTIVE_VIDEO_RESPONSE.replace(
                    "\"bitrate\":5000000,\"width\":1280,\"height\":720",
                    "\"bitrate\":5000000,\"width\":1920,\"height\":1080",
                )
            val client = jsonClient(response)
            val stream = extractor(client).extract("video", ContentHints(isExplicit = true, wantVideo = true, maxVideoHeight = 1080))
            assertNotNull(stream)
            assertEquals(22, stream.videoItag)
            client.close()
        }

    @Test
    fun adaptivePreferenceDoesNotExceedTheRequestedResolution() =
        runBlocking {
            val response =
                PROGRESSIVE_AND_ADAPTIVE_VIDEO_RESPONSE.replace(
                    "\"bitrate\":1000000,\"width\":1280,\"height\":720",
                    "\"bitrate\":1000000,\"width\":1920,\"height\":1080",
                )
            val client = jsonClient(response)
            val stream = extractor(client).extract("video", ContentHints(isExplicit = true, wantVideo = true, maxVideoHeight = 720))
            assertNotNull(stream)
            assertEquals(22, stream.videoItag)
            client.close()
        }

    @Test
    fun readyProgressiveVideoDoesNotTriggerCipherWorkForAdaptiveVideo() =
        runBlocking {
            val response =
                PROGRESSIVE_AND_ADAPTIVE_VIDEO_RESPONSE.replace(
                    "\"url\":\"https://r.googlevideo.com/videoplayback?stream=adaptive\"",
                    "\"signatureCipher\":\"url=https%3A%2F%2Fr.googlevideo.com%2Fvideoplayback&sp=sig&s=encrypted\"",
                )
            val client = jsonClient(response)
            val cipher = RecordingCipherService()
            val stream =
                makeExtractor(
                    client = client,
                    innerTube = InnerTube(client, retryDelay = {}),
                    parser = CountingParser(),
                    cipherService = cipher,
                ).extract("video", ContentHints(isExplicit = true, wantVideo = true))
            assertNotNull(stream)
            assertEquals(22, stream.videoItag)
            assertTrue(cipher.calls.isEmpty())
            client.close()
        }

    @Test
    fun progressiveVideoRemainsFallbackWhenAdaptiveVideoIsMissing() =
        runBlocking {
            val client = jsonClient(PROGRESSIVE_VIDEO_FALLBACK_RESPONSE)
            val stream = extractor(client).extract("video", ContentHints(isExplicit = true, wantVideo = true))

            assertNotNull(stream)
            assertEquals(22, stream.videoItag)
            client.close()
        }

    @Test
    fun selectedAudioAndVideoAreProcessedInOneCipherBatch() =
        runBlocking {
            val client = jsonClient(CIPHERED_VIDEO_RESPONSE)
            val cipherService = RecordingCipherService()
            val stream =
                makeExtractor(
                    client = client,
                    innerTube = InnerTube(client, retryDelay = {}),
                    parser = CountingParser(),
                    cipherService = cipherService,
                ).extract("video", ContentHints(isExplicit = true, wantVideo = true))

            assertNotNull(stream)
            assertEquals(136, stream.videoItag)
            assertEquals(listOf(listOf(251, 136)), cipherService.calls)
            client.close()
        }

    @Test
    fun normalDirectPlaybackSkipsWatchPageAndCipherSetup() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)
            val parser = CountingParser()
            try {
                val extractor = makeExtractor(client, InnerTube(client, retryDelay = {}), parser)
                assertNotNull(extractor.extract("video", ContentHints()))
                assertEquals(0, parser.calls)
            } finally {
                client.close()
            }
        }

    @Test
    fun configFreeResponseNeedingCipherFallsBackToWatchConfig() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE.replace("expire=9999999999", "expire=9999999999&n=source"))
            val parser = CountingParser()
            try {
                val extractor = makeExtractor(client, InnerTube(client, retryDelay = {}), parser, cipherService = NTransformCipherService)
                val stream = assertNotNull(extractor.extract("video", ContentHints()))
                assertTrue(stream.audioUrl.contains("n=solved"))
                assertEquals(1, parser.calls)
            } finally {
                client.close()
            }
        }

    @Test
    fun configFetchIsSharedAndSessionChangeInvalidatesIt() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)
            val innerTube = InnerTube(client, retryDelay = {})
            val parser = CountingParser()
            val extractor = makeExtractor(client, innerTube, parser)
            assertNotNull(extractor.extract("one", ContentHints(isExplicit = true)))
            assertNotNull(extractor.extract("two", ContentHints(isExplicit = true)))
            assertEquals(1, parser.calls)
            innerTube.visitorData = "new-session"
            assertNotNull(extractor.extract("three", ContentHints(isExplicit = true)))
            assertEquals(2, parser.calls)
            client.close()
        }

    @Test
    fun unusableCachedConfigIsRefreshedBeforeRetry() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            if (requests == 2) UNPLAYABLE_RESPONSE else DIRECT_RESPONSE,
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val parser = CountingParser()
            val extractor = makeExtractor(client, InnerTube(client, retryDelay = {}), parser)
            try {
                assertNotNull(extractor.extract("one", ContentHints(isExplicit = true)))
                assertNotNull(extractor.extract("two", ContentHints(isExplicit = true)))
                assertEquals(2, parser.calls)
                assertEquals(3, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun refreshedConfigRetainsBudgetAfterNativeAndCachedFailures() =
        runBlocking {
            val parser = CountingParser()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            if (requests == 1 || parser.calls >= 2) DIRECT_RESPONSE else UNPLAYABLE_RESPONSE,
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val fallback =
                object : com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy {
                    override fun resolveClients(hints: ContentHints) =
                        List(PlaybackClientCatalog.automaticManifests.size * 2) { YouTubeClient.VISIONOS }
                }
            try {
                val extractor = makeExtractor(client, InnerTube(client, retryDelay = {}), parser, fallback)
                assertNotNull(extractor.extract("prime", ContentHints(isExplicit = true)))
                assertNotNull(extractor.extract("refresh", ContentHints()))
                assertEquals(2, parser.calls)
                assertTrue(requests > PlaybackClientCatalog.automaticManifests.size * 2 + 2)
            } finally {
                client.close()
            }
        }

    @Test
    fun explicitTokenMintingRunsConcurrentlyWithConfigFetch() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val client = jsonClient(DIRECT_RESPONSE)
            val innerTube = InnerTube(client, retryDelay = {}).also { it.visitorData = "visitor" }
            var tokenCalls = 0
            val provider =
                object : TokenProvider {
                    override val capabilities = TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD))

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ): PoTokenResult {
                        tokenCalls++
                        started.complete(Unit)
                        return PoTokenResult("player-token", "AQID", visitorData)
                    }
                }
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        started.await()
                        return PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, "visitor", null)
                    }
                }
            try {
                val extractor =
                    InnerTubeExtractor(
                        parser,
                        PlayerClientDirector(innerTube, WebRemixFallback, provider),
                        AudioOnlyCipherService,
                        innerTube,
                        provider,
                    )
                assertNotNull(withTimeout(1_000) { extractor.extract("video", ContentHints(isExplicit = true)) })
                assertEquals(1, tokenCalls)
            } finally {
                client.close()
            }
        }

    @Test
    fun directPlaybackCancelsUnusedTokenPrefetch() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)
            val innerTube = InnerTube(client, retryDelay = {}).also { it.visitorData = "visitor" }
            var tokenCalls = 0
            val tokenProvider =
                object : TokenProvider {
                    override val capabilities = TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD))

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ): PoTokenResult? {
                        tokenCalls++
                        awaitCancellation()
                    }
                }
            val parser = CountingParser()
            val extractor =
                InnerTubeExtractor(
                    parser,
                    PlayerClientDirector(innerTube, DirectFallback, tokenProvider),
                    AudioOnlyCipherService,
                    innerTube,
                    tokenProvider,
                )
            try {
                assertNotNull(withTimeout(1_000) { extractor.extract("video", ContentHints(isExplicit = true)) })
                assertEquals(1, tokenCalls)
            } finally {
                client.close()
            }
        }

    @Test
    fun anonymousAndAuthenticatedPlayerConfigsAreCachedSeparately() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)
            val innerTube = InnerTube(client, retryDelay = {}).also { it.cookie = "SAPISID=session-cookie" }
            val modes = mutableListOf<Boolean>()
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        modes += useLoginCookies
                        return PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, null, null)
                    }
                }
            val extractor = makeExtractor(client, innerTube, parser)

            assertNotNull(extractor.extract("normal-one", ContentHints(playbackClientOverrideId = "VISIONOS_0_1")))
            assertNotNull(extractor.extract("explicit-one", ContentHints(isExplicit = true)))
            assertNotNull(extractor.extract("normal-two", ContentHints(playbackClientOverrideId = "VISIONOS_0_1")))
            assertNotNull(extractor.extract("explicit-two", ContentHints(isExplicit = true)))

            assertEquals(listOf(false, true), modes)
            client.close()
        }

    @Test
    fun invalidPlayerResponseProducesBoundedFailure() =
        runBlocking {
            val client = jsonClient("{\"playabilityStatus\":{\"status\":\"UNPLAYABLE\",\"reason\":\"missing\"}}")
            val failure =
                assertFailsWith<StreamResolveException> {
                    extractor(client).extract("video", ContentHints(isExplicit = true))
                }
            assertEquals(StreamResolveException.Reason.UNAVAILABLE, failure.reason)
            client.close()
        }

    @Test
    fun configFetchedBeforeSessionChangeIsNotCached() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)
            val innerTube = InnerTube(client, retryDelay = {})
            var calls = 0
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        calls++
                        if (calls == 1) innerTube.visitorData = "changed-session"
                        return PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, null, null)
                    }
                }
            val extractor = makeExtractor(client, innerTube, parser)

            assertFailsWith<CancellationException> {
                extractor.extract("first", ContentHints(isExplicit = true))
            }
            assertNotNull(extractor.extract("second", ContentHints(isExplicit = true)))
            assertEquals(2, calls)
            client.close()
        }

    @Test
    fun unapprovedMediaUrlIsRejected() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE.replace("https://r.googlevideo.com", "https://evil.test"))

            assertFailsWith<StreamResolveException> {
                extractor(client).extract("video", ContentHints(isExplicit = true))
            }
            client.close()
        }

    @Test
    fun uploadedYoutubeMediaUsesLoginCookie() =
        runBlocking {
            val response = DIRECT_RESPONSE.replace("https://r.googlevideo.com", "https://rr1.c.youtube.com")
            val client = jsonClient(response)
            val innerTube = InnerTube(client, retryDelay = {}).also { it.cookie = "SAPISID=test-cookie" }
            val stream =
                makeExtractor(client, innerTube, CountingParser(), WebRemixFallback)
                    .extract("video", ContentHints(isUploaded = true))

            assertNotNull(stream)
            assertEquals("SAPISID=test-cookie", stream.headers["Cookie"])
            client.close()
        }

    @Test
    fun uploadedYoutubeMediaRejectsNonPlaybackPath() =
        runBlocking {
            val response = DIRECT_RESPONSE.replace("https://r.googlevideo.com/videoplayback", "https://rr1.c.youtube.com/watch")
            val client = jsonClient(response)

            assertFailsWith<StreamResolveException> {
                extractor(client, WebRemixFallback).extract("video", ContentHints(isUploaded = true))
            }
            client.close()
        }

    @Test
    fun unapprovedVideoUrlIsRejected() =
        runBlocking {
            val response =
                VIDEO_RESPONSE.replace(
                    "{\"itag\":136,\"url\":\"https://r.googlevideo.com",
                    "{\"itag\":136,\"url\":\"https://evil.test",
                )
            val client = jsonClient(response)

            assertFailsWith<StreamResolveException> {
                extractor(client).extract("video", ContentHints(isExplicit = true, wantVideo = true, maxVideoHeight = 720))
            }
            client.close()
        }

    @Test
    fun videoRequestFailsWhenCipherCannotProduceVideoUrl() =
        runBlocking {
            val client = jsonClient(CIPHERED_VIDEO_RESPONSE)
            val innerTube = InnerTube(client, retryDelay = {})

            assertFailsWith<StreamResolveException> {
                makeExtractor(
                    client,
                    innerTube,
                    CountingParser(playerUrl = ""),
                    cipherService = AudioOnlyCipherService,
                ).extract("video", ContentHints(isExplicit = true, wantVideo = true))
            }
            client.close()
        }

    @Test
    fun boundedClientRequiresVideoContentLength() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.host.endsWith("googlevideo.com")) {
                            respond("", HttpStatusCode.OK)
                        } else {
                            respond(BOUNDED_VIDEO_RESPONSE, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            assertFailsWith<StreamResolveException> {
                extractor(client, IosFallback).extract("video", ContentHints(isExplicit = true, wantVideo = true))
            }
            client.close()
        }

    @Test
    fun hlsManifestIsSelectedOnlyFromApprovedEndpoint() =
        runBlocking {
            val response =
                "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"hlsManifestUrl\":\"https://www.youtube.com/api/manifest/hls.m3u8\"}}"
            val client = jsonClient(response)
            val stream = extractor(client).extract("video", ContentHints(isExplicit = true, isLive = true))

            assertNotNull(stream)
            assertEquals("https://www.youtube.com/api/manifest/hls.m3u8", stream.audioUrl)
            client.close()
        }

    @Test
    fun hlsManifestCanBeDisabledByCaller() {
        runBlocking {
            val response =
                "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"hlsManifestUrl\":\"https://www.youtube.com/api/manifest/hls.m3u8\"}}"
            val client = jsonClient(response)
            val innerTube = InnerTube(client, retryDelay = {})
            val extractor =
                makeExtractor(
                    client = client,
                    innerTube = innerTube,
                    parser = CountingParser(),
                    cipherService = AudioOnlyCipherService,
                )

            assertFailsWith<StreamResolveException> {
                extractor.extract(
                    "video",
                    ContentHints(isExplicit = true, isLive = true).withStreamCapabilities(allowHls = false),
                )
            }
            client.close()
        }
    }

    @Test
    fun boundedRangeClientCanBeDisabledByCaller() {
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)

            assertFailsWith<StreamResolveException> {
                extractor(client, IosFallback).extract(
                    "video",
                    ContentHints(isExplicit = true).withStreamCapabilities(allowBoundedRange = false),
                )
            }
            client.close()
        }
    }

    @Test
    fun automaticFallbackReachesSabrAfterDirectClientsFail() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            if (requests <= 4) UNPLAYABLE_RESPONSE else SABR_RESPONSE,
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val sabrManifest = checkNotNull(PlaybackClientCatalog.findManifest("WEB_REMIX_SABR"))
            val selectedClients =
                listOf(
                    YouTubeClient.VISIONOS,
                    YouTubeClient.VISIONOS_0_1,
                    YouTubeClient.ANDROID_VR_1_65_10,
                    YouTubeClient.TVHTML5_SIMPLY,
                ).map(::SelectedClient) + SelectedClient(sabrManifest.client, sabrManifest)
            val fallback =
                object : ClientFallbackStrategy {
                    override fun resolveClients(hints: ContentHints) = selectedClients.map(SelectedClient::client)

                    override fun selectClients(request: ClientSelectionRequest) = ClientSelectionResult(selectedClients)
                }

            val stream = extractor(client, fallback).extract("video", ContentHints(isExplicit = true))

            assertNotNull(stream)
            assertEquals("WEB_REMIX_SABR__nopo", stream.profileId)
            client.close()
        }

    @Test
    fun requiredClientMintsTokenOnlyAfterConfigIsReady() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)
            val innerTube = InnerTube(client, retryDelay = {}).also { it.visitorData = "visitor" }
            var configReady = false
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        configReady = true
                        return PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, null, null)
                    }
                }
            var tokenCalls = 0
            val tokenProvider =
                object : TokenProvider {
                    override val capabilities =
                        TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD))

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ): PoTokenResult {
                        tokenCalls++
                        assertTrue(configReady)
                        return PoTokenResult("player-token", "AQID", visitorData)
                    }
                }
            val manifest = checkNotNull(PlaybackClientCatalog.findManifest("WEB_REMIX"))
            val fallback =
                object : ClientFallbackStrategy {
                    override fun resolveClients(hints: ContentHints) = listOf(manifest.client)

                    override fun selectClients(request: ClientSelectionRequest) =
                        ClientSelectionResult(listOf(SelectedClient(manifest.client, manifest)))
                }
            val extractor =
                InnerTubeExtractor(
                    configParser = parser,
                    cipherService = YouTubeCipherService(client),
                    innerTube = innerTube,
                    fallbackStrategy = fallback,
                    tokenProvider = tokenProvider,
                )

            assertNotNull(withTimeout(5_000) { extractor.extract("video", ContentHints(isExplicit = true)) })
            assertEquals(1, tokenCalls)
            client.close()
        }

    @Test
    fun prewarmMintsPoTokenForCachedVisitor() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE)
            val innerTube =
                InnerTube(client, retryDelay = {}).also {
                    it.cookie = "SAPISID=session-cookie"
                    it.visitorData = "visitor"
                }
            val configModes = mutableListOf<Boolean>()
            var tokenRequest: Triple<String, String, String?>? = null
            val tokenProvider =
                object : TokenProvider {
                    override val capabilities =
                        TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD))

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ): PoTokenResult {
                        tokenRequest = Triple(videoId, visitorData, cookie)
                        return PoTokenResult("player-token", "video-token", visitorData)
                    }
                }
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        configModes += useLoginCookies
                        return PlayerConfig("", 123, null, null)
                    }
                }
            val extractor =
                InnerTubeExtractor(
                    parser,
                    PlayerClientDirector(innerTube, DirectFallback, tokenProvider),
                    AudioOnlyCipherService,
                    innerTube,
                    tokenProvider = tokenProvider,
                )

            extractor.prewarm()

            assertEquals(listOf(false, true), configModes)
            assertEquals(Triple("dQw4w9WgXcQ", "visitor", "SAPISID=session-cookie"), tokenRequest)
            client.close()
        }

    @Test
    fun sabrResponseBuildsAudioBootstrap() =
        runBlocking {
            val client = jsonClient(SABR_RESPONSE)
            val innerTube = InnerTube(client, retryDelay = {}).also { it.visitorData = "visitor" }
            val manifest =
                checkNotNull(
                    PlaybackClientCatalog.findManifest("WEB_SABR"),
                )
            val fallback =
                object : ClientFallbackStrategy {
                    override fun resolveClients(hints: ContentHints) = listOf(manifest.client)

                    override fun selectClients(request: ClientSelectionRequest) =
                        ClientSelectionResult(
                            listOf(
                                SelectedClient(manifest.client, manifest),
                            ),
                        )
                }
            val tokenProvider =
                object : TokenProvider {
                    override val capabilities =
                        TokenProviderCapabilities(
                            providers = PoTokenProviderKind.entries.toSet(),
                        )

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ) = PoTokenResult("player-token", "AQID", visitorData)
                }
            val extractor =
                InnerTubeExtractor(
                    CountingParser(),
                    PlayerClientDirector(innerTube, fallback, tokenProvider),
                    DefaultExtractionCipherService(YouTubeCipherService(client)),
                    innerTube,
                )

            val stream = extractor.extract("video", ContentHints(playbackClientOverrideId = "WEB_SABR"))

            assertNotNull(stream)
            assertEquals("sabr://video", stream.audioUrl)
            assertEquals(140, stream.itag)
            assertNotNull(stream.sabrBootstrap)
            client.close()
        }

    @Test
    fun poTokenIsEncodedBeforeFragmentAndNonceIsPreserved() =
        runBlocking {
            val client = jsonClient(DIRECT_RESPONSE.replace("?expire=9999999999", "?expire=9999999999#fragment"))
            val innerTube = InnerTube(client, retryDelay = {}).also { it.visitorData = "visitor" }
            val manifest =
                checkNotNull(
                    com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
                        .findManifest("WEB_REMIX"),
                )
            val tokenProvider =
                object : TokenProvider {
                    override val capabilities =
                        TokenProviderCapabilities(
                            providers = setOf(com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind.WEB_BOTGUARD),
                        )

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ) = PoTokenResult("player", "a+b&c", visitorData)
                }
            val fallback =
                object : com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy {
                    override fun resolveClients(hints: ContentHints) = listOf(manifest.client)

                    override fun selectClients(request: com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest) =
                        com.metrolist.innertubex.extraction.strategy.ClientSelectionResult(
                            listOf(
                                com.metrolist.innertubex.extraction.strategy
                                    .SelectedClient(manifest.client, manifest),
                            ),
                        )
                }
            val extractor =
                InnerTubeExtractor(
                    CountingParser(),
                    PlayerClientDirector(innerTube, fallback, tokenProvider),
                    DefaultExtractionCipherService(YouTubeCipherService(client)),
                    innerTube,
                )

            val stream =
                extractor.extract(
                    "video",
                    ContentHints(playbackClientOverrideId = "WEB_REMIX"),
                    clientPlaybackNonce = "abcdefghijklmnop",
                )

            assertNotNull(stream)
            assertTrue(stream.audioUrl.contains("pot=a%2Bb%26c"))
            assertTrue(stream.audioUrl.contains("#fragment"))
            assertTrue(stream.audioUrl.contains("cpn=abcdefghijklmnop"))
            client.close()
        }

    private fun extractor(
        client: HttpClient,
        fallback: com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy = DirectFallback,
    ): InnerTubeExtractor = makeExtractor(client, InnerTube(client, retryDelay = {}), CountingParser(), fallback)

    private fun makeExtractor(
        client: HttpClient,
        innerTube: InnerTube,
        parser: YtConfigParser,
        fallback: com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy = DirectFallback,
        cipherService: ExtractionCipherService = DefaultExtractionCipherService(YouTubeCipherService(client)),
    ): InnerTubeExtractor =
        InnerTubeExtractor(
            configParser = parser,
            clientDirector = PlayerClientDirector(innerTube, fallback, NoTokenProvider),
            cipherService = cipherService,
            innerTube = innerTube,
        )

    private fun jsonClient(body: String) =
        HttpClient(
            MockEngine {
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private class CountingParser(
        private val playerUrl: String = "https://www.youtube.com/s/player/test/base.js",
    ) : YtConfigParser {
        var calls = 0

        override suspend fun fetchConfig(
            videoId: String,
            useLoginCookies: Boolean,
        ): PlayerConfig {
            calls++
            delay(1)
            return PlayerConfig(playerUrl, 123, null, null)
        }
    }

    private object DirectFallback : com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy {
        override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.VISIONOS)
    }

    private object WebRemixFallback : com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy {
        override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.WEB_REMIX)
    }

    private object IosFallback : com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy {
        override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.IOS)
    }

    private object NoTokenProvider : TokenProvider {
        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ) = null
    }

    private object NTransformCipherService : ExtractionCipherService {
        override suspend fun initialize() {}

        override suspend fun preloadPlayerCode(playerUrl: String) {}

        override suspend fun prewarmEjs() {}

        override suspend fun processFormats(
            playerUrl: String,
            formats: List<PlayerResponse.StreamingData.Format>,
        ): List<PlayerResponse.StreamingData.Format> =
            formats.map { format -> format.copy(url = format.url?.replace("n=source", "n=solved")) }
    }

    private object AudioOnlyCipherService : ExtractionCipherService {
        override suspend fun initialize() {}

        override suspend fun preloadPlayerCode(playerUrl: String) {}

        override suspend fun prewarmEjs() {}

        override suspend fun processFormats(
            playerUrl: String,
            formats: List<PlayerResponse.StreamingData.Format>,
        ): List<PlayerResponse.StreamingData.Format> = formats.filter(PlayerResponse.StreamingData.Format::isAudio)
    }

    private class RecordingCipherService : ExtractionCipherService {
        val calls = mutableListOf<List<Int>>()

        override suspend fun initialize() {}

        override suspend fun preloadPlayerCode(playerUrl: String) {}

        override suspend fun prewarmEjs() {}

        override suspend fun processFormats(
            playerUrl: String,
            formats: List<PlayerResponse.StreamingData.Format>,
        ): List<PlayerResponse.StreamingData.Format> {
            calls += formats.map { it.itag }
            return formats.map { format ->
                format.copy(url = format.url ?: "https://r.googlevideo.com/videoplayback?itag=${format.itag}")
            }
        }
    }

    private companion object {
        val DIRECT_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","title":"Track title","author":"Artist","channelId":"channel","lengthSeconds":"60","musicVideoType":"MUSIC_VIDEO_TYPE_ATV","viewCount":"42","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/video/default.jpg","width":120,"height":90}]}},"playerConfig":{"audioConfig":{"loudnessDb":-5.0,"perceptualLoudnessDb":-8.5}},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback?expire=9999999999","mimeType":"audio/webm; codecs=\"opus\"","bitrate":128000}]}}
            """.trimIndent()
        val VIDEO_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback","mimeType":"audio/webm","bitrate":128000},{"itag":136,"url":"https://r.googlevideo.com/videoplayback","mimeType":"video/mp4; codecs=\"avc1\"","bitrate":1000000,"width":1280,"height":720},{"itag":247,"url":"https://r.googlevideo.com/videoplayback","mimeType":"video/webm; codecs=\"vp9\"","bitrate":2000000,"width":1920,"height":1080},{"itag":313,"url":"https://r.googlevideo.com/videoplayback","mimeType":"video/webm; codecs=\"vp9\"","bitrate":10000000,"width":3840,"height":2160}]}}
            """.trimIndent()
        val CIPHERED_VIDEO_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback","mimeType":"audio/webm","bitrate":128000},{"itag":136,"signatureCipher":"url=https%3A%2F%2Fr.googlevideo.com%2Fvideoplayback&sp=sig&s=encrypted","mimeType":"video/mp4; codecs=\"avc1\"","bitrate":1000000,"width":1280,"height":720}]}}
            """.trimIndent()
        val PROGRESSIVE_AND_ADAPTIVE_VIDEO_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":22,"url":"https://r.googlevideo.com/videoplayback?stream=progressive","mimeType":"video/mp4; codecs=\"avc1\"","bitrate":5000000,"width":1280,"height":720}],"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback?stream=audio","mimeType":"audio/webm","bitrate":128000},{"itag":136,"url":"https://r.googlevideo.com/videoplayback?stream=adaptive","mimeType":"video/mp4; codecs=\"avc1\"","bitrate":1000000,"width":1280,"height":720}]}}
            """.trimIndent()
        val PROGRESSIVE_VIDEO_FALLBACK_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":22,"url":"https://r.googlevideo.com/videoplayback?stream=progressive","mimeType":"video/mp4; codecs=\"avc1\"","bitrate":5000000,"width":1280,"height":720}],"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback?stream=audio","mimeType":"audio/webm","bitrate":128000}]}}
            """.trimIndent()
        val BOUNDED_VIDEO_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback?stream=audio","mimeType":"audio/webm","bitrate":128000,"contentLength":1234},{"itag":136,"url":"https://r.googlevideo.com/videoplayback?stream=video","mimeType":"video/mp4; codecs=\"avc1\"","bitrate":1000000,"width":1280,"height":720}]}}
            """.trimIndent()

        val UNPLAYABLE_RESPONSE =
            """
            {"playabilityStatus":{"status":"UNPLAYABLE","reason":"unavailable"}}
            """.trimIndent()

        val SABR_RESPONSE =
            """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","lengthSeconds":"60"},"streamingData":{"serverAbrStreamingUrl":"https://r.googlevideo.com/videoplayback?sabr=1","adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"contentLength":1234,"lastModified":"100","approxDurationMs":"60000"},{"itag":160,"mimeType":"video/mp4; codecs=\"avc1\"","bitrate":100000,"width":256,"height":144,"contentLength":2345,"lastModified":"200"}]},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}}
            """.trimIndent()
    }
}
