package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.PLAYER_RESPONSE_FIELD_MASK
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class ExtractionRound3Test {
    @Test
    fun localizedAgeSignalDecodesAndClassifiesWithoutChangingPublicConstructor() {
        val status = Json.decodeFromString<PlayerResponse>(AGE).playabilityStatus
        assertEquals(1, status.desktopLegacyAgeGateReason)
        assertEquals(StreamResolveException.Reason.AGE_RESTRICTED, classifyPlayabilityFailures(listOf(status.toFailure())))
        assertEquals(ClientFailureKind.PLAYABILITY, status.toFailure().category)
        assertTrue(PLAYER_RESPONSE_FIELD_MASK.contains("playabilityStatus(status,reason,desktopLegacyAgeGateReason)"))
        for (signal in listOf(null, 0, -1)) {
            val ambiguous = PlayerResponse.PlayabilityStatus("LOGIN_REQUIRED", "Zaloguj się")
            ambiguous.desktopLegacyAgeGateReason = signal
            assertNull(classifyPlayabilityFailures(listOf(ambiguous.toFailure())))
            assertEquals(ClientFailureKind.TOKEN, ambiguous.toFailure().category)
        }
        assertEquals(
            StreamResolveException.Reason.AGE_RESTRICTED,
            classifyPlayabilityFailures(listOf(PlayabilityFailure("LOGIN_REQUIRED", "Please confirm your age"))),
        )
    }

    @Test
    fun localizedAgeSignalLearnsRoutingAndDoesNotPenalizeHealth() =
        runBlocking {
            val fixture = Fixture { AGE }
            try {
                var healthFailures = 0
                val health =
                    object : ClientHealthMonitor {
                        override fun recordFailure(
                            clientId: String,
                            kind: ClientFailureKind,
                            scope: ClientHealthScope?,
                        ) {
                            healthFailures++
                        }
                    }
                val budget = PlayerRequestBudget(10)
                val batch =
                    PlayerClientDirector(fixture.tube, FixedStrategy("WEB_REMIX", "VISIONOS"), UnavailableTokenProvider, health)
                        .fetchPlayerResponses("video", CONFIG, ContentHints(), requestBudget = budget)
                assertTrue(budget.ageRestricted)
                assertTrue(batch.onlyAccountGateFailures)
                assertEquals(1, fixture.players)
                assertEquals(0, healthFailures)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun terminalVerdictsRepeatWithZeroRequestsUntilOriginalExpiry() =
        runBlocking {
            for (body in listOf(ERROR, AGE)) {
                val fixture = Fixture { body }
                try {
                    var clock = Clock.System.now()
                    val extractor = fixture.extractor(now = { clock })
                    fixture.restore(extractor)
                    val first = failure(extractor)
                    val requests = fixture.players
                    clock += 59.seconds
                    assertEquals(first.reason, failure(extractor).reason)
                    assertEquals(requests, fixture.players)
                    clock += 1.seconds
                    assertEquals(first.reason, failure(extractor).reason)
                    assertTrue(fixture.players > requests)
                    assertEquals(0, fixture.watches)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun terminalCacheIsLruBoundedAndPerExtractor() =
        runBlocking {
            val fixture = Fixture { ERROR }
            try {
                val extractor = fixture.extractor()
                repeat(256) { failure(extractor, "video$it") }
                assertEquals(256, fixture.players)
                failure(extractor, "video0")
                failure(extractor, "video256")
                failure(extractor, "video0")
                assertEquals(257, fixture.players)
                failure(extractor, "video1")
                assertEquals(258, fixture.players)
                failure(fixture.extractor(), "video0")
                assertEquals(259, fixture.players)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun terminalCacheSeparatesSessionVideoAndIntentAndBypassesManualClients() =
        runBlocking {
            val fixture = Fixture { ERROR }
            try {
                val extractor = fixture.extractor()
                fixture.restore(extractor)
                failure(extractor)
                failure(extractor)
                assertEquals(1, fixture.players)
                failure(extractor, "other")
                assertEquals(2, fixture.players)
                failure(extractor, hints = ContentHints(wantVideo = true))
                assertEquals(3, fixture.players)
                repeat(2) { failure(extractor, hints = ContentHints(playbackClientOverrideId = "WEB_REMIX")) }
                assertEquals(5, fixture.players)
                fixture.tube.cookie = "SAPISID=synthetic-new-session"
                failure(extractor)
                assertEquals(6, fixture.players)
                fixture.tube.cookie = null
                failure(extractor)
                assertEquals(7, fixture.players)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ageCacheDoesNotSurviveAuthenticationChange() =
        runBlocking {
            val fixture = Fixture { if (it.tube.hasSapCookieAuth()) SABR else AGE }
            try {
                val extractor = fixture.extractor(strategy = FixedStrategy("WEB_REMIX_SABR"))
                fixture.restore(extractor)
                assertEquals(StreamResolveException.Reason.AGE_RESTRICTED, failure(extractor).reason)
                val before = fixture.players
                failure(extractor)
                assertEquals(before, fixture.players)
                fixture.tube.cookie = "SAPISID=synthetic"
                assertNotNull(extractor.extract("video", ContentHints(isAgeRestricted = true)))
                assertEquals(before + 1, fixture.players)
                assertEquals(0, fixture.watches)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ambiguousMixedMalformedAndTransportFailuresAreNotCached() =
        runBlocking {
            for (kind in listOf("login", "unplayable", "mixed", "mixed-terminal", "malformed", "transport")) {
                val fixture =
                    Fixture {
                        when (kind) {
                            "login" -> denial("LOGIN_REQUIRED")
                            "unplayable" -> denial("UNPLAYABLE")
                            "mixed" -> if (it.players % 2 == 1) AGE else denial("UNPLAYABLE")
                            "mixed-terminal" -> if (it.players % 2 == 1) AGE else ERROR
                            "malformed" -> if (it.players % 2 == 1) ERROR else "{}"
                            else -> if (it.players % 2 == 1) ERROR else error("synthetic failure")
                        }
                    }
                try {
                    val extractor = fixture.extractor(strategy = FixedStrategy("WEB_REMIX", "TVHTML5_DOWNGRADED"))
                    fixture.restore(extractor)
                    failure(extractor)
                    val before = fixture.players
                    failure(extractor)
                    assertTrue(fixture.players > before, kind)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun excludedClientFailuresCannotPoisonUnrestrictedExtraction() =
        runBlocking {
            val fixture = Fixture { ERROR }
            try {
                val extractor = fixture.extractor()
                assertFailsWith<StreamResolveException> { extractor.extract("video", ContentHints(), excludedClients = setOf("VISIONOS")) }
                val before = fixture.players
                failure(extractor)
                assertTrue(fixture.players > before)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun authenticatedAgeAndPremiumUseRestoredPublicBootstrapWithoutWatchGet() =
        runBlocking {
            for (premium in listOf(false, true)) {
                val fixture = Fixture { SABR }
                try {
                    fixture.tube.cookie = "SAPISID=synthetic"
                    val extractor = fixture.extractor(strategy = FixedStrategy("WEB_REMIX_SABR"))
                    fixture.restore(extractor)
                    val hints = ContentHints(isAgeRestricted = true).let { if (premium) it.withPremium() else it }
                    assertNotNull(extractor.extract("video", hints, audioQuality = AudioQuality.HIGH))
                    assertEquals(1, fixture.players)
                    assertEquals(0, fixture.watches)
                    assertEquals(1, fixture.authenticatedPlayers)
                    assertEquals(0, fixture.writes)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun authenticatedAgeUsesInMemoryPublicBootstrapWithoutWatchGet() =
        runBlocking {
            val fixture = Fixture { SABR }
            try {
                val extractor = fixture.extractor(strategy = FixedStrategy("WEB_REMIX_SABR"))
                extractor.prewarm()
                assertEquals(1, fixture.watches)
                fixture.tube.cookie = "SAPISID=synthetic"
                assertNotNull(extractor.extract("video", ContentHints(isAgeRestricted = true)))
                assertEquals(1, fixture.watches)
                assertEquals(1, fixture.players)
                assertEquals(1, fixture.authenticatedPlayers)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun failedPublicBootstrapFetchesAuthenticatedWatchExactlyOnce() =
        runBlocking {
            val fixture = Fixture { if (it.watches == 0) "{}" else SABR }
            try {
                fixture.tube.cookie = "SAPISID=synthetic"
                val extractor = fixture.extractor(strategy = FixedStrategy("WEB_REMIX_SABR"))
                fixture.restore(extractor)
                assertNotNull(extractor.extract("video", ContentHints(isAgeRestricted = true)))
                assertEquals(1, fixture.watches)
                assertEquals(1, fixture.authenticatedWatches)
                assertEquals(2, fixture.players)
                assertEquals(2, fixture.authenticatedPlayers)
                assertEquals(0, fixture.writes)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun publicCipherFailureStillFetchesAuthenticatedWatchOnce() =
        runBlocking {
            val fixture = Fixture { SABR.replace("videoplayback\"", "videoplayback?n=synthetic\"") }
            try {
                fixture.tube.cookie = "SAPISID=synthetic"
                var cipherCalls = 0
                val cipher =
                    object : ExtractionCipherService by NoCipher {
                        override suspend fun processFormats(
                            playerUrl: String,
                            formats: List<PlayerResponse.StreamingData.Format>,
                        ): List<PlayerResponse.StreamingData.Format> {
                            if (++cipherCalls == 1) error("synthetic cipher failure")
                            return formats.map { it.copy(url = it.url?.substringBefore('?')) }
                        }
                    }
                val extractor = fixture.extractor(strategy = FixedStrategy("WEB_REMIX_SABR"), cipher = cipher)
                fixture.restore(extractor)
                assertNotNull(extractor.extract("video", ContentHints(isAgeRestricted = true)))
                assertEquals(1, fixture.watches)
                assertEquals(1, fixture.authenticatedWatches)
                assertEquals(2, fixture.players)
                assertEquals(2, cipherCalls)
                assertEquals(0, fixture.writes)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun mixedGateAndErrorDoesNotRefreshPublicBootstrapOrEnterNegativeCache() =
        runBlocking {
            val fixture = Fixture { if (it.players % 2 == 1) AGE else ERROR }
            try {
                fixture.tube.cookie = "SAPISID=synthetic"
                val extractor = fixture.extractor(strategy = FixedStrategy("WEB_REMIX", "TVHTML5_DOWNGRADED"))
                fixture.restore(extractor)
                repeat(2) { failure(extractor, hints = ContentHints(isAgeRestricted = true)) }
                assertEquals(4, fixture.players)
                assertEquals(0, fixture.watches)
                assertEquals(0, fixture.embeds)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun publicGateAndErrorPassesNeverFetchAuthenticatedWatch() =
        runBlocking {
            for (body in listOf(AGE, ERROR, denial("LOGIN_REQUIRED"))) {
                val fixture = Fixture { body }
                try {
                    fixture.tube.cookie = "SAPISID=synthetic"
                    val extractor = fixture.extractor()
                    fixture.restore(extractor)
                    failure(extractor, hints = ContentHints(isAgeRestricted = true))
                    assertEquals(1, fixture.players)
                    assertEquals(0, fixture.watches)
                    assertEquals(0, fixture.embeds)
                    assertEquals(0, fixture.writes)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun publicBootstrapNeedsValidSnapshotAndExistingVisitor() =
        runBlocking {
            for (missing in listOf("visitor", "expired", "invalid")) {
                val fixture = Fixture { SABR }
                try {
                    fixture.tube.cookie = "SAPISID=synthetic"
                    val extractor = fixture.extractor(strategy = FixedStrategy("WEB_REMIX_SABR"))
                    val saved = Clock.System.now() - if (missing == "expired") 20_000.seconds else 1.seconds
                    val snapshot = PlayerBootstrapSnapshot.of(CONFIG, fixture.tube.locale, saved.toEpochMilliseconds())!!.encode()
                    extractor.setPlayerBootstrapCache({ if (missing == "invalid") "{}" else snapshot }, { fixture.writes++ })
                    if (missing == "visitor") fixture.tube.visitorData = null
                    assertNotNull(extractor.extract("video", ContentHints(isAgeRestricted = true)))
                    assertEquals(1, fixture.watches, missing)
                    assertEquals(1, fixture.authenticatedWatches, missing)
                    assertEquals(1, fixture.players, missing)
                    assertEquals(0, fixture.writes, missing)
                } finally {
                    fixture.close()
                }
            }
        }

    private suspend fun failure(
        extractor: InnerTubeExtractor,
        video: String = "video",
        hints: ContentHints = ContentHints(),
    ) = assertFailsWith<StreamResolveException> { extractor.extract(video, hints) }

    private class FixedStrategy(
        vararg ids: String,
    ) : ClientFallbackStrategy {
        private val manifests = ids.map { checkNotNull(PlaybackClientCatalog.findManifest(it)) }

        override fun resolveClients(hints: ContentHints) = manifests.map { it.client }

        override fun selectClients(request: ClientSelectionRequest) = ClientSelectionResult(manifests.map { SelectedClient(it.client, it) })
    }

    private class Fixture(
        body: (Fixture) -> String,
    ) {
        var players = 0
        var watches = 0
        var embeds = 0
        var authenticatedPlayers = 0
        var authenticatedWatches = 0
        var writes = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    val response =
                        when {
                            request.url.encodedPath.endsWith("/player") -> {
                                players++
                                if (request.headers[HttpHeaders.Authorization] != null &&
                                    request.headers[HttpHeaders.Cookie] != null
                                ) {
                                    authenticatedPlayers++
                                }
                                assertEquals(PLAYER_RESPONSE_FIELD_MASK, request.headers["X-Goog-FieldMask"])
                                body(this@Fixture)
                            }

                            request.url.encodedPath == "/watch" -> {
                                watches++
                                if (request.headers[HttpHeaders.Cookie] != null) authenticatedWatches++
                                WATCH
                            }

                            request.url.encodedPath.startsWith("/embed/") -> {
                                embeds++
                                WATCH
                            }

                            else -> {
                                error("Unexpected mock request")
                            }
                        }
                    respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val tube = InnerTube(client, retryDelay = {}).also { it.visitorData = "synthetic-visitor" }

        fun extractor(
            strategy: ClientFallbackStrategy = FixedStrategy("WEB_REMIX"),
            now: () -> kotlin.time.Instant = {
                Clock.System.now()
            },
            cipher: ExtractionCipherService = NoCipher,
        ) = InnerTubeExtractor(
            YtConfigParserImpl(client, tube),
            PlayerClientDirector(tube, strategy, UnavailableTokenProvider),
            cipher,
            tube,
            now = now,
        )

        suspend fun restore(extractor: InnerTubeExtractor) {
            val snapshot = PlayerBootstrapSnapshot.of(CONFIG, tube.locale, Clock.System.now().toEpochMilliseconds() - 1_000)!!.encode()
            extractor.setPlayerBootstrapCache({ snapshot }, { writes++ })
        }

        fun close() = client.close()
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
        val CONFIG = PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, "synthetic-visitor", null)
        const val AGE = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Zaloguj się, aby potwierdzić swój wiek",
            "desktopLegacyAgeGateReason":1}}
        """
        const val ERROR = """{"playabilityStatus":{"status":"ERROR"}}"""
        const val WATCH = """
            {"PLAYER_JS_URL":"/s/player/test/base.js","STS":456,"visitorData":"synthetic-visitor",
            "INNERTUBE_CLIENT_VERSION":"2.0"}
        """
        const val SABR = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","lengthSeconds":"10"},
            "streamingData":{"serverAbrStreamingUrl":"https://r1.googlevideo.com/videoplayback","adaptiveFormats":[
            {"itag":140,"mimeType":"audio/mp4","bitrate":128000,"lastModified":"5","contentLength":"1000"},
            {"itag":160,"mimeType":"video/mp4","width":256,"height":144,"lastModified":"7"}]},
            "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}}
        """

        fun denial(status: String) = """{"playabilityStatus":{"status":"$status"}}"""
    }
}
