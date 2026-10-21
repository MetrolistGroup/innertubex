package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.strategy.CapabilitySupport
import com.metrolist.innertubex.extraction.strategy.ClientContentCapabilities
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthContent
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.ClientPoTokenCapabilities
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.InMemoryClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class AgeGateRoutingTest {
    @Test
    fun explicitAgeStatusesSkipUnsupportedCandidatesAcrossPasses() =
        runBlocking {
            for (status in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED")) {
                var requests = 0
                val client =
                    client {
                        requests++
                        denial(status)
                    }
                try {
                    val strategy = FixedStrategy("WEB_REMIX", "VISIONOS", "TVHTML5_DOWNGRADED")
                    val director = PlayerClientDirector(InnerTube(client), strategy, UnavailableTokenProvider)
                    val budget = PlayerRequestBudget(20)
                    val first = director.fetchPlayerResponses("video", CONFIG, ContentHints(), requestBudget = budget)
                    val second =
                        director.fetchPlayerResponses(
                            "video",
                            CONFIG.copy(signatureTimestamp = 456),
                            ContentHints(),
                            requestBudget = budget,
                        )
                    assertTrue(budget.ageRestricted)
                    assertEquals(listOf("WEB_REMIX", "TVHTML5"), first.attempts.map { it.clientName })
                    assertEquals(listOf("WEB_REMIX", "TVHTML5"), second.attempts.map { it.clientName })
                    assertEquals(true, strategy.hints.last().isAgeRestricted)
                    assertEquals(4, requests)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun loginAndEnglishAgeReasonsDoNotChangeRouting() =
        runBlocking {
            for (status in listOf("LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED", "UNPLAYABLE")) {
                val client = client { denial(status, "Please confirm your age") }
                try {
                    val budget = PlayerRequestBudget(10)
                    val batch =
                        PlayerClientDirector(InnerTube(client), FixedStrategy("WEB_REMIX", "VISIONOS"), UnavailableTokenProvider)
                            .fetchPlayerResponses("video", CONFIG, ContentHints(), requestBudget = budget)
                    assertFalse(budget.ageRestricted)
                    assertEquals(2, batch.attempts.size)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun manualAgeUnsupportedOverrideStillRuns() =
        runBlocking {
            var requests = 0
            val client =
                client {
                    requests++
                    denial("AGE_CHECK_REQUIRED")
                }
            try {
                PlayerClientDirector(InnerTube(client), ContentAwareFallbackStrategy(), UnavailableTokenProvider)
                    .fetchPlayerResponses("video", CONFIG, ContentHints(isAgeRestricted = true, playbackClientOverrideId = "VISIONOS"))
                assertEquals(1, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun accountGatesDoNotPenalizeHealth() =
        runBlocking {
            for (status in listOf(
                "LOGIN_REQUIRED",
                "AGE_CHECK_REQUIRED",
                "AGE_VERIFICATION_REQUIRED",
                "CONTENT_CHECK_REQUIRED",
                "UNPLAYABLE",
            )) {
                val health = RecordingHealth()
                val client = client { denial(status) }
                try {
                    PlayerClientDirector(InnerTube(client), FixedStrategy("WEB_REMIX"), UnavailableTokenProvider, health)
                        .fetchPlayerResponses("video", CONFIG, ContentHints())
                    assertEquals(if (status == "UNPLAYABLE") listOf(ClientFailureKind.PLAYABILITY) else emptyList(), health.kinds)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun laterPlayabilityAndRequestFailuresUseLearnedAgeScope() =
        runBlocking {
            for (transportFailure in listOf(false, true)) {
                var requests = 0
                val client =
                    client {
                        requests++
                        if (requests == 1) {
                            denial("AGE_CHECK_REQUIRED")
                        } else {
                            if (transportFailure) error("synthetic transport failure")
                            denial("UNPLAYABLE")
                        }
                    }
                try {
                    val health = RecordingHealth()
                    PlayerClientDirector(
                        InnerTube(client, retryDelay = {}),
                        FixedStrategy("WEB_REMIX", "TVHTML5_DOWNGRADED"),
                        UnavailableTokenProvider,
                        health,
                    ).fetchPlayerResponses("video", CONFIG, ContentHints())
                    assertEquals(
                        listOf(if (transportFailure) ClientFailureKind.PLAYER_REQUEST else ClientFailureKind.PLAYABILITY),
                        health.kinds,
                    )
                    assertEquals(listOf(ClientHealthContent.AGE_RESTRICTED), health.scopes.map { it?.content })
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun learnedAgeSurvivesWatchAndCookiePassesWithoutEmbedFetch() =
        runBlocking {
            val client = client { denial("AGE_VERIFICATION_REQUIRED") }
            try {
                val innerTube = InnerTube(client).also { it.cookie = "SAPISID=synthetic" }
                val parser = CountingParser()
                val strategy = FixedStrategy("WEB_REMIX", "VISIONOS")
                assertFailsWith<StreamResolveException> { extractor(innerTube, parser, strategy).extract("video", ContentHints()) }
                assertEquals(listOf(false, true), parser.watchModes)
                assertEquals(0, parser.embeds)
                assertTrue(strategy.hints.size >= 3)
                assertTrue(strategy.hints.drop(1).all { it.isAgeRestricted == true })
            } finally {
                client.close()
            }
        }

    @Test
    fun hintedAgeSkipsAutomaticEmbedButPreservesManualEmbed() =
        runBlocking {
            for (manual in listOf(false, true)) {
                val client = client { denial("UNPLAYABLE") }
                try {
                    val parser = CountingParser()
                    val hints = ContentHints(isAgeRestricted = true, playbackClientOverrideId = "WEB_EMBEDDED_PLAYER".takeIf { manual })
                    assertFailsWith<StreamResolveException> {
                        extractor(InnerTube(client), parser, FixedStrategy("WEB_REMIX")).extract("video", hints)
                    }
                    assertEquals(if (manual) 1 else 0, parser.embeds)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun normalAndKidsEmbedFallbackDoNotInventAgeRestriction() =
        runBlocking {
            for (kids in listOf(false, true)) {
                val client = client { denial("LOGIN_REQUIRED") }
                try {
                    val parser = CountingParser()
                    val strategy = FixedStrategy("WEB_REMIX")
                    assertFailsWith<StreamResolveException> {
                        extractor(InnerTube(client), parser, strategy).extract("video", ContentHints(isKidsContent = kids))
                    }
                    assertEquals(1, parser.embeds)
                    assertEquals(null, strategy.hints.single { it.playbackClientOverrideId == "WEB_EMBEDDED_PLAYER" }.isAgeRestricted)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun spentRequestBudgetSkipsEmbedFetch() =
        runBlocking {
            var requests = 0
            val client =
                client {
                    requests++
                    denial("LOGIN_REQUIRED")
                }
            try {
                val parser = CountingParser()
                var pass = 0
                val strategy =
                    object : ClientFallbackStrategy {
                        override fun resolveClients(hints: ContentHints) =
                            (0 until 256).map { YouTubeClient.VISIONOS.copy(clientVersion = "${pass++}.$it") }
                    }
                val innerTube = InnerTube(client).also { it.cookie = "SAPISID=synthetic" }
                assertFailsWith<StreamResolveException> { extractor(innerTube, parser, strategy).extract("video", ContentHints()) }
                assertEquals(PlaybackClientCatalog.automaticManifests.size * 4 + 2, requests)
                assertEquals(0, parser.embeds)
                assertEquals(0, PlayerRequestBudget(1, Duration.ZERO).remaining, "the same guard covers an expired deadline")
            } finally {
                client.close()
            }
        }

    @Test
    fun cachedGateOnlyPassDoesNotRefreshWatchConfig() =
        runBlocking {
            for (status in listOf("LOGIN_REQUIRED", "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED")) {
                val client = client { denial(status) }
                try {
                    val parser = CountingParser()
                    val extractor = extractor(InnerTube(client), parser, FixedStrategy("WEB_REMIX"))
                    extractor.prewarm()
                    assertFailsWith<StreamResolveException> { extractor.extract("video", ContentHints(wantVideo = true)) }
                    assertEquals(listOf(false), parser.watchModes, status)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun cachedNonGateOrMalformedFailuresStillRefreshWatchConfig() =
        runBlocking {
            for (otherResponse in listOf(denial("UNPLAYABLE"), "{}")) {
                var requests = 0
                val client = client { if (++requests % 2 == 1) denial("LOGIN_REQUIRED") else otherResponse }
                try {
                    val parser = CountingParser()
                    val extractor = extractor(InnerTube(client), parser, FixedStrategy("WEB_REMIX", "WEB_SAFARI"))
                    extractor.prewarm()
                    assertFailsWith<StreamResolveException> { extractor.extract("video", ContentHints(wantVideo = true)) }
                    assertEquals(listOf(false, false), parser.watchModes)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun authenticatedHealthResetsForEveryAuthIdentityChangeButAnonymousHealthSurvives() {
        val client = client { error("no requests expected") }
        try {
            val innerTube = InnerTube(client).also { it.cookie = "SAPISID=synthetic" }
            val monitor = InMemoryClientHealthMonitor(sessionGeneration = { innerTube.sessionSnapshot().generation }, nowMs = { 0L })
            val authenticated = ClientHealthScope.from(ContentHints(), authenticated = true)
            val anonymous = authenticated.copy(authenticated = false)
            monitor.recordFailure("A", ClientFailureKind.PLAYER_REQUEST, anonymous)
            val changes: List<() -> Unit> =
                listOf(
                    { innerTube.cookie = "SAPISID=replacement" },
                    { innerTube.authUser = "1" },
                    { innerTube.dataSyncId = "synthetic-sync" },
                    { innerTube.cookie = null },
                )
            for (change in changes) {
                monitor.recordFailure("A", ClientFailureKind.PLAYER_REQUEST, authenticated)
                assertEquals(-18, monitor.scoreAdjustment("A", authenticated))
                change()
                assertEquals(0, monitor.scoreAdjustment("A", authenticated))
                assertEquals(-18, monitor.scoreAdjustment("A", anonymous))
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun anyAssertedUnsupportedCategoryWinsWithoutChangingSupportedPrecedence() {
        val hints = ContentHints(isUploaded = true, isLive = true, isAgeRestricted = true, isKidsContent = true, isExplicit = true)
        val supported =
            ClientContentCapabilities(
                normal = CapabilitySupport.UNSUPPORTED,
                uploads = CapabilitySupport.LIMITED,
                live = CapabilitySupport.SUPPORTED,
                ageRestricted = CapabilitySupport.SUPPORTED,
                kids = CapabilitySupport.SUPPORTED,
                explicit = CapabilitySupport.SUPPORTED,
            )
        assertEquals(CapabilitySupport.LIMITED, supported.supportFor(hints))
        for (unsupported in listOf(
            supported.copy(uploads = CapabilitySupport.UNSUPPORTED),
            supported.copy(live = CapabilitySupport.UNSUPPORTED),
            supported.copy(ageRestricted = CapabilitySupport.UNSUPPORTED),
            supported.copy(kids = CapabilitySupport.UNSUPPORTED),
            supported.copy(explicit = CapabilitySupport.UNSUPPORTED),
        )) {
            assertEquals(CapabilitySupport.UNSUPPORTED, unsupported.supportFor(hints))
        }
        assertEquals(
            CapabilitySupport.UNSUPPORTED,
            supported.copy(ageRestricted = CapabilitySupport.UNSUPPORTED).supportFor(ContentHints(isLive = true, isAgeRestricted = true)),
        )
        assertEquals(
            CapabilitySupport.SUPPORTED,
            supported.copy(ageRestricted = CapabilitySupport.UNSUPPORTED).supportFor(ContentHints(isLive = true)),
        )
    }

    private class FixedStrategy(
        vararg ids: String,
    ) : ClientFallbackStrategy {
        private val clients =
            ids.map { id ->
                checkNotNull(PlaybackClientCatalog.findManifest(id))
                    .copy(poTokens = ClientPoTokenCapabilities())
                    .let { SelectedClient(it.client, it) }
            }
        val hints = mutableListOf<ContentHints>()

        override fun resolveClients(hints: ContentHints) = clients.map { it.client }

        override fun selectClients(request: ClientSelectionRequest): ClientSelectionResult {
            hints += request.hints
            val override = request.hints.playbackClientOverrideId?.let(PlaybackClientCatalog::findManifest)
            return ClientSelectionResult(if (override == null) clients else listOf(SelectedClient(override.client, override)))
        }
    }

    private class RecordingHealth : ClientHealthMonitor {
        val kinds = mutableListOf<ClientFailureKind>()
        val scopes = mutableListOf<ClientHealthScope?>()

        override fun recordFailure(
            clientId: String,
            kind: ClientFailureKind,
            scope: ClientHealthScope?,
        ) {
            kinds += kind
            scopes += scope
        }
    }

    private class CountingParser : YtConfigParser {
        val watchModes = mutableListOf<Boolean>()
        var embeds = 0

        override suspend fun fetchConfig(
            videoId: String,
            useLoginCookies: Boolean,
        ): PlayerConfig {
            watchModes += useLoginCookies
            return CONFIG.copy(signatureTimestamp = 123 + watchModes.size)
        }

        override suspend fun fetchEmbeddedConfig(
            videoId: String,
            useLoginCookies: Boolean,
        ): PlayerConfig {
            embeds++
            return CONFIG.copy(encryptedHostFlags = "synthetic-flags")
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

    private fun extractor(
        innerTube: InnerTube,
        parser: YtConfigParser,
        strategy: ClientFallbackStrategy,
    ) = InnerTubeExtractor(parser, PlayerClientDirector(innerTube, strategy, UnavailableTokenProvider), NoCipher, innerTube)

    private fun client(body: () -> String) =
        HttpClient(MockEngine { respond(body(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private fun denial(
        status: String,
        reason: String = "synthetic denial",
    ) = """{"playabilityStatus":{"status":"$status","reason":"$reason"}}"""

    private companion object {
        val CONFIG = PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, "synthetic-visitor", null)
    }
}
