package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExtractionRound2Test {
    @Test
    fun gatedAudioKeepsDirectFirstWithAndWithoutPremium() {
        fun candidates(
            premium: Boolean,
            age: Boolean,
        ) = ContentAwareFallbackStrategy()
            .selectClients(
                ClientSelectionRequest(
                    ContentHints(isAgeRestricted = age).let { if (premium) it.withPremium() else it },
                    authenticated = true,
                    premium = premium,
                    availablePoTokenProviders = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    javaScriptRuntimeAvailable = true,
                ),
            ).candidates
            .map { it.manifest!!.id }
        val gated = candidates(premium = false, age = true)
        for (direct in listOf("WEB_REMIX", "WEB_CREATOR")) {
            assertTrue(gated.indexOf(direct) < gated.indexOf("WEB_REMIX_SABR"))
        }
        assertFalse("MWEB" in gated || "WEB_SAFARI" in gated)
        val premium = candidates(premium = true, age = true)
        assertTrue(premium.indexOf("WEB_REMIX") < premium.indexOf("WEB_REMIX_SABR"))
    }

    @Test
    fun hintedAndLearnedAgeKeepTokenBackedDirectFirst() =
        runBlocking {
            for (hinted in listOf(false, true)) {
                var requests = 0
                val client = client { if (++requests == 1 && !hinted) denial("AGE_CHECK_REQUIRED") else DIRECT }
                try {
                    val tube = session(client)
                    val provider = CountingTokens()
                    val extractor =
                        extractor(tube, Parser(), FixedStrategy("VISIONOS", "WEB_REMIX", "WEB_CREATOR", "WEB_REMIX_SABR"), provider)
                    val stream = assertNotNull(extractor.extract("video", ContentHints(isAgeRestricted = hinted, isExplicit = true)))
                    assertEquals("WEB_REMIX__po", stream.profileId)
                    assertEquals(if (hinted) 1 else 2, requests)
                    assertEquals(1, provider.calls)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun sabrAndDirectFallbackReuseOneWireRequest() =
        runBlocking {
            var requests = 0
            val client =
                client {
                    requests++
                    DIRECT
                }
            try {
                val provider = CountingTokens()
                val stream =
                    extractor(session(client), Parser(), FixedStrategy("WEB_REMIX", "WEB_REMIX_SABR"), provider)
                        .extract("video", ContentHints(isAgeRestricted = true))
                assertEquals("WEB_REMIX__po", assertNotNull(stream).profileId)
                assertEquals(1, requests)
                assertEquals(1, provider.calls)
            } finally {
                client.close()
            }
        }

    @Test
    fun explicitPremiumDirectPlaybackDoesNotMint() =
        runBlocking {
            val client = client { DIRECT }
            try {
                val provider = CountingTokens()
                val stream =
                    extractor(session(client), Parser(), FixedStrategy("WEB_REMIX"), provider).extract(
                        "video",
                        ContentHints(isExplicit = true).withPremium(),
                        audioQuality = AudioQuality.HIGH,
                    )
                assertEquals("WEB_REMIX__nopo", assertNotNull(stream).profileId)
                assertEquals(0, provider.calls)
            } finally {
                client.close()
            }
        }

    @Test
    fun failedClientDoesNotRestartInflightMint() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val provider =
                CountingTokens {
                    started.complete(Unit)
                    release.await()
                }
            var requests = 0
            val client =
                client {
                    started.await()
                    if (++requests == 1) {
                        denial("UNPLAYABLE")
                    } else {
                        release.complete(Unit)
                        DIRECT
                    }
                }
            try {
                val tube = session(client)
                val batch =
                    PlayerClientDirector(tube, FixedStrategy("WEB_REMIX", "WEB_CREATOR"), provider)
                        .fetchPlayerResponses("video", CONFIG, ContentHints())
                assertEquals(1, batch.playableResponses.size)
                assertEquals(2, requests)
                assertEquals(1, provider.calls)
            } finally {
                client.close()
            }
        }

    @Test
    fun unusedInflightMintIsCancelledAtExtractionEnd() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val provider =
                CountingTokens {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            val client =
                client {
                    started.await()
                    denial("ERROR")
                }
            try {
                assertFailsWith<StreamResolveException> {
                    extractor(session(client), Parser(), FixedStrategy("WEB_REMIX", "WEB_CREATOR"), provider)
                        .extract("video", ContentHints(isAgeRestricted = true))
                }
                assertTrue(cancelled.isCompleted)
                assertEquals(1, provider.calls)
            } finally {
                client.close()
            }
        }

    @Test
    fun sabrRankingSkipsInvalidHigherBitrateAudio() =
        runBlocking {
            for (invalid in listOf("", "\"lastModified\":\"0\",", "\"lastModified\":\"5\",\"approxDurationMs\":\"0\",")) {
                val malformed = """{"itag":141,"mimeType":"audio/mp4","bitrate":256000,$invalid"contentLength":"1000"},"""
                val client = client { SABR.replace("\"adaptiveFormats\":[", "\"adaptiveFormats\":[$malformed") }
                try {
                    val stream =
                        extractor(session(client), Parser(), FixedStrategy("WEB_REMIX_SABR"))
                            .extract("video", ContentHints(isAgeRestricted = true))
                    assertEquals(140, assertNotNull(stream).itag)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun errorOnlyConfiguredPassStopsWithoutRefreshEmbedOrKidsRequests() =
        runBlocking {
            for (cached in listOf(false, true)) {
                var requests = 0
                val client =
                    client {
                        requests++
                        denial("ERROR")
                    }
                try {
                    val parser = Parser()
                    val tube = InnerTube(client).also { it.visitorData = "synthetic-visitor" }
                    val extractor = extractor(tube, parser, FixedStrategy("WEB_REMIX", "WEB_CREATOR"))
                    if (cached) extractor.prewarm()
                    val failure = assertFailsWith<StreamResolveException> { extractor.extract("video", ContentHints(wantVideo = cached)) }
                    assertEquals(StreamResolveException.Reason.UNAVAILABLE, failure.reason)
                    assertEquals(if (cached) 2 else 4, requests)
                    assertEquals(1, parser.watches)
                    assertEquals(0, parser.embeds)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun unplayableStillRefreshesAndTriesEmbedAndKids() =
        runBlocking {
            val client = client { denial("UNPLAYABLE") }
            try {
                val parser = Parser()
                val extractor = extractor(InnerTube(client), parser, FixedStrategy("WEB_REMIX"))
                extractor.prewarm()
                assertFailsWith<StreamResolveException> { extractor.extract("video", ContentHints()) }
                assertEquals(2, parser.watches)
                assertEquals(1, parser.embeds)
            } finally {
                client.close()
            }
        }

    @Test
    fun extractionDeadlineBoundsWatchEmbedCipherAndPlayerWithoutHealthPenalty() =
        runBlocking {
            for (stage in listOf("watch", "embed", "cipher", "player")) {
                val client =
                    client {
                        if (stage ==
                            "player"
                        ) {
                            awaitCancellation()
                        } else {
                            DIRECT.replace("videoplayback?", "videoplayback?n=x&")
                        }
                    }
                try {
                    val parser = Parser { if (stage == "watch" || stage == "embed") awaitCancellation() }
                    val health = RecordingHealth()
                    val cipher =
                        object : ExtractionCipherService by NoCipher {
                            override suspend fun processFormats(
                                playerUrl: String,
                                formats: List<PlayerResponse.StreamingData.Format>,
                            ): List<PlayerResponse.StreamingData.Format> = awaitCancellation()
                        }
                    val extractor =
                        extractor(
                            InnerTube(client),
                            parser,
                            FixedStrategy("VISIONOS"),
                            cipher = cipher,
                            health = health,
                            budget = 150.milliseconds,
                        )
                    val failure =
                        withTimeout(2.seconds) {
                            assertFailsWith<StreamResolveException> {
                                extractor.extract(
                                    "video",
                                    ContentHints(
                                        playbackClientOverrideId =
                                            if (stage ==
                                                "embed"
                                            ) {
                                                "WEB_EMBEDDED_PLAYER"
                                            } else {
                                                "VISIONOS"
                                            },
                                    ),
                                )
                            }
                        }
                    assertEquals(StreamResolveException.Reason.NO_PLAYABLE_STREAM, failure.reason, stage)
                    assertTrue(failure.cause is PlayerRequestBudgetExhaustedException, stage)
                    assertEquals(0, health.failures, stage)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun extractionDeadlineCancelsPlayerScriptRetryDelay() =
        runBlocking {
            var downloads = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val script = request.url.encodedPath.endsWith(".js")
                        if (script) downloads++
                        respond(
                            if (script) "unavailable" else DIRECT.replace("videoplayback?", "videoplayback?n=x&"),
                            if (script) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val cipher = YouTubeCipherService(client)
            try {
                val failure =
                    assertFailsWith<StreamResolveException> {
                        extractor(InnerTube(client), Parser(), FixedStrategy("VISIONOS"), cipher = cipher, budget = 200.milliseconds)
                            .extract("video", ContentHints(playbackClientOverrideId = "VISIONOS"))
                    }
                assertTrue(failure.cause is PlayerRequestBudgetExhaustedException)
                assertEquals(1, downloads)
            } finally {
                cipher.dispose()
                client.close()
            }
        }

    @Test
    fun configMutexWaitSharesDeadlineAndCallerCancellationStaysCancellation() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val client = client { DIRECT }
            try {
                val parser =
                    Parser {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                val extractor = extractor(InnerTube(client), parser, FixedStrategy("VISIONOS"), budget = 150.milliseconds)
                val prewarm = launch { extractor.prewarm() }
                started.await()
                try {
                    val failure =
                        assertFailsWith<StreamResolveException> {
                            extractor.extract("video", ContentHints(playbackClientOverrideId = "VISIONOS"))
                        }
                    assertTrue(failure.cause is PlayerRequestBudgetExhaustedException)
                    assertEquals(1, parser.watches)
                    val extraction =
                        launch(
                            start = CoroutineStart.UNDISPATCHED,
                        ) { extractor.extract("video", ContentHints(playbackClientOverrideId = "VISIONOS")) }
                    extraction.cancelAndJoin()
                    assertTrue(extraction.isCancelled)
                } finally {
                    prewarm.cancelAndJoin()
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun prewarmRestoresPersistedBootstrapWithoutWatchRequest() =
        runBlocking {
            val client = client { error("no player request expected") }
            try {
                val tube = InnerTube(client).also { it.visitorData = "synthetic-visitor" }
                val parser = Parser()
                val extractor = extractor(tube, parser, FixedStrategy("VISIONOS"))
                extractor.setPlayerBootstrapCache({ snapshot(tube) }, { error("no write expected") })
                extractor.prewarm()
                assertEquals(0, parser.watches)
            } finally {
                client.close()
            }
        }

    @Test
    fun concurrentPrewarmsShareRestoreWithoutDownloadingWatchConfig() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client = client { error("no player request expected") }
            try {
                val tube = InnerTube(client).also { it.visitorData = "synthetic-visitor" }
                val parser = Parser()
                val extractor = extractor(tube, parser, FixedStrategy("VISIONOS"))
                var reads = 0
                extractor.setPlayerBootstrapCache({
                    reads++
                    started.complete(Unit)
                    release.await()
                    snapshot(tube)
                }, {})
                val first = async { extractor.prewarm() }
                started.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { extractor.prewarm() }
                release.complete(Unit)
                first.await()
                second.await()
                assertEquals(1, reads)
                assertEquals(0, parser.watches)
            } finally {
                client.close()
            }
        }

    @Test
    fun slowRestoreDoesNotBlockAuthenticatedCacheHit() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val client = client { DIRECT }
            try {
                val tube = session(client)
                val parser = Parser()
                val extractor = extractor(tube, parser, FixedStrategy("WEB_REMIX"))
                val hints = ContentHints(isAgeRestricted = true).withPremium()
                assertNotNull(extractor.extract("video", hints))
                extractor.setPlayerBootstrapCache({
                    started.complete(Unit)
                    awaitCancellation()
                }, {})
                val restore = launch { extractor.prewarm() }
                started.await()
                try {
                    withTimeout(500.milliseconds) { assertNotNull(extractor.extract("video", hints)) }
                    assertEquals(1, parser.watches)
                } finally {
                    restore.cancelAndJoin()
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun restoreTimeoutIsAMissAndReplacedStoreCannotPublishOldSnapshot() =
        runBlocking {
            for (replaceStore in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val client = client { error("no player request expected") }
                try {
                    val tube = InnerTube(client).also { it.visitorData = "synthetic-visitor" }
                    val parser = Parser()
                    val extractor = extractor(tube, parser, FixedStrategy("VISIONOS"))
                    extractor.setPlayerBootstrapCache({
                        started.complete(Unit)
                        release.await()
                        snapshot(tube)
                    }, {})
                    val warming = async { extractor.prewarm() }
                    started.await()
                    if (replaceStore) {
                        extractor.setPlayerBootstrapCache({ null }, {})
                        release.complete(Unit)
                    }
                    withTimeout(2.seconds) { warming.await() }
                    assertEquals(1, parser.watches)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun stalledWriterKeepsOnlyLatestPendingBootstrap() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val latest = CompletableDeferred<String>()
            val writerJob = SupervisorJob()
            val writerScope = CoroutineScope(coroutineContext + writerJob)
            val client = client { error("no player request expected") }
            try {
                val tube = InnerTube(client).also { it.visitorData = "synthetic-visitor" }
                val parser = Parser()
                val extractor =
                    InnerTubeExtractor(
                        parser,
                        PlayerClientDirector(tube, FixedStrategy("VISIONOS"), UnavailableTokenProvider),
                        NoCipher,
                        tube,
                        bootstrapWriteScope = writerScope,
                    )
                var writes = 0
                extractor.setPlayerBootstrapCache({ null }) { value ->
                    writes++
                    if (writes == 1) {
                        started.complete(Unit)
                        release.await()
                    } else {
                        latest.complete(value)
                    }
                }
                extractor.prewarm()
                started.await()
                repeat(30) { index ->
                    tube.locale = tube.locale.copy(hl = "test-$index")
                    extractor.prewarm()
                }
                assertEquals(1, writes)
                assertEquals(1, writerJob.children.count())
                release.complete(Unit)
                val stored = withTimeout(2.seconds) { latest.await() }
                assertEquals("test-29", PlayerBootstrapSnapshot.decode(stored)!!.hl)
                assertEquals(2, writes)
            } finally {
                release.complete(Unit)
                writerJob.cancelAndJoin()
                client.close()
            }
        }

    private class FixedStrategy(
        vararg ids: String,
    ) : ClientFallbackStrategy {
        private val manifests = ids.map { checkNotNull(PlaybackClientCatalog.findManifest(it)) }

        override fun resolveClients(hints: ContentHints) = manifests.map { it.client }

        override fun selectClients(request: ClientSelectionRequest) = ClientSelectionResult(manifests.map { SelectedClient(it.client, it) })
    }

    private class CountingTokens(
        private val beforeMint: suspend () -> Unit = {},
    ) : TokenProvider {
        var calls = 0
        override val capabilities = TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD))

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): PoTokenResult {
            calls++
            beforeMint()
            return PoTokenResult("AQID", "AQID", visitorData)
        }
    }

    private class RecordingHealth : ClientHealthMonitor {
        var failures = 0

        override fun recordFailure(
            clientId: String,
            kind: ClientFailureKind,
            scope: ClientHealthScope?,
        ) {
            failures++
        }
    }

    private class Parser(
        private val beforeFetch: suspend () -> Unit = {},
    ) : YtConfigParser {
        var watches = 0
        var embeds = 0

        override suspend fun fetchConfig(
            videoId: String,
            useLoginCookies: Boolean,
        ): PlayerConfig {
            watches++
            beforeFetch()
            return CONFIG.copy(signatureTimestamp = 123 + watches)
        }

        override suspend fun fetchEmbeddedConfig(
            videoId: String,
            useLoginCookies: Boolean,
        ): PlayerConfig {
            embeds++
            beforeFetch()
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
        tube: InnerTube,
        parser: YtConfigParser,
        strategy: ClientFallbackStrategy,
        provider: TokenProvider = UnavailableTokenProvider,
        cipher: ExtractionCipherService = NoCipher,
        health: ClientHealthMonitor = ClientHealthMonitor.NONE,
        budget: Duration = 30.seconds,
    ) = InnerTubeExtractor(
        parser,
        PlayerClientDirector(tube, strategy, provider, health),
        cipher,
        tube,
        provider,
        extractionTimeBudgetMs = budget.inWholeMilliseconds,
    )

    private fun client(body: suspend () -> String) =
        HttpClient(
            MockEngine {
                respond(body(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun session(client: HttpClient) =
        InnerTube(client).also {
            it.visitorData = "synthetic-visitor"
            it.cookie = "SAPISID=synthetic"
        }

    private fun snapshot(tube: InnerTube) =
        PlayerBootstrapSnapshot.of(CONFIG, tube.locale, Clock.System.now().toEpochMilliseconds() - 60_000)!!.encode()

    private fun denial(status: String) = """{"playabilityStatus":{"status":"$status"}}"""

    private companion object {
        val CONFIG = PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, "synthetic-visitor", null)
        const val DIRECT = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
            {"itag":140,"mimeType":"audio/mp4","bitrate":128000,"contentLength":"1000",
            "url":"https://r1.googlevideo.com/videoplayback?id=synthetic"}]}}
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
