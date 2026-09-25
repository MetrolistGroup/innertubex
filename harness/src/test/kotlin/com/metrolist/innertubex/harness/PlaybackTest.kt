package com.metrolist.innertubex.harness

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.sabr.SabrChunk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTest {
    private fun stream(
        url: String,
        size: Long,
        mime: String,
    ) = ExtractedStream(
        videoId = "AAAAAAAAAAA",
        audioUrl = url,
        headers = emptyMap(),
        loudnessDb = null,
        expiresAt = null,
        contentLengthBytes = size,
        itag = 251,
        mimeType = mime,
        codecs = "opus",
        bitrate = 128000,
        sampleRate = 48000,
        clientName = "test",
        profileId = "WEB_REMIX__nopo",
        requireBoundedRange = false,
        rangeChunkSizeBytes = 1_048_576,
    )

    private fun hasFfmpeg() =
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .any { File(it, "ffmpeg").canExecute() }

    @Test fun recoveredSabrRateLimitsAreNotTerminal() {
        fun response(
            endpoint: String,
            code: Int,
        ) = buildJsonObject {
            put("endpoint", endpoint)
            put("status", code)
        }
        assertFalse(terminalMedia4xx(listOf(response("sabr", 429)), "SABR", "pass"))
        assertFalse(terminalMedia4xx(listOf(response("sabr", 408), response("sabr", 425)), "SABR", "pass"))
        assertTrue(terminalMedia4xx(listOf(response("sabr", 403)), "SABR", "pass"))
        assertTrue(terminalMedia4xx(listOf(response("media", 429)), "DIRECT", "pass"))
        assertTrue(terminalMedia4xx(listOf(response("hls_playlist", 403)), "HLS", "pass"))
        assertFalse(terminalMedia4xx(listOf(response("sabr", 403)), "SABR", "fail"))
    }

    @Test fun responseBudgetCountsPartialBodiesAtTheTransport() {
        val server =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/videoplayback") { exchange ->
            exchange.requestBody.close()
            exchange.sendResponseHeaders(200, 32_768)
            runCatching { exchange.responseBody.use { it.write(ByteArray(32_768)) } }
        }
        server.start()
        val budget = MediaBudget(16_384)
        val capture = Capture().also { it.sabrBudget = budget }
        val client =
            okhttp3.OkHttpClient
                .Builder()
                .addInterceptor(capture)
                .build()
        try {
            val request =
                okhttp3.Request
                    .Builder()
                    .url("http://127.0.0.1:${server.address.port}/videoplayback")
                    .post(ByteArray(0).toRequestBody())
                    .build()
            client.newCall(request).execute().use { response ->
                response.body.source().readByte()
                assertTrue(budget.bytes > 0) // Charged before EOF, including framing/unselected data.
                assertFailsWith<IllegalStateException> { response.body.bytes() }
                assertTrue(budget.bytes <= 16_384)
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.stop(0)
        }
    }

    @Test fun firstPcmDeadline() =
        runBlocking {
            coroutineScope {
                val running = async { delay(100) }
                try {
                    assertFailsWith<IllegalStateException> {
                        awaitFirstPcm(System.nanoTime(), running, CompletableDeferred(), limitMs = 20)
                    }
                } finally {
                    running.cancel()
                }
                val first = CompletableDeferred<Unit>()
                val continued =
                    async {
                        first.complete(Unit)
                        delay(40)
                        7
                    }
                assertEquals(7, awaitFirstPcm(System.nanoTime(), continued, first, limitMs = 20))
            }
            Unit
        }

    @Test fun defaultTimeline() {
        val plan = PlaybackPlan()
        assertEquals(70_000, plan.initialMs)
        assertEquals(50_000, plan.initialMs - plan.backMs)
        assertEquals(90_000, plan.initialMs - plan.backMs + plan.afterMs + plan.forwardMs)
        assertEquals(90_000, plan.initialMs + 2 * plan.afterMs)
    }

    @Test fun forwardSeekFitsShortFixturesWithoutReducingDecodedTime() {
        val plan = PlaybackPlan()
        assertEquals(90_000, forwardSeekMs(plan, null))
        assertEquals(90_000, forwardSeekMs(plan, 180))
        assertEquals(85_000, forwardSeekMs(plan, 96))
        assertEquals(90_000, plan.initialMs + 2 * plan.afterMs)
        assertFailsWith<IllegalStateException> { forwardSeekMs(plan, 70) }
    }

    @Test fun hlsParsingAndSafety() {
        val base = "https://manifest.googlevideo.com/api/manifest/hls_playlist/x"
        val playlist = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:8
#EXT-X-TARGETDURATION:5
#EXT-X-MAP:URI="https://a.googlevideo.com/videoplayback/init"
#EXT-X-KEY:METHOD=AES-128,URI="https://a.googlevideo.com/videoplayback/key"
#EXTINF:1.5,
https://a.googlevideo.com/videoplayback/one
#EXT-X-DISCONTINUITY
#EXT-X-BYTERANGE:30@3
#EXTINF:2,
https://a.googlevideo.com/videoplayback/two
#EXT-X-ENDLIST"""
        assertTrue(allowedHls("https://www.youtube.com/api/manifest/hls.m3u8"))
        assertTrue(allowedHls("https://manifest.googlevideo.com/manifest/audio/playlist.m3u8"))
        val parsed = parseHls(playlist, base)
        assertEquals(8, parsed.sequence)
        assertEquals(1500, parsed.parts[1].start)
        assertEquals(1, parsed.parts[1].discontinuity)
        val previous =
            parseHls(playlist.replace("#EXT-X-MEDIA-SEQUENCE:8", "#EXT-X-DISCONTINUITY-SEQUENCE:3\n#EXT-X-MEDIA-SEQUENCE:8"), base)
        val refreshed = parseHls("#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:4\n#EXTINF:2,\nhttps://a.googlevideo.com/videoplayback/two", base)
        assertEquals(4, previous.parts[1].discontinuity)
        assertEquals(previous.parts[1].discontinuity, refreshed.parts[0].discontinuity)
        assertFailsWith<IllegalStateException> {
            parseHls("#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:-1\n#EXTINF:2,\nhttps://a.googlevideo.com/videoplayback/two", base)
        }
        assertEquals("bytes=3-32", parsed.parts[1].range)
        assertTrue(parsed.end)
        assertEquals("https://a.googlevideo.com/videoplayback/key", parsed.parts[0].key)
        assertFailsWith<IllegalStateException> { parseHls(playlist.replace("AES-128", "SAMPLE-AES"), base) }
        for (url in listOf(
            "http://a.googlevideo.com/videoplayback",
            "https://a.googlevideo.com:80/videoplayback",
            "https://user@a.googlevideo.com/videoplayback",
            "https://a.googlevideo.com.evil.test/videoplayback",
            "https://a.googlevideo.com/other",
            "https://a.googlevideo.com/videoplayback/../private",
            "https://a.googlevideo.com/videoplayback/%2e%2e/private",
        )) {
            assertFalse(allowedHls(url), url)
        }
        assertFailsWith<IllegalStateException> { parseHls("#EXTM3U\n#EXTINF:1,\nhttps://evil.test/videoplayback", base) }
        assertEquals(
            "https://a.googlevideo.com/videoplayback/audio",
            hlsMaster(
                "#EXTM3U\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"https://a.googlevideo.com/videoplayback/audio\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=99999999,AUDIO=\"a\"\nhttps://a.googlevideo.com/videoplayback/video",
                base,
                false,
            ),
        )
        assertEquals(
            "https://manifest.googlevideo.com/videoplayback/muxed" to "VARIANT_0",
            hlsSelection(
                "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"other\",DEFAULT=YES,URI=\"/videoplayback/wrong\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=128000,AUDIO=\"missing\"\n/videoplayback/muxed",
                base,
                false,
                null,
                AudioQuality.AUTO,
            ),
        )
    }

    @Test fun muxedVideoBandwidthDoesNotPretendToBeAudioQuality() {
        val base = "https://manifest.googlevideo.com/api/manifest/hls_playlist/x"
        val body =
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS=\"avc1.640028,mp4a.40.2\"\n/videoplayback/large\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=300000,CODECS=\"avc1.42001e,mp4a.40.2\"\n/videoplayback/small\n"
        assertTrue(hlsSelection(body, base, false, null, AudioQuality.HIGH)!!.first.endsWith("/small"))
        val audio = body.replace("avc1.640028,", "").replace("avc1.42001e,", "")
        assertTrue(hlsSelection(audio, base, false, null, AudioQuality.HIGH)!!.first.endsWith("/large"))
    }

    @Test fun directRangePlaybackAndRealSeek() =
        runBlocking {
            assumeTrue(hasFfmpeg())
            val file = Files.createTempFile("harness-aac-", ".mp4").toFile()
            try {
                val maker =
                    ProcessBuilder(
                        "ffmpeg",
                        "-nostdin",
                        "-v",
                        "quiet",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=60",
                        "-ac",
                        "2",
                        "-ar",
                        "48000",
                        "-c:a",
                        "aac",
                        "-b:a",
                        "384k",
                        "-movflags",
                        "+faststart",
                        "-y",
                        file.absolutePath,
                    ).start()
                awaitProcess(maker, 10)
                assertEquals(0, maker.exitValue())
                val audio = file.readBytes()
                val starts = mutableListOf<Long>()
                HttpClient(
                    MockEngine { request ->
                        val range = checkNotNull(request.headers[HttpHeaders.Range])
                        val begin = range.substringAfter('=').substringBefore('-').toLong()
                        val end = range.substringAfter('-').toLong()
                        synchronized(starts) { starts += begin }
                        respond(
                            audio.copyOfRange(begin.toInt(), end.toInt() + 1),
                            HttpStatusCode.PartialContent,
                            headersOf(HttpHeaders.ContentRange, "bytes $begin-$end/${audio.size}"),
                        )
                    },
                ).use { http ->
                    val budget = MediaBudget(audio.size.toLong() * 10)
                    val source = stream("https://a.googlevideo.com/videoplayback", audio.size.toLong(), "audio/mp4")
                    for (target in listOf(0L, 10_000L, 40_000L)) {
                        var fault: Throwable? = null
                        rangeBridge(http, source, budget, { fault = it }).use { bridge ->
                            val player = decoder(bridge.url, target, listOf("-f", "mp4"))
                            try {
                                val started = System.nanoTime()
                                var observed = 0L
                                val (pcm, settle) = consumePcm(player, target, 600) { position, _, _ -> observed = position }
                                assertTrue(kotlin.math.abs(observed - (target + 600)) < 100)
                                assertTrue((System.nanoTime() - started) / 1_000_000 >= 500)
                                assertTrue(pcm >= 8800)
                                assertTrue(settle < 10_000)
                                assertEquals(null, fault)
                                if (target > 0) assertTrue(bridge.ranged)
                            } finally {
                                stopDecoder(player)
                            }
                        }
                    }
                    assertTrue(starts.any { it > 0 })
                    rangeBridge(http, source, budget, {}).use { bridge ->
                        val player = decoder(bridge.url, 40_000, listOf("-f", "mp4"))
                        try {
                            val error = assertFailsWith<IllegalStateException> { consumePcm(player, 0, 600) { _, _, _ -> } }
                            assertEquals("decoder_seek_mismatch", safeError(error))
                        } finally {
                            stopDecoder(player)
                        }
                    }
                    rangeBridge(http, source, budget, {}).use { bridge ->
                        val player = decoder(bridge.url, 0, listOf("-f", "mp4"))
                        try {
                            val error =
                                assertFailsWith<IllegalStateException> {
                                    consumePcm(player, 0, 600, checkSource = { error("Media HTTP status") }) { _, _, _ -> }
                                }
                            assertEquals("media_http_status", safeError(error))
                        } finally {
                            stopDecoder(player)
                        }
                    }
                    val unknown = source.copy(contentLengthBytes = null)
                    var unknownFault: Throwable? = null
                    rangeBridge(http, unknown, budget, { unknownFault = it }).use { bridge ->
                        val player = decoder(bridge.url, 40_000, listOf("-f", "mp4"))
                        try {
                            assertEquals(9600L, consumePcm(player, 40_000, 600) { _, _, _ -> }.first)
                            assertTrue(bridge.ranged)
                            assertEquals(null, unknownFault)
                        } finally {
                            stopDecoder(player)
                        }
                    }
                    assertFailsWith<IllegalStateException> { fetchRange(http, unknown, 0, 0, budget, audio.size.toLong() + 1) }
                    Unit
                }
            } finally {
                file.delete()
            }
        }

    @Test fun fragmentedMp4InitAndSeek() =
        runBlocking {
            assumeTrue(hasFfmpeg())
            val file = Files.createTempFile("harness-fragmented-", ".mp4").toFile()
            try {
                val maker =
                    ProcessBuilder(
                        "ffmpeg",
                        "-nostdin",
                        "-v",
                        "quiet",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=5",
                        "-c:a",
                        "aac",
                        "-movflags",
                        "+empty_moov+default_base_moof",
                        "-frag_duration",
                        "1000000",
                        "-f",
                        "mp4",
                        "-y",
                        file.absolutePath,
                    ).start()
                awaitProcess(maker, 10)
                assertEquals(0, maker.exitValue())
                val data = file.readBytes()
                val boundaries =
                    (4 until data.size - 4)
                        .filter { offset ->
                            data.copyOfRange(offset, offset + 4).contentEquals("moof".toByteArray()) &&
                                java.nio.ByteBuffer
                                    .wrap(data, offset - 4, 4)
                                    .int in 8..65536
                        }.map { it - 4 }
                assertTrue(boundaries.size >= 4)
                val init = data.copyOfRange(0, boundaries.first())
                val fragments =
                    boundaries.mapIndexed { i, start ->
                        data.copyOfRange(start, boundaries.getOrNull(i + 1) ?: data.size)
                    }
                val firstSabrMedia = SabrChunk(fragments[2], 0, false, 2, 2000, 1000)
                assertEquals(500, sabrPrerollMs(2500, firstSabrMedia))
                assertFailsWith<IllegalStateException> { sabrPrerollMs(2500, firstSabrMedia.copy(startMs = 2600)) }
                assertFailsWith<IllegalStateException> { sabrPrerollMs(3500, firstSabrMedia) }
                val sabrDecoder = decoder("pipe:0", sabrPrerollMs(2500, firstSabrMedia), listOf("-f", "mp4"))
                try {
                    sabrDecoder.outputStream.use {
                        it.write(init)
                        it.write(firstSabrMedia.data)
                    }
                    assertTrue(consumePcm(sabrDecoder, 2500, 300) { _, _, _ -> }.first >= 4800)
                } finally {
                    stopDecoder(sabrDecoder)
                }
                val playlist =
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI=\"init\"\n" +
                        fragments.joinToString("") { index -> "#EXTINF:1,\npart${fragments.indexOf(index)}\n" } +
                        "#EXT-X-ENDLIST\n"
                val mapPlaylist =
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" +
                        "#EXT-X-MAP:URI=\"maps\",BYTERANGE=\"${init.size}@0\"\n#EXTINF:1,\npart0\n" +
                        "#EXT-X-MAP:URI=\"maps\",BYTERANGE=\"${init.size}@${init.size}\"\n#EXTINF:1,\npart1\n" +
                        "#EXT-X-ENDLIST\n"
                val mapRanges = mutableListOf<String>()
                HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath.substringAfterLast('/')
                        respond(
                            when (path) {
                                "list.m3u8" -> playlist.toByteArray()
                                "map.m3u8" -> mapPlaylist.toByteArray()
                                "init" -> init
                                "maps" -> {
                                    val range = checkNotNull(request.headers[HttpHeaders.Range])
                                    mapRanges += range
                                    val from = range.substringAfter('=').substringBefore('-').toInt()
                                    val to = range.substringAfter('-').toInt()
                                    return@MockEngine respond(
                                        (init + init).copyOfRange(from, to + 1),
                                        HttpStatusCode.PartialContent,
                                        headersOf(HttpHeaders.ContentRange, "bytes $from-$to/${init.size * 2}"),
                                    )
                                }
                                else -> fragments[path.removePrefix("part").toInt()]
                            },
                        )
                    },
                ).use { http ->
                    val source = stream("http://127.0.0.1:8877/fixture/list.m3u8", 0, "application/x-mpegURL")
                    val player = HlsSession(http, source, MediaBudget(5_000_000), true)
                    assertTrue(player.play(0, 500) { _, _, _ -> }.first >= 8000)
                    assertTrue(player.play(2000, 500) { _, _, _ -> }.first >= 8000)
                    val mapped = source.copy(audioUrl = "http://127.0.0.1:8877/fixture/map.m3u8")
                    assertTrue(HlsSession(http, mapped, MediaBudget(5_000_000), true).play(0, 1500) { _, _, _ -> }.first >= 24_000)
                    assertEquals(listOf("bytes=0-${init.size - 1}", "bytes=${init.size}-${init.size * 2 - 1}"), mapRanges)
                }
            } finally {
                file.delete()
            }
        }

    @Test fun hlsAdtsAudioWithoutMap() =
        runBlocking {
            assumeTrue(hasFfmpeg())
            val file = Files.createTempFile("harness-adts-", ".aac").toFile()
            try {
                val maker =
                    ProcessBuilder(
                        "ffmpeg",
                        "-nostdin",
                        "-v",
                        "quiet",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=2",
                        "-c:a",
                        "aac",
                        "-f",
                        "adts",
                        "-y",
                        file.absolutePath,
                    ).start()
                awaitProcess(maker, 10)
                assertEquals(0, maker.exitValue())
                val segment = file.readBytes()
                val master =
                    "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",DEFAULT=YES,URI=\"audio\"\n" +
                        "#EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO=\"audio\"\nvideo\n"
                val playlist =
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\npart0\n#EXTINF:2,\npart1\n#EXT-X-ENDLIST\n"
                HttpClient(
                    MockEngine { request ->
                        respond(
                            when (request.url.encodedPath.substringAfterLast('/')) {
                                "master" -> master.toByteArray()
                                "audio" -> playlist.toByteArray()
                                "part0", "part1" -> segment
                                else -> error("Unexpected HLS request")
                            },
                        )
                    },
                ).use { http ->
                    val source = stream("http://127.0.0.1:8877/fixture/master", 0, "application/x-mpegURL")
                    val session = HlsSession(http, source, MediaBudget(5_000_000), true)
                    for (target in listOf(0L, 2250L, 1750L)) {
                        assertEquals(8000L, session.play(target, 500) { _, _, _ -> }.first)
                    }
                    assertEquals("AUDIO_0", session.representation)
                }
            } finally {
                file.delete()
            }
        }

    @Test fun hlsPacedSeekAndLate403() =
        runBlocking {
            assumeTrue(hasFfmpeg())
            val dir = Files.createTempDirectory("harness-ts-").toFile()
            try {
                val output = File(dir, "part%02d.ts")
                val maker =
                    ProcessBuilder(
                        "ffmpeg",
                        "-nostdin",
                        "-v",
                        "quiet",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=6",
                        "-c:a",
                        "aac",
                        "-f",
                        "segment",
                        "-segment_time",
                        "1",
                        "-segment_format",
                        "mpegts",
                        "-y",
                        output.absolutePath,
                    ).start()
                awaitProcess(maker, 10)
                assertEquals(0, maker.exitValue())
                val parts = dir.listFiles()!!.sortedBy { it.name }
                assertTrue(parts.size >= 5)
                val playlist =
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" +
                        parts
                            .mapIndexed { i, part ->
                                (if (i == 2) "#EXT-X-DISCONTINUITY\n" else "") + "#EXTINF:1,\n${part.name}\n"
                            }.joinToString("") + "#EXT-X-ENDLIST\n"
                val key = ByteArray(16) { 17 }
                val iv = ByteArray(16)
                val combined = parts.flatMap { it.readBytes().toList() }.toByteArray()
                val rangedPlaylist =
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" +
                        parts
                            .mapIndexed { i, file ->
                                val start = parts.take(i).sumOf { it.length() }
                                "#EXT-X-BYTERANGE:${file.length()}@$start\n#EXTINF:1,\nbundle.ts\n"
                            }.joinToString("") + "#EXT-X-ENDLIST\n"
                val encryptedPlaylist =
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" +
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x00000000000000000000000000000000\n" +
                        parts.joinToString("") { "#EXTINF:1,\n${it.name}\n" } + "#EXT-X-ENDLIST\n"
                val requested = mutableListOf<String>()
                var failLater = false
                var mode = "plain"
                var liveLists = 0
                var discontinuityLists = 0
                var slideSequence = 0
                HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath.substringAfterLast('/')
                        synchronized(requested) { requested += path }
                        when {
                            failLater && path == parts[3].name -> respond("denied", HttpStatusCode.Forbidden)
                            path == "list.m3u8" -> {
                                val text =
                                    when (mode) {
                                        "aes" -> encryptedPlaylist
                                        "range" -> rangedPlaylist
                                        "cross" ->
                                            "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\n" +
                                                "https://a.googlevideo.com/videoplayback\n#EXT-X-ENDLIST\n"
                                        "sliding" ->
                                            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:$slideSequence\n#EXT-X-TARGETDURATION:1\n" +
                                                parts.drop(slideSequence).take(4).joinToString("") { "#EXTINF:1,\n${it.name}\n" }
                                        "live" -> {
                                            liveLists++
                                            "#EXTM3U\n#EXT-X-TARGETDURATION:1\n" +
                                                parts.take(if (liveLists <= 2) 2 else 5).joinToString("") { "#EXTINF:1,\n${it.name}\n" }
                                        }
                                        "discontinuity-slide" -> {
                                            discontinuityLists++
                                            if (discontinuityLists <= 2) {
                                                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-DISCONTINUITY-SEQUENCE:3\n" +
                                                    parts
                                                        .take(3)
                                                        .mapIndexed { i, part ->
                                                            (if (i == 1) "#EXT-X-DISCONTINUITY\n" else "") + "#EXTINF:1,\n${part.name}\n"
                                                        }.joinToString("")
                                            } else {
                                                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-DISCONTINUITY-SEQUENCE:4\n" +
                                                    "#EXT-X-MEDIA-SEQUENCE:1\n" +
                                                    parts.drop(1).joinToString("") { "#EXTINF:1,\n${it.name}\n" }
                                            }
                                        }
                                        else -> playlist
                                    }
                                respond(text.toByteArray())
                            }
                            path == "key" -> respond(key)
                            path == "videoplayback" -> {
                                assertEquals(null, request.headers[HttpHeaders.Authorization])
                                respond(parts[0].readBytes())
                            }
                            path == "bundle.ts" -> {
                                val range = checkNotNull(request.headers[HttpHeaders.Range])
                                val from = range.substringAfter('=').substringBefore('-').toInt()
                                val to = range.substringAfter('-').toInt()
                                respond(
                                    combined.copyOfRange(from, to + 1),
                                    HttpStatusCode.PartialContent,
                                    headersOf(HttpHeaders.ContentRange, "bytes $from-$to/${combined.size}"),
                                )
                            }
                            else -> {
                                val bytes = File(dir, path).readBytes()
                                if (mode == "aes") {
                                    respond(
                                        Cipher
                                            .getInstance("AES/CBC/PKCS5Padding")
                                            .apply {
                                                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                            }.doFinal(bytes),
                                    )
                                } else {
                                    respond(bytes)
                                }
                            }
                        }
                    },
                ).use { http ->
                    val source = stream("http://127.0.0.1:8877/fixture/list.m3u8", 0, "application/x-mpegURL")
                    val session = HlsSession(http, source, MediaBudget(5_000_000), true)
                    for (target in listOf(0L, 1000L, 3000L)) {
                        val (pcm, _) = session.play(target, 500) { _, _, _ -> }
                        assertTrue(pcm >= 7200)
                    }
                    assertTrue(requested.contains(parts[3].name))
                    assertFailsWith<IllegalStateException> {
                        HlsSession(http, source, MediaBudget(5_000_000), true, AudioQuality.MP4).play(0, 300) { _, _, _ -> }
                    }
                    assertTrue(session.play(1750, 500) { _, _, _ -> }.first >= 8000)
                    failLater = true
                    val failing = HlsSession(http, source, MediaBudget(5_000_000), true)
                    assertFailsWith<IllegalStateException> { failing.play(3000, 500) { _, _, _ -> } }
                    failLater = false
                    mode = "aes"
                    assertTrue(HlsSession(http, source, MediaBudget(5_000_000), true).play(1000, 500) { _, _, _ -> }.first >= 7200)
                    mode = "range"
                    assertTrue(HlsSession(http, source, MediaBudget(5_000_000), true).play(3000, 500) { _, _, _ -> }.first >= 7200)
                    mode = "live"
                    assertTrue(HlsSession(http, source, MediaBudget(5_000_000), true).play(0, 2500) { _, _, _ -> }.first >= 39_200)
                    assertTrue(liveLists >= 3)
                    mode = "discontinuity-slide"
                    assertTrue(HlsSession(http, source, MediaBudget(5_000_000), true).play(1000, 2800) { _, _, _ -> }.first >= 44_800)
                    assertTrue(discontinuityLists >= 3)
                    mode = "sliding"
                    val sliding = HlsSession(http, source, MediaBudget(5_000_000), true)
                    val anchor = sliding.initialTarget(1000, 1000)
                    assertEquals(3000, anchor)
                    slideSequence = 1
                    assertTrue(sliding.play(anchor - 1000, 300) { _, _, _ -> }.first >= 4800)
                    slideSequence = 4
                    assertFailsWith<IllegalStateException> { sliding.play(anchor - 1000, 300) { _, _, _ -> } }
                    mode = "cross"
                    val privateHeaders = source.copy(headers = mapOf(HttpHeaders.Authorization to "private-token"))
                    assertTrue(HlsSession(http, privateHeaders, MediaBudget(5_000_000), true).play(0, 300) { _, _, _ -> }.first >= 4800)
                }
            } finally {
                dir.deleteRecursively()
            }
            Unit
        }
}
