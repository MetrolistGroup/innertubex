package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.InMemoryClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.sabr.hasSabrBootstrapPrerequisites
import com.metrolist.innertubex.sabr.toSabrBootstrap
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TestTimeSource

class ExtractionLatencyTest {
    @Test
    fun transportVariantsShareOneWireIdentity() {
        assertEquals(YouTubeClient.WEB_REMIX.wireIdentity(), YouTubeClient.WEB_REMIX_SABR.wireIdentity())
        assertNotEquals(YouTubeClient.WEB_REMIX.wireIdentity(), YouTubeClient.VISIONOS.wireIdentity())
    }

    @Test
    fun defaultHealthMonitorPenalizesRepeatedFailuresAndResetsOnSuccess() {
        val monitor = InMemoryClientHealthMonitor { 0L }
        monitor.recordFailure("A", ClientFailureKind.PLAYER_REQUEST)
        val afterOne = monitor.scoreAdjustment("A")
        monitor.recordFailure("A", ClientFailureKind.PLAYER_REQUEST)
        assertTrue(monitor.scoreAdjustment("A") < afterOne - 500, "second failure starts a cooldown")
        monitor.recordSuccess("A")
        assertEquals(0, monitor.scoreAdjustment("A"))
        assertEquals(0, monitor.scoreAdjustment("B"))
    }

    @Test
    fun defaultHealthPenaltiesFadeWithoutSuccessReports() {
        var now = 0L
        val monitor = InMemoryClientHealthMonitor { now }
        repeat(3) { monitor.recordFailure("A", ClientFailureKind.PLAYER_REQUEST) }
        assertTrue(monitor.scoreAdjustment("A") < 0)
        now += 11 * 60 * 1000L
        assertEquals(0, monitor.scoreAdjustment("A"))
        monitor.recordFailure("A", ClientFailureKind.PLAYER_REQUEST)
        assertEquals(-18, monitor.scoreAdjustment("A"), "an old streak does not carry into a new failure")
    }

    @Test
    fun unhealthyDirectClientsFallBehindHealthySabr() {
        val healthy = ContentAwareFallbackStrategy().selectClients(ClientSelectionRequest(ContentHints(), authenticated = false)).candidates
        val leadingDirect = healthy.first { !it.client.useSabr }
        val penalized =
            ContentAwareFallbackStrategy(
                object : ClientHealthMonitor {
                    override fun scoreAdjustment(
                        clientId: String,
                        scope: ClientHealthScope?,
                    ) = if (clientId == leadingDirect.manifest?.id) -1_000 else 0
                },
            ).selectClients(ClientSelectionRequest(ContentHints(), authenticated = false)).candidates
        val sabrIndex = penalized.indexOfFirst { it.client.useSabr }
        val demotedIndex = penalized.indexOfFirst { it.manifest?.id == leadingDirect.manifest?.id }
        assertTrue(sabrIndex in 0 until demotedIndex, "a cooled-down direct client must not precede healthy SABR")
    }

    @Test
    fun requestBudgetStopsOnceExtractionTimeIsSpent() {
        val clock = TestTimeSource()
        val budget = PlayerRequestBudget(10, totalTime = 1.seconds, timeSource = clock)
        assertEquals(5.milliseconds, budget.timeLeft(5.milliseconds))
        assertEquals(5.milliseconds, budget.timeLeftOrNull(5.milliseconds))
        clock += 999.milliseconds
        assertEquals(1.milliseconds, budget.timeLeftOrNull(5.milliseconds))
        clock += 1.milliseconds
        assertNull(budget.timeLeftOrNull(5.milliseconds))
        assertEquals(0, budget.remaining)
        assertFailsWith<PlayerRequestBudgetExhaustedException> { budget.timeLeft(5.milliseconds) }
        assertFailsWith<PlayerRequestBudgetExhaustedException> { budget.consume() }
    }

    @Test
    fun optionalTimeBudgetReadsTheDeadlineOnce() {
        var now = 0L
        var advancing = false
        val clock =
            object : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
                override fun read(): Long = now.also { if (advancing) now++ }
            }
        val budget = PlayerRequestBudget(1, totalTime = 2.milliseconds, timeSource = clock)
        now = 1L
        advancing = true
        assertEquals(1.milliseconds, budget.timeLeftOrNull(5.milliseconds))
        assertNull(budget.timeLeftOrNull(5.milliseconds))
    }

    @Test
    fun oversizedFormatListsAreRejectedWhileDecoding() {
        val formats = (1..2_049).joinToString(",") { "{}" }
        val payload = """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[$formats]}}"""
        assertFailsWith<SerializationException> { Json { ignoreUnknownKeys = true }.decodeFromString<PlayerResponse>(payload) }
        val accepted = payload.replace("[$formats]", "[{},{}]")
        assertEquals(
            2,
            Json { ignoreUnknownKeys = true }
                .decodeFromString<PlayerResponse>(accepted)
                .streamingData
                ?.adaptiveFormats
                ?.size,
        )
    }

    @Test
    fun sabrBootstrapSkipsAnUnidentifiableSmallestDiscardRendition() {
        val response =
            Json { ignoreUnknownKeys = true }.decodeFromString<PlayerResponse>(
                """
                {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"v","lengthSeconds":"10"},
                "streamingData":{"serverAbrStreamingUrl":"https://r1---sn.googlevideo.com/videoplayback",
                "adaptiveFormats":[
                  {"itag":140,"mimeType":"audio/mp4","lastModified":"5"},
                  {"itag":160,"mimeType":"video/mp4","width":256,"height":144},
                  {"itag":133,"mimeType":"video/mp4","width":426,"height":240,"lastModified":"7"}]},
                "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}}
                """.trimIndent(),
            )
        assertTrue(response.hasSabrBootstrapPrerequisites())
        val bootstrap = response.toSabrBootstrap(3, "1", response.streamingData!!.adaptiveFormats.first())
        assertEquals(133, bootstrap.discardVideoFormat.itag)
    }

    @Test
    fun failedPlayerStatusDoesNotWaitForItsBody() =
        runBlocking {
            val engine =
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    // Leave the body open: status handling must not wait for EOF.
                    respond(channel, HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val innerTube =
                InnerTube(HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }, retryDelay = {})
            val error =
                withTimeout(2.seconds) {
                    assertFailsWith<InnerTubeHttpException> {
                        innerTube.playerTextWithSessionBound(
                            YouTubeClient.VISIONOS,
                            "video",
                            null,
                            null,
                            innerTube.sessionSnapshot(),
                            null,
                            4096,
                        )
                    }
                }
            assertEquals(503, error.status.value)
        }

    @Test
    fun mintedTokenIsReusedByLaterClientsInOneExtraction() =
        runBlocking {
            var mints = 0
            val innerTube =
                InnerTube(
                    HttpClient(
                        MockEngine {
                            // Unplayable, so every client falls through to the next one.
                            respond(
                                """{"playabilityStatus":{"status":"UNPLAYABLE"}}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
                    retryDelay = {},
                ).also { it.visitorData = "visitor" }
            val provider =
                object : TokenProvider {
                    override val capabilities =
                        TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION), true)

                    override suspend fun getPoToken(
                        videoId: String,
                        visitorData: String,
                        cookie: String?,
                    ): PoTokenResult {
                        mints++
                        return PoTokenResult("player", "stream", visitorData)
                    }
                }
            val manifests = listOf("TVHTML5_SIMPLY", "TVHTML5_SIMPLY_SABR").map { checkNotNull(PlaybackClientCatalog.findManifest(it)) }
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
            assertEquals(1, mints)
        }
}
