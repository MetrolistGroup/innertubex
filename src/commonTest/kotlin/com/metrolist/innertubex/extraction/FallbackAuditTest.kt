package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.InMemoryClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class FallbackAuditTest {
    @Test
    fun visitorBootstrapKeepsTvDiscoveryAndUsesPublishedGeneration() =
        runBlocking {
            val client =
                client { request ->
                    when {
                        !request.url.encodedPath.endsWith("/player") -> """{"VISITOR_DATA":"bootstrap-visitor"}"""
                        request.headers["X-Goog-Visitor-Id"].isNullOrBlank() -> AGE
                        else -> DIRECT
                    }
                }
            val tube = InnerTube(client, retryDelay = {})
            val before = tube.sessionSnapshot().generation
            var credentialGeneration: Long? = null
            val provider =
                object : TvBearerCredentialProvider {
                    override suspend fun getCredential(
                        videoId: String,
                        sessionGeneration: Long,
                    ): TvBearerCredential? {
                        credentialGeneration = sessionGeneration
                        return null
                    }

                    override suspend fun isCredentialCurrent(credential: TvBearerCredential) = true
                }
            try {
                val stream =
                    extractor(tube, catalog("WEB_REMIX"), tokens()).extractWithAuthenticatedTvDiscovery(
                        "video",
                        true,
                        provider,
                        ContentHints(isAgeRestricted = true),
                    )
                assertNotNull(stream)
                assertTrue(tube.sessionSnapshot().generation > before)
                assertEquals(tube.sessionSnapshot().generation, credentialGeneration)
            } finally {
                client.close()
            }
        }

    @Test
    fun providerLocalTimeoutsKeepBaselineAndCallerTimeoutStillCancels() =
        runBlocking {
            for (duringValidation in listOf(false, true)) {
                val client = client { DIRECT }
                val tube = InnerTube(client, retryDelay = {})
                val provider =
                    object : TvBearerCredentialProvider {
                        override suspend fun getCredential(
                            videoId: String,
                            sessionGeneration: Long,
                        ): TvBearerCredential? {
                            if (!duringValidation) withTimeout(10) { awaitCancellation() }
                            return TvBearerCredential("fixture", "TVHTML5", Clock.System.now() + 1.hours, sessionGeneration)
                        }

                        override suspend fun isCredentialCurrent(credential: TvBearerCredential): Boolean =
                            withTimeout(10) { awaitCancellation() }
                    }
                try {
                    assertNotNull(
                        extractor(tube).extractWithAuthenticatedTvDiscovery("video", true, provider, ContentHints(isExplicit = true)),
                    )
                    val stalled =
                        object : TvBearerCredentialProvider {
                            override suspend fun getCredential(
                                videoId: String,
                                sessionGeneration: Long,
                            ): TvBearerCredential? = awaitCancellation()

                            override suspend fun isCredentialCurrent(credential: TvBearerCredential) = true
                        }
                    assertFailsWith<TimeoutCancellationException> {
                        withTimeout(
                            100,
                        ) { extractor(tube).extractWithAuthenticatedTvDiscovery("video", true, stalled, ContentHints(isExplicit = true)) }
                    }
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun unknownKidsRecoveryTriesAndroidKids() =
        runBlocking {
            val requested = mutableListOf<String?>()
            val client =
                client { request ->
                    val id = request.headers["X-YouTube-Client-Name"]
                    requested += id
                    if (id == YouTubeClient.ANDROID_KIDS.clientId) DIRECT else UNPLAYABLE
                }
            try {
                val tube = InnerTube(client, retryDelay = {})
                val stream = assertNotNull(extractor(tube, ContentAwareFallbackStrategy()).extract("video", ContentHints()))
                assertEquals(YouTubeClient.ANDROID_KIDS.clientName, stream.clientName)
                assertTrue(YouTubeClient.ANDROID_KIDS.clientId in requested)
            } finally {
                client.close()
            }
        }

    @Test
    fun staleConfigFailuresDoNotOverrideALaterPlayableResponse() =
        runBlocking {
            for (embeddedFails in listOf(false, true)) {
                val client = client { DIRECT.replace("r.googlevideo.com", "invalid.test") }
                val tube = InnerTube(client, retryDelay = {}).also { it.cookie = "SAPISID=fixture" }
                var configCalls = 0
                val parser =
                    object : YtConfigParser {
                        override suspend fun fetchConfig(
                            videoId: String,
                            useLoginCookies: Boolean,
                        ): PlayerConfig {
                            if (++configCalls == 1) error("Fixture watch config failure")
                            return CONFIG
                        }

                        override suspend fun fetchEmbeddedConfig(
                            videoId: String,
                            useLoginCookies: Boolean,
                        ): PlayerConfig {
                            if (embeddedFails) error("Fixture embedded config failure")
                            return CONFIG
                        }
                    }
                try {
                    val extractor =
                        InnerTubeExtractor(
                            parser,
                            PlayerClientDirector(tube, legacy(YouTubeClient.VISIONOS), UnavailableTokenProvider),
                            cipher { it },
                            tube,
                        )
                    val error =
                        assertFailsWith<StreamResolveException> {
                            extractor.extract("video", ContentHints(isExplicit = true, isKidsContent = false))
                        }
                    assertEquals(2, configCalls)
                    assertTrue(error.diagnostics?.attempts?.any { it.outcome == "playable_response" } == true)
                    assertEquals(StreamResolveException.Reason.NO_PLAYABLE_STREAM, error.reason)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun cipherFailureKeepsNetworkCauseAfterPlayableResponse() =
        runBlocking {
            val failure = IOException("fixture transport failure")
            val client = client { DIRECT.replace("?expire=", "?n=source&expire=") }
            try {
                val error =
                    assertFailsWith<StreamResolveException> {
                        extractor(InnerTube(client, retryDelay = {}), cipher = cipher { throw failure })
                            .extract("video", ContentHints(playbackClientOverrideId = "VISIONOS"))
                    }
                assertEquals(StreamResolveException.Reason.NETWORK, error.reason)
                assertSame(failure, error.cause)
            } finally {
                client.close()
            }
        }

    @Test
    fun sabrVideoSkipsInvalidIdentitiesBeforeRanking() =
        runBlocking {
            for (invalid in listOf(
                "",
                ",\"lastModified\":\"0\"",
                ",\"lastModified\":\"bad\"",
                ",\"lastModified\":\"-1\"",
                ",\"itag\":0,\"lastModified\":\"7\"",
            )) {
                val high =
                    """{"itag":137,"mimeType":"video/mp4","width":1920,"height":1080$invalid}""".let {
                        if (invalid.contains("itag")) it.replace("\"itag\":137,", "") else it
                    }
                val client = client { SABR.replace("\"adaptiveFormats\":[", "\"adaptiveFormats\":[$high,") }
                try {
                    val stream =
                        assertNotNull(
                            extractor(InnerTube(client, retryDelay = {}), catalog("WEB_REMIX_SABR"))
                                .extract("video", ContentHints(wantVideo = true)),
                        )
                    assertEquals(720, stream.videoHeight)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun streamExpiryUsesEarliestSelectedUrlOrResponseLifetime() =
        runBlocking {
            val now = Instant.fromEpochSeconds(1_000)
            val cases =
                listOf(
                    DIRECT
                        .replace(
                            "?expire=9999999999",
                            "",
                        ).replace("\"adaptiveFormats\"", "\"expiresInSeconds\":60,\"adaptiveFormats\"") to 1_060L,
                    VIDEO.replace("\"adaptiveFormats\"", "\"expiresInSeconds\":600,\"adaptiveFormats\"") to 1_030L,
                    SABR
                        .replace(
                            "?sabr=1",
                            "?sabr=1&expire=1020",
                        ).replace("\"adaptiveFormats\"", "\"expiresInSeconds\":600,\"adaptiveFormats\"") to
                        1_020L,
                )
            for ((index, case) in cases.withIndex()) {
                val client = client { case.first }
                try {
                    val stream =
                        assertNotNull(
                            extractor(
                                InnerTube(client, retryDelay = {}),
                                if (index == 2) catalog("WEB_REMIX_SABR") else catalog("VISIONOS"),
                                now = { now },
                            ).extract("video", ContentHints(isExplicit = true, wantVideo = index == 1)),
                        )
                    assertEquals(Instant.fromEpochSeconds(case.second), stream.expiresAt)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun failedPreferredVideoTransformUsesReadyLowerRendition() =
        runBlocking {
            val client = client { VIDEO.replace("?expire=1030", "?n=source&expire=1030") }
            try {
                val stream =
                    assertNotNull(
                        extractor(
                            InnerTube(client, retryDelay = {}),
                            cipher =
                                cipher { formats ->
                                    formats.map { if (it.url?.contains("n=source") == true) it.copy(url = null) else it }
                                },
                        ).extract("video", ContentHints(isExplicit = true, wantVideo = true, maxVideoHeight = 1080)),
                    )
                assertEquals(720, stream.videoHeight)
                assertEquals(Instant.fromEpochSeconds(1_020), stream.expiresAt)
            } finally {
                client.close()
            }
        }

    @Test
    fun throwingCipherStillUsesHlsAndRemainingClients() =
        runBlocking {
            for (hls in listOf(true, false)) {
                val client =
                    client { request ->
                        if (request.headers["X-YouTube-Client-Name"] == YouTubeClient.VISIONOS.clientId) {
                            val response = DIRECT.replace("?expire=", "?n=source&expire=")
                            if (hls) {
                                response.replace(
                                    "\"adaptiveFormats\"",
                                    "\"hlsManifestUrl\":\"https://manifest.googlevideo.com/api/manifest/hls_variant/test\"," +
                                        "\"adaptiveFormats\"",
                                )
                            } else {
                                response
                            }
                        } else {
                            DIRECT
                        }
                    }
                try {
                    val fallback = legacy(YouTubeClient.VISIONOS, YouTubeClient.WEB_EMBEDDED_PLAYER)
                    val stream =
                        assertNotNull(
                            extractor(InnerTube(client, retryDelay = {}), fallback, cipher = cipher { error("fixture cipher failure") })
                                .extract("video", ContentHints(isAgeRestricted = true)),
                        )
                    assertEquals(if (hls) "application/x-mpegURL" else "audio/webm", stream.mimeType)
                    if (!hls) assertEquals("WEB_EMBEDDED_PLAYER", stream.clientName)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun fragmentPotDecoyCannotSuppressQueryToken() =
        runBlocking {
            val client = client { DIRECT.replace("9999999999", "9999999999#note&pot=decoy") }
            try {
                val tube = InnerTube(client, retryDelay = {}).also { it.visitorData = "visitor" }
                val stream =
                    assertNotNull(extractor(tube, catalog("WEB_REMIX"), tokens()).extract("video", ContentHints(isAgeRestricted = true)))
                assertEquals("fixture", Url(stream.audioUrl).parameters["pot"])
                assertTrue(stream.audioUrl.endsWith("#note&pot=decoy"))
            } finally {
                client.close()
            }
        }

    @Test
    fun learnedAgeGateRetriesVisitorAndTokenRecovery() =
        runBlocking {
            for (previouslyLearned in listOf(false, true)) {
                val client =
                    client { request ->
                        when {
                            !request.url.encodedPath.endsWith("/player") -> """{"VISITOR_DATA":"bootstrap-visitor"}"""
                            request.headers["X-Goog-Visitor-Id"].isNullOrBlank() -> AGE
                            else -> DIRECT
                        }
                    }
                try {
                    val tube = InnerTube(client, retryDelay = {})
                    val budget = PlayerRequestBudget(5).also { it.ageRestricted = previouslyLearned }
                    val batch =
                        PlayerClientDirector(tube, catalog("WEB_REMIX"), tokens())
                            .fetchPlayerResponses("video", CONFIG, ContentHints(), requestBudget = budget)
                    assertEquals(1, batch.playableResponses.size)
                    assertTrue(batch.playableResponses.single().usedPoToken)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun sharedVisitorProducerTimeoutKeepsPlaybackFallback() =
        runBlocking {
            val client =
                client { request ->
                    if (!request.url.encodedPath.endsWith("/player")) withTimeout(10) { awaitCancellation() }
                    if (request.headers["X-YouTube-Client-Name"] == YouTubeClient.WEB_REMIX.clientId) AGE else DIRECT
                }
            try {
                val tube = InnerTube(client, retryDelay = {})
                val batch =
                    PlayerClientDirector(
                        tube,
                        object : ClientFallbackStrategy {
                            override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.WEB_REMIX, YouTubeClient.VISIONOS)

                            override fun selectClients(request: ClientSelectionRequest) =
                                ClientSelectionResult(
                                    listOf(
                                        SelectedClient(YouTubeClient.WEB_REMIX, PlaybackClientCatalog.findManifest("WEB_REMIX")),
                                        SelectedClient(YouTubeClient.VISIONOS),
                                    ),
                                )
                        },
                        tokens(),
                    ).fetchPlayerResponses("video", CONFIG, ContentHints(isAgeRestricted = true))
                assertEquals(YouTubeClient.VISIONOS.clientName, batch.playableResponses.single().clientName)
            } finally {
                client.close()
            }
        }

    @Test
    fun callerTimeoutDoesNotRecordClientHealthFailure() =
        runBlocking {
            val client = client { awaitCancellation() }
            try {
                val tube = InnerTube(client, retryDelay = {})
                val health = InMemoryClientHealthMonitor()
                val director = PlayerClientDirector(tube, catalog("VISIONOS"), UnavailableTokenProvider, health)
                repeat(2) {
                    assertNull(withTimeoutOrNull(30) { director.fetchPlayerResponses("video", CONFIG, ContentHints()) })
                }
                assertEquals(0, health.scoreAdjustment("VISIONOS", ClientHealthScope.from(ContentHints(), false)))
            } finally {
                client.close()
            }
        }

    @Test
    fun bearerJsonCollectionBudgetRejectsBeforeTreeMaterialization() =
        runBlocking {
            val entries = List(100_000) { "{}" }.joinToString(",")
            val payloads = listOf("[$entries]", "[".repeat(65) + "0" + "]".repeat(65))
            for (payload in payloads) {
                val client = client { DIRECT.replace("\"adaptiveFormats\"", "\"unknownCollection\":$payload,\"adaptiveFormats\"") }
                try {
                    val tube = InnerTube(client, retryDelay = {})
                    val credential =
                        TvBearerCredential("fixture", "TVHTML5", Clock.System.now() + 1.hours, tube.sessionSnapshot().generation)
                    val batch =
                        PlayerClientDirector(tube, catalog("TVHTML5"), UnavailableTokenProvider)
                            .fetchPlayerResponses("video", CONFIG, ContentHints(), tvBearerCredential = credential)
                    assertTrue(batch.playableResponses.isEmpty())
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun jsonBudgetIgnoresEscapedPunctuationInsideStrings() =
        runBlocking {
            val punctuation =
                kotlinx.serialization.json
                    .JsonPrimitive("\\\"{[:,]}".repeat(20_000))
                    .toString()
            val client = client { DIRECT.replace("\"adaptiveFormats\"", "\"unknown\":$punctuation,\"adaptiveFormats\"") }
            try {
                val tube = InnerTube(client, retryDelay = {})
                val batch =
                    PlayerClientDirector(tube, catalog("VISIONOS"), UnavailableTokenProvider)
                        .fetchPlayerResponses("video", CONFIG, ContentHints())
                assertEquals(1, batch.playableResponses.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun cookieResponsesRejectStreamingAndFormatDrmMarkers() =
        runBlocking {
            for (response in listOf(
                DIRECT.replace("\"adaptiveFormats\"", "\"licenseInfos\":[{\"type\":\"WIDEVINE\"}],\"adaptiveFormats\""),
                DIRECT.replace("\"itag\":251", "\"drmFamilies\":[\"WIDEVINE\"],\"itag\":251"),
            )) {
                val client = client { response }
                try {
                    val tube = InnerTube(client, retryDelay = {}).also { it.cookie = "SAPISID=fixture" }
                    val batch =
                        PlayerClientDirector(tube, catalog("WEB_EMBEDDED_PLAYER"), UnavailableTokenProvider)
                            .fetchPlayerResponses("video", CONFIG, ContentHints())
                    assertTrue(batch.playableResponses.isEmpty())
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun resolveClientsAdapterPreservesCatalogTokenRules() =
        runBlocking {
            for (sabr in listOf(false, true)) {
                val client = client { if (sabr) SABR else DIRECT }
                try {
                    val tube =
                        InnerTube(client, retryDelay = {}).also {
                            it.visitorData = "visitor"
                            if (!sabr) it.cookie = "SAPISID=fixture"
                        }
                    val fallback =
                        object : ClientFallbackStrategy {
                            override fun resolveClients(hints: ContentHints) =
                                listOf(if (sabr) YouTubeClient.WEB_REMIX_SABR else YouTubeClient.WEB_REMIX)
                        }
                    val batch =
                        PlayerClientDirector(tube, fallback, if (sabr) UnavailableTokenProvider else tokens())
                            .fetchPlayerResponses("video", CONFIG, ContentHints())
                    assertEquals(1, batch.playableResponses.size)
                    assertEquals(!sabr, batch.playableResponses.single().usedPoToken)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun accountSwitchDuringHealthReportRejectsOldFailure() =
        runBlocking {
            val client = client { UNPLAYABLE }
            try {
                val tube = InnerTube(client, retryDelay = {}).also { it.cookie = "SAPISID=first" }
                var replacement = 0
                var switchAccount = true
                val health =
                    InMemoryClientHealthMonitor(sessionGeneration = { tube.sessionSnapshot().generation }, nowMs = {
                        if (switchAccount) tube.cookie = "SAPISID=replacement-${replacement++}"
                        0L
                    })
                val director = PlayerClientDirector(tube, catalog("WEB_REMIX_SABR"), UnavailableTokenProvider, health)
                repeat(2) { director.fetchPlayerResponses("video-$it", CONFIG, ContentHints()) }
                switchAccount = false
                assertEquals(0, health.scoreAdjustment("WEB_REMIX_SABR", ClientHealthScope.from(ContentHints(), true)))
            } finally {
                client.close()
            }
        }

    @Test
    fun hlsPlaybackNoncePreservesFragmentsAndEndpointRestrictions() =
        runBlocking {
            for (fallback in listOf(false, true)) {
                val response =
                    if (fallback) {
                        DIRECT.replace(
                            "?expire=",
                            "?n=source&expire=",
                        )
                    } else {
                        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[]}}"""
                    }
                val client =
                    client {
                        response.replace(
                            "\"adaptiveFormats\"",
                            "\"hlsManifestUrl\":\"https://manifest.googlevideo.com/api/manifest/hls_variant/test#fragment\"," +
                                "\"adaptiveFormats\"",
                        )
                    }
                try {
                    val nonce = "abcdefghijklmnop"
                    val stream =
                        assertNotNull(
                            extractor(InnerTube(client, retryDelay = {}), cipher = cipher { emptyList() })
                                .extract("video", ContentHints(isExplicit = true), clientPlaybackNonce = nonce),
                        )
                    assertEquals(nonce, Url(stream.audioUrl).parameters["cpn"])
                    assertTrue(stream.audioUrl.endsWith("#fragment"))
                    for (rejected in listOf(
                        "https://manifest.googlevideo.com/watch",
                        "https://example.test/manifest/hls/test",
                        "http://manifest.googlevideo.com/manifest/hls/test",
                        "https://manifest.googlevideo.com:8443/manifest/hls/test",
                        "https://user@manifest.googlevideo.com/manifest/hls/test",
                    )) {
                        assertEquals(rejected, appendHlsClientPlaybackNonce(rejected, nonce))
                    }
                    val existing = "https://manifest.googlevideo.com/manifest/hls/test?cpn=existing#fragment"
                    assertEquals(existing, appendHlsClientPlaybackNonce(existing, nonce))
                    assertEquals(existing, appendHlsClientPlaybackNonce(existing, "short"))
                } finally {
                    client.close()
                }
            }
        }

    private fun client(body: suspend (HttpRequestData) -> String) =
        HttpClient(
            MockEngine { request ->
                respond(body(request), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun catalog(vararg ids: String): ClientFallbackStrategy =
        object : ClientFallbackStrategy {
            private val selected = ids.map { checkNotNull(PlaybackClientCatalog.findManifest(it)) }.map { SelectedClient(it.client, it) }

            override fun resolveClients(hints: ContentHints) = selected.map { it.client }

            override fun selectClients(request: ClientSelectionRequest) = ClientSelectionResult(selected)
        }

    private fun legacy(vararg clients: YouTubeClient): ClientFallbackStrategy =
        object : ClientFallbackStrategy {
            override fun resolveClients(hints: ContentHints) = clients.toList()

            override fun selectClients(request: ClientSelectionRequest) = ClientSelectionResult(clients.map(::SelectedClient))
        }

    private fun tokens() =
        object : TokenProvider {
            override val capabilities =
                TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION))

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ) = PoTokenResult("fixture", "fixture", visitorData)
        }

    private fun cipher(process: suspend (List<Format>) -> List<Format>) =
        object : ExtractionCipherService {
            override suspend fun initialize() {}

            override suspend fun preloadPlayerCode(playerUrl: String) {}

            override suspend fun prewarmEjs() {}

            override suspend fun processFormats(
                playerUrl: String,
                formats: List<Format>,
            ) = process(formats)
        }

    private fun extractor(
        tube: InnerTube,
        fallback: ClientFallbackStrategy = catalog("VISIONOS"),
        provider: TokenProvider = UnavailableTokenProvider,
        cipher: ExtractionCipherService = cipher { it },
        now: () -> Instant = { Clock.System.now() },
    ) = InnerTubeExtractor(
        configParser =
            object : YtConfigParser {
                override suspend fun fetchConfig(
                    videoId: String,
                    useLoginCookies: Boolean,
                ) = CONFIG
            },
        clientDirector = PlayerClientDirector(tube, fallback, provider),
        cipherService = cipher,
        innerTube = tube,
        now = now,
    )

    private companion object {
        val CONFIG = PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, null, null)
        val AGE =
            """
            {"playabilityStatus":{"status":"AGE_CHECK_REQUIRED"}}
            """.trimIndent()
        val UNPLAYABLE =
            """
            {"playabilityStatus":{"status":"UNPLAYABLE"}}
            """.trimIndent()
        val DIRECT =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback?expire=9999999999","mimeType":"audio/webm","bitrate":128000}]}}
            """.trimIndent()
        val VIDEO =
            """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback?expire=1040","mimeType":"audio/webm","bitrate":128000},{"itag":136,"url":"https://r.googlevideo.com/videoplayback?expire=1020","mimeType":"video/mp4","width":1280,"height":720},{"itag":137,"url":"https://r.googlevideo.com/videoplayback?expire=1030","mimeType":"video/mp4","width":1920,"height":1080}]}}
            """.trimIndent()
        val SABR =
            """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","lengthSeconds":"60"},"streamingData":{"serverAbrStreamingUrl":"https://r.googlevideo.com/videoplayback?sabr=1","adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","bitrate":128000,"lastModified":"100","approxDurationMs":"60000"},{"itag":136,"mimeType":"video/mp4","width":1280,"height":720,"lastModified":"200"}]},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}}
            """.trimIndent()
    }
}
