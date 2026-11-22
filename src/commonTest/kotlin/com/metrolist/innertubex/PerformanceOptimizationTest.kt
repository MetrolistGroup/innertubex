package com.metrolist.innertubex

import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractionCipherService
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.PlayerJsonInspection
import com.metrolist.innertubex.extraction.PlayerResponseJsonScanner
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.sabr.ProtoWriter
import com.metrolist.innertubex.sabr.SabrEvent
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.innertubex.sabr.SabrUmpProcessor
import com.metrolist.innertubex.sabr.UmpPartType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PerformanceOptimizationTest {
    @Test
    fun playerJsonScannerDetectsEscapedAndMixedCaseDrmWithoutFalsePositives() {
        assertEquals(
            PlayerJsonInspection.DRM_DETECTED,
            PlayerResponseJsonScanner.inspect(
                """{"streamingData":{"drm\u0046amilies":["WIDEVINE"],"adaptiveFormats":[]}}""",
            ),
        )
        assertEquals(
            PlayerJsonInspection.DRM_DETECTED,
            PlayerResponseJsonScanner.inspect(
                """{"streamingData":{"formats":[{"DrMpArAmS":"token"}]}}""",
            ),
        )
        assertEquals(
            PlayerJsonInspection.DRM_DETECTED,
            PlayerResponseJsonScanner.inspect(
                """{"streamingData":{"adaptiveFormats":[{"licenseInfos":[{}]}]}}""",
            ),
        )
        assertEquals(
            PlayerJsonInspection.CLEAN,
            PlayerResponseJsonScanner.inspect(
                """{"videoDetails":{"drmFamilies":["WIDEVINE"]},""" +
                    """"streamingData":{"drmParams":"","licenseInfos":[],"widevine":false,"playready":0,"fairplay":null,""" +
                    """"adaptiveFormats":[{"drmFamilies":[],"drmTrackType":"   ","drmParams":"0","widevine":"false"}]}}""",
            ),
        )
        assertEquals(
            PlayerJsonInspection.UNBOUNDED,
            PlayerResponseJsonScanner.inspect("{".repeat(65) + "}".repeat(65)),
        )
        assertEquals(
            PlayerJsonInspection.CLEAN,
            PlayerResponseJsonScanner.inspect("""{"streamingData":{"drmParams": invalid_token}}"""),
        )
        // Duplicate-key overwrite and first case-insensitive key precedence match JsonObject behavior.
        assertEquals(
            PlayerJsonInspection.CLEAN,
            PlayerResponseJsonScanner.inspect("""{"streamingData":{"widevine":true,"widevine":false}}"""),
        )
        assertEquals(
            PlayerJsonInspection.CLEAN,
            PlayerResponseJsonScanner.inspect("""{"streamingData":{"widevine":false,"WIDEVINE":true}}"""),
        )
        assertEquals(
            PlayerJsonInspection.CLEAN,
            PlayerResponseJsonScanner.inspect("""{"streamingData":{"widevine":true},"streamingData":{}}"""),
        )
        assertEquals(
            PlayerJsonInspection.CLEAN,
            PlayerResponseJsonScanner.inspect("""{"streamingData":{"formats":[{"widevine":true}],"formats":[]}}"""),
        )
        assertEquals(
            PlayerJsonInspection.DRM_DETECTED,
            PlayerResponseJsonScanner.inspect("""{"streamingData":{"widevine":false,"widevine":true}}"""),
        )
        assertEquals(
            PlayerJsonInspection.DRM_DETECTED,
            PlayerResponseJsonScanner.inspect("""{"streamingData":{"WIDEVINE":true,"widevine":false}}"""),
        )
    }

    @Test
    fun sabrUmpProcessorStreamsSelectedMediaAndSkipsUnselectedAcrossOneByteChunksAndResets() {
        val headerSelected =
            ProtoWriter()
                .apply {
                    int32(1, 7)
                    int32(3, 251)
                    int64(4, 100L)
                    int32(9, 1)
                    int64(14, 5L)
                }.toByteArray()
        val headerSkipped =
            ProtoWriter()
                .apply {
                    int32(1, 8)
                    int32(3, 137)
                    int64(4, 200L)
                    int32(9, 1)
                    int64(14, 4L)
                }.toByteArray()
        val stream =
            buildUmpPart(UmpPartType.MEDIA_HEADER, headerSelected) +
                buildUmpPart(UmpPartType.MEDIA_HEADER, headerSkipped) +
                buildUmpPart(UmpPartType.MEDIA, byteArrayOf(8, 9, 9, 9, 9)) +
                buildUmpPart(UmpPartType.MEDIA, byteArrayOf(7, 1, 2, 3, 4, 5)) +
                buildUmpPart(UmpPartType.MEDIA_END, byteArrayOf(8)) +
                buildUmpPart(UmpPartType.MEDIA_END, byteArrayOf(7))

        val processor = SabrUmpProcessor(shouldCollectMedia = { it.formatId.itag == 251 })
        repeat(2) {
            processor.resetForNextResponse()
            val events = mutableListOf<SabrEvent>()
            val single = ByteArray(1)
            for (byte in stream) {
                single[0] = byte
                processor.feed(single, 1) { events += it }
            }
            processor.finish()
            val segment = events.filterIsInstance<SabrEvent.Segment>().single().segment
            assertEquals(251, segment.header.formatId.itag)
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), segment.data)
            assertEquals(0, processor.retainedMediaBufferCount)
        }

        processor.resetForNextResponse()
        processor.feed(buildUmpPart(UmpPartType.MEDIA_HEADER, headerSelected))
        val truncatedMedia = byteArrayOf(UmpPartType.MEDIA.toByte(), 6, 7, 1, 2)
        processor.feed(truncatedMedia)
        assertFailsWith<SabrProtocolException> { processor.finish() }
    }

    @Test
    fun incrementalStopWhenPreservesUtf8SequenceAcrossBoundary() =
        runBlocking {
            val prefixBytes = "a".repeat(32 * 1024 - 1).encodeToByteArray()
            val multiByte = "é".encodeToByteArray() // 2 bytes: splits across the 32 KiB boundary
            val tailBytes = "MARKER".encodeToByteArray() + "b".repeat(40 * 1024).encodeToByteArray()
            val channel = ByteChannel()
            val writer =
                launch {
                    channel.writeFully(prefixBytes)
                    channel.writeFully(multiByte, 0, 1)
                    channel.flush()
                    channel.writeFully(multiByte, 1, multiByte.size)
                    channel.writeFully(tailBytes)
                    channel.flush()
                }
            val client = HttpClient(MockEngine { respond(channel, HttpStatusCode.OK) })
            try {
                val result =
                    withTimeout(5_000) {
                        client.prepareGet("https://example.test").execute { response ->
                            response.bodyAsTextLimited(1024 * 1024) { it.contains("MARKER") }
                        }
                    }
                assertTrue(result.contains("aéMARKER"), "UTF-8 character split at chunk boundary must not corrupt")
            } finally {
                writer.cancel()
                client.close()
            }
        }

    @Test
    fun homepageVisitorDataStopsEarlyWithoutWaitingForPageEof() =
        runBlocking {
            val channel = ByteChannel()
            val writer =
                launch {
                    channel.writeStringUtf8(
                        """<html><script>ytcfg.set({"VISITOR_DATA":"early-visitor"})</script>""" + " ".repeat(40 * 1024),
                    )
                    channel.flush()
                }
            val engine =
                MockEngine { request ->
                    if (request.url.host == "www.youtube.com") {
                        respond("error", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                    }
                }
            val client = HttpClient(engine)
            val innerTube = InnerTube(client, retryDelay = {})
            try {
                val visitor = withTimeout(5_000) { innerTube.fetchFreshVisitorData() }
                assertEquals("early-visitor", visitor)
            } finally {
                writer.cancel()
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun remoteConfigStoreAvoidsReadingCachedJsonOnSteadyStateHits() =
        runBlocking {
            var jsonReads = 0
            val table = """{"schemaVersion":1,"players":{"aaaaaaaa":{"sig":"A(1,2,INPUT)","nClass":"B","sts":20613}}}"""
            val repository =
                object : PlayerConfigRepository {
                    override val enabled = true
                    override val defaultSourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
                    override val sourceUrl = defaultSourceUrl
                    private var backingJson = table
                    override var cachedJson: String
                        get() {
                            jsonReads++
                            return backingJson
                        }
                        set(value) {
                            backingJson = value
                        }
                    override var cachedAtMs = 1_000L
                    override var cachedSourceUrl = defaultSourceUrl
                    override var cachedEtag = ""
                }
            val client = HttpClient(MockEngine { error("No network expected") })
            try {
                val store = RemotePlayerConfigStore(client, repository) { 2_000L }
                val playerUrl = "https://www.youtube.com/s/player/aaaaaaaa/player.js"
                assertNotNull(store.getConfig(playerUrl))
                val readsAfterLoad = jsonReads
                repeat(10) {
                    assertNotNull(store.getConfig(playerUrl))
                }
                assertEquals(readsAfterLoad, jsonReads, "Steady-state lookups must not re-read cachedJson from host storage")
            } finally {
                client.close()
            }
        }

    @Test
    fun cipherServiceReusesIsolatedClientAndRecoversAfterDispose() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(YouTubeCipherServiceTest.PLAYER_CODE) })
            val service = YouTubeCipherService(client)
            try {
                val url = "${YouTubeCipherServiceTest.MEDIA_URL}?n=abcdef"
                val expected = "${YouTubeCipherServiceTest.MEDIA_URL}?n=abcdef_done"
                assertEquals(expected, service.processNParameter(YouTubeCipherServiceTest.PLAYER_URL, url))
                service.dispose()
                assertEquals(expected, service.processNParameter(YouTubeCipherServiceTest.PLAYER_URL, url))
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun failedAudioLengthProbeCancelsStalledVideoProbeImmediately() =
        runBlocking {
            val videoProbeCancelled = CompletableDeferred<Unit>()
            val playerPayload =
                """
                {
                  "playabilityStatus": {"status": "OK"},
                  "videoDetails": {"videoId": "video123456", "lengthSeconds": "10"},
                  "streamingData": {
                    "adaptiveFormats": [
                      {"itag": 140, "url": "https://rr1.googlevideo.com/videoplayback?id=audio", "mimeType": "audio/mp4", "bitrate": 128000},
                      {"itag": 136, "url": "https://rr1.googlevideo.com/videoplayback?id=video", "mimeType": "video/mp4", "bitrate": 500000, "width": 1280, "height": 720}
                    ]
                  }
                }
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Head && request.url.parameters["id"] == "audio" -> {
                            respond("", HttpStatusCode.Forbidden)
                        }

                        request.method == HttpMethod.Head && request.url.parameters["id"] == "video" -> {
                            try {
                                awaitCancellation()
                            } finally {
                                videoProbeCancelled.complete(Unit)
                            }
                        }

                        else -> {
                            respond(playerPayload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    }
                }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val innerTube = InnerTube(client, retryDelay = {}).also { it.visitorData = "visitor" }
            val extractor =
                InnerTubeExtractor(
                    configParser =
                        object : YtConfigParser {
                            override suspend fun fetchConfig(
                                videoId: String,
                                useLoginCookies: Boolean,
                            ) = PlayerConfig("", null, "visitor", null)
                        },
                    cipherService =
                        object : ExtractionCipherService {
                            override suspend fun initialize() = Unit

                            override suspend fun prewarmEjs() = Unit

                            override suspend fun preloadPlayerCode(playerUrl: String) = Unit

                            override suspend fun processFormats(
                                playerUrl: String,
                                formats: List<com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format>,
                            ) = formats
                        },
                    innerTube = innerTube,
                    fallbackStrategy =
                        object : ClientFallbackStrategy {
                            override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.ANDROID_VR_1_65_10)
                        },
                )
            try {
                withTimeout(2_000) {
                    assertFailsWith<StreamResolveException> {
                        extractor.extract("video123456", ContentHints(wantVideo = true), audioQuality = AudioQuality.HIGH)
                    }
                    videoProbeCancelled.await()
                }
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun closedInnerTubeRejectsLateNoRedirectClientAccess() {
        val client = HttpClient(MockEngine { respond("") })
        val innerTube = InnerTube(client)
        try {
            innerTube.close()
            assertFailsWith<CancellationException> { innerTube.noRedirectClient }
        } finally {
            client.close()
        }
    }

    private fun buildUmpPart(
        type: Int,
        payload: ByteArray,
    ): ByteArray = encodeUmpVarint(type) + encodeUmpVarint(payload.size) + payload

    private fun encodeUmpVarint(value: Int): ByteArray =
        when {
            value < 0x80 -> byteArrayOf(value.toByte())
            value < 0x4000 -> byteArrayOf((0x80 or (value and 0x3f)).toByte(), (value ushr 6).toByte())
            else -> error("Test helper supports varints < 16384")
        }
}
