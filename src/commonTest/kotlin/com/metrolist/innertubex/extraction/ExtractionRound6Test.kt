package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtractionRound6Test {
    @Test
    fun hintedAndLearnedAgeKeepTokenBackedDirectBeforeSabr() =
        runBlocking {
            for (hinted in listOf(false, true)) {
                val fixture = Fixture { name, _ -> if (name == "VISIONOS") AGE else DIRECT_VIDEO }
                try {
                    val tokens = CountingTokens(available = true)
                    val batch =
                        PlayerClientDirector(fixture.tube, ContentAwareFallbackStrategy(), tokens)
                            .fetchPlayerResponses("video", CONFIG, ContentHints(isAgeRestricted = hinted))
                    assertEquals("WEB_REMIX__po", batch.playableResponses.single().profileId)
                    assertEquals((if (hinted) emptyList() else listOf("VISIONOS")) + "WEB_REMIX", fixture.players)
                    assertEquals(1, tokens.calls)
                } finally {
                    fixture.client.close()
                }
            }
        }

    @Test
    fun fastPathErrorsReachAuthenticatedConfiguredPlayback() =
        runBlocking {
            val fixture = Fixture { name, _ -> if (name == "WEB_REMIX") SABR else ERROR }
            try {
                val stream = assertNotNull(fixture.extractor().extract("video", ContentHints()))
                assertEquals("WEB_REMIX_SABR__nopo", stream.profileId)
                assertEquals(listOf("VISIONOS", "VISIONOS", "WEB_EMBEDDED_PLAYER", "WEB_REMIX"), fixture.players)
                assertEquals(1, fixture.watches)
                assertEquals(1, fixture.authenticatedRemix)
                assertEquals(0, fixture.embeds)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun configuredAnonymousErrorsAreNotTerminalOrCachedWhenSignedIn() =
        runBlocking {
            val fixture = Fixture { _, _ -> ERROR }
            try {
                val director = fixture.director(FixedStrategy("VISIONOS"))
                val batch = director.fetchPlayerResponses("video", CONFIG, ContentHints())
                assertFalse(batch.onlyUnavailableFailures)
                val extractor = fixture.extractor(FixedStrategy("VISIONOS"))
                assertFailsWith<StreamResolveException> { extractor.extract("video", ContentHints(isExplicit = true)) }
                val before = fixture.players.size
                assertFailsWith<StreamResolveException> { extractor.extract("video", ContentHints(isExplicit = true)) }
                assertTrue(fixture.players.size > before)
                fixture.tube.cookie = null
                assertTrue(director.fetchPlayerResponses("video", CONFIG, ContentHints()).onlyUnavailableFailures)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun embeddedErrorWithoutFlagsFetchesEmbedContextBeforeRetry() =
        runBlocking {
            val fixture = Fixture { _, body -> if (body.contains("encryptedHostFlags")) DIRECT_VIDEO else ERROR }
            try {
                val hints = ContentHints(wantVideo = true).withStreamCapabilities(allowSabr = false)
                val stream = assertNotNull(fixture.extractor().extract("video", hints))
                assertEquals("WEB_EMBEDDED_PLAYER__nopo", stream.profileId)
                assertEquals(listOf("WEB_EMBEDDED_PLAYER", "WEB_EMBEDDED_PLAYER"), fixture.players)
                assertEquals(1, fixture.embeds)
                assertEquals(2, fixture.watches)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun configuredRemovedVideoStopsAfterOneRequestAndCachesVerdict() =
        runBlocking {
            for (authenticated in listOf(false, true)) {
                val fixture = Fixture(authenticated) { _, _ -> ERROR }
                try {
                    val extractor = fixture.extractor(FixedStrategy("WEB_REMIX_SABR"))
                    repeat(2) {
                        val failure =
                            assertFailsWith<StreamResolveException> { extractor.extract("video", ContentHints(isAgeRestricted = true)) }
                        assertEquals(StreamResolveException.Reason.UNAVAILABLE, failure.reason)
                    }
                    assertEquals(listOf("WEB_REMIX"), fixture.players)
                    assertEquals(1, fixture.watches)
                    assertEquals(0, fixture.embeds)
                } finally {
                    fixture.client.close()
                }
            }
        }

    @Test
    fun enablingSabrBypassesCachedAgeVerdict() =
        runBlocking {
            val fixture = Fixture { name, _ -> if (name == "WEB_REMIX") SABR else AGE }
            try {
                val extractor = fixture.extractor()
                val disabled = ContentHints().withStreamCapabilities(allowSabr = false)
                assertEquals(
                    StreamResolveException.Reason.AGE_RESTRICTED,
                    assertFailsWith<StreamResolveException> { extractor.extract("video", disabled) }.reason,
                )
                val before = fixture.players.size
                assertFailsWith<StreamResolveException> { extractor.extract("video", disabled) }
                assertEquals(before, fixture.players.size)
                assertFalse("WEB_REMIX" in fixture.players)
                val stream = assertNotNull(extractor.extract("video", disabled.withStreamCapabilities(allowSabr = true)))
                assertEquals("WEB_REMIX_SABR__nopo", stream.profileId)
                assertTrue(fixture.players.size > before)
                assertEquals(0, fixture.tokens.calls)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun terminalCacheSeparatesSelectionInputs() =
        runBlocking {
            val fixture = Fixture { _, _ -> ERROR }
            try {
                val extractor = fixture.extractor(FixedStrategy("WEB_REMIX_SABR"))
                val base = ContentHints(isAgeRestricted = true)
                val variants =
                    listOf(
                        base,
                        base.withStreamCapabilities(allowHls = false),
                        base.withStreamCapabilities(allowSabr = false),
                        base.withStreamCapabilities(allowBoundedRange = false),
                        base.copy(isExplicit = true),
                        base.copy(isKidsContent = true),
                        base.copy(isLive = true),
                        base.copy(isUploaded = true),
                        base.copy(wantVideo = true),
                        base.copy(sabrFirst = true),
                        base.withPremium(),
                    )
                for ((index, hints) in variants.withIndex()) {
                    repeat(2) { assertFailsWith<StreamResolveException> { extractor.extract("video", hints) } }
                    assertEquals(index + 1, fixture.players.size)
                }
                repeat(2) {
                    assertFailsWith<StreamResolveException> { extractor.extract("video", base, audioQuality = AudioQuality.HIGH) }
                    assertFailsWith<StreamResolveException> { extractor.extract("video", base, excludedClients = setOf("VISIONOS")) }
                }
                assertEquals(variants.size + 2, fixture.players.size)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun localizedLoginGateSkipsUnsupportedClientsAndUsesTokenlessSabr() =
        runBlocking {
            val fixture = Fixture { name, _ -> if (name == "WEB_REMIX") SABR else AGE }
            try {
                val budget = PlayerRequestBudget(20)
                val batch = fixture.director().fetchPlayerResponses("video", CONFIG, ContentHints(), requestBudget = budget)
                assertTrue(budget.ageRestricted)
                assertEquals(listOf("VISIONOS", "WEB_REMIX"), fixture.players)
                assertEquals("WEB_REMIX_SABR__nopo", batch.playableResponses.single().profileId)
                assertEquals(0, fixture.tokens.calls)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun autoAndLearnedAgePreserveHealthDemotionWithoutSabrPromotion() =
        runBlocking {
            val health =
                object : ClientHealthMonitor {
                    override fun scoreAdjustment(
                        clientId: String,
                        scope: ClientHealthScope?,
                    ) = if (clientId == "WEB_REMIX_SABR") -100 else 0
                }
            val strategy = ContentAwareFallbackStrategy(health)
            for (age in listOf(false, true)) {
                val selection =
                    strategy
                        .selectClients(
                            ClientSelectionRequest(
                                ContentHints(isAgeRestricted = age),
                                authenticated = true,
                                availablePoTokenProviders = setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION),
                            ),
                        ).candidates
                        .map { it.manifest!!.id }
                assertTrue(selection.indexOf("WEB_REMIX") < selection.indexOf("WEB_REMIX_SABR"))
                assertTrue(selection.indexOf("WEB_SABR") < selection.indexOf("WEB_REMIX_SABR"))
            }
            val fixture = Fixture { name, _ -> if (name == "VISIONOS") AGE else SABR }
            try {
                val tokens = CountingTokens(available = true)
                val batch =
                    PlayerClientDirector(fixture.tube, strategy, tokens, health)
                        .fetchPlayerResponses("video", CONFIG, ContentHints())
                assertEquals(listOf("VISIONOS", "WEB_REMIX", "WEB_CREATOR", "WEB"), fixture.players)
                assertEquals("WEB_SABR__po", batch.playableResponses.single().profileId)
                assertEquals(1, tokens.calls)
            } finally {
                fixture.client.close()
            }
        }

    private class FixedStrategy(
        vararg ids: String,
    ) : ClientFallbackStrategy {
        private val manifests = ids.map { checkNotNull(PlaybackClientCatalog.findManifest(it)) }

        override fun resolveClients(hints: ContentHints) = manifests.map { it.client }

        override fun selectClients(request: ClientSelectionRequest) = ClientSelectionResult(manifests.map { SelectedClient(it.client, it) })
    }

    private class Fixture(
        authenticated: Boolean = true,
        body: (String, String) -> String,
    ) {
        val players = mutableListOf<String>()
        var watches = 0
        var embeds = 0
        var authenticatedRemix = 0
        val tokens = CountingTokens()
        val client =
            HttpClient(
                MockEngine { request ->
                    val response =
                        when {
                            request.url.encodedPath.endsWith("/player") -> {
                                val text = (request.body as TextContent).text
                                val context = Json.parseToJsonElement(text).jsonObject["context"]!!.jsonObject
                                val name = context["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content
                                players += name
                                if (name == "WEB_REMIX" && request.headers[HttpHeaders.Authorization] != null) authenticatedRemix++
                                body(name, text)
                            }

                            request.url.encodedPath == "/watch" -> {
                                watches++
                                WATCH
                            }

                            request.url.encodedPath.startsWith("/embed/") -> {
                                embeds++
                                WATCH.dropLast(1) + ",\"encryptedHostFlags\":\"synthetic-flags\"}"
                            }

                            else -> {
                                error("Unexpected mock request")
                            }
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val tube =
            InnerTube(client, retryDelay = {}).also {
                it.visitorData = "synthetic-visitor"
                if (authenticated) it.cookie = "SAPISID=synthetic"
            }

        fun director(strategy: ClientFallbackStrategy = ContentAwareFallbackStrategy()) = PlayerClientDirector(tube, strategy, tokens)

        fun extractor(strategy: ClientFallbackStrategy = ContentAwareFallbackStrategy()) =
            InnerTubeExtractor(
                YtConfigParserImpl(client, tube),
                director(strategy),
                NoCipher,
                tube,
            )
    }

    private class CountingTokens(
        available: Boolean = false,
    ) : TokenProvider {
        var calls = 0
        override val capabilities = TokenProviderCapabilities(if (available) setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION) else emptySet())

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): PoTokenResult {
            calls++
            return PoTokenResult("AQID", "AQID", visitorData)
        }
    }

    private object NoCipher : ExtractionCipherService {
        override suspend fun initialize() {}

        override suspend fun preloadPlayerCode(playerUrl: String) {}

        override suspend fun prewarmEjs() {}

        override suspend fun processFormats(
            playerUrl: String,
            formats: List<PlayerResponse.StreamingData.Format>,
        ) = formats
    }

    private companion object {
        val CONFIG = PlayerConfig("https://www.youtube.com/s/player/test/base.js", 12345, "synthetic-visitor", null)
        const val ERROR = """{"playabilityStatus":{"status":"ERROR"}}"""
        const val AGE = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","desktopLegacyAgeGateReason":1}}"""
        const val WATCH =
            """{"PLAYER_JS_URL":"/s/player/test/base.js","STS":12345,"visitorData":"synthetic-visitor",""" +
                """"INNERTUBE_CLIENT_VERSION":"2.0"}"""
        const val DIRECT_VIDEO = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
            {"itag":140,"mimeType":"audio/mp4","bitrate":128000,"contentLength":"1000",
            "url":"https://r1.googlevideo.com/videoplayback?id=synthetic"},
            {"itag":160,"mimeType":"video/mp4","width":256,"height":144,"contentLength":"1000",
            "url":"https://r1.googlevideo.com/videoplayback?id=synthetic-video"}]}}
        """
        const val SABR = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","lengthSeconds":"10"},
            "streamingData":{"serverAbrStreamingUrl":"https://r1.googlevideo.com/videoplayback","adaptiveFormats":[
            {"itag":140,"mimeType":"audio/mp4","bitrate":128000,"lastModified":"5","contentLength":"1000"},
            {"itag":160,"mimeType":"video/mp4","width":256,"height":144,"lastModified":"7"}]},
            "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}}
        """
    }
}
