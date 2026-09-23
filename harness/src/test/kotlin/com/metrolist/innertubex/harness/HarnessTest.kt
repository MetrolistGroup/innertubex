package com.metrolist.innertubex.harness

import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.sabr.SabrChunk
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessTest {
    @Test fun validation() {
        validate(listOf(Case("sample", "AAAAAAAAAAA")))
        assertFailsWith<IllegalArgumentException> { validate(listOf(Case("bad/path", "AAAAAAAAAAA"))) }
        assertFailsWith<IllegalArgumentException> { validate(listOf(Case("sample", "AAAAAAAAAAA", "something"))) }
        assertFailsWith<IllegalArgumentException> { validate(listOf(Case("sample", "AAAAAAAAAAA"), Case("sample", "BBBBBBBBBBB"))) }
    }

    @Test fun captureRedactsNestedUnknownAndNumericIds() {
        val text =
            """{"videoId":"AAAAAAAAAAA","url":"https://host/?token=secret","otherId":123456,"context":{
                "client":{"clientName":"SECRET","clientVersion":"ABC"},"cookie":"SAPISID=secret"},
                "streamingData":{"adaptiveFormats":[{"itag":251,"bitrate":128000,"contentLength":"987654","mimeType":"audio/webm"}]},
                "account":{"itag":222222},
                "playabilityStatus":{"status":"OK","reason":"secret"}}""".replace("\n", "")
        val safe = Capture().safeJson(text).toString()
        for (secret in listOf("AAAAAAAAAAA", "token", "123456", "SAPISID", "SECRET", "987654", "audio/webm", "reason", "222222")) {
            assertFalse(
                secret in safe,
            )
        }
        assertTrue("251" in safe && "128000" in safe && "OK" in safe)
        assertTrue(Capture().safeJson("x".repeat(Capture.LIMIT + 1)).toString().contains("omitted"))
    }

    @Test fun usefulDiagnosticsStillRedactSecrets() {
        val capture = Capture()
        val payload = capture.safeJson("""{"context":{"client":{"clientName":"WEB_REMIX","visitorData":"private"}}}""").toString()
        assertTrue("WEB_REMIX" in payload)
        assertFalse("private" in payload)
        val headers =
            capture
                .safeHeaders(
                    okhttp3.Headers
                        .Builder()
                        .add("Authorization", "Bearer private")
                        .add("Set-Cookie", "private")
                        .add("Content-Range", "bytes 0-99/100")
                        .add("Content-Type", "audio/webm")
                        .build(),
                ).toString()
        assertTrue("bytes 0-99/100" in headers)
        assertFalse("private" in headers)
        val event =
            safeEvent(
                com.metrolist.innertubex.InnerTubeLogEvent(
                    com.metrolist.innertubex.InnerTubeLogLevel.ERROR,
                    "PlayerClientDirector",
                    "private remote message",
                    "AAAAAAAAAAA",
                    mapOf("client" to "WEB_REMIX", "elapsedMs" to "123", "cookie" to "private", "account" to "123456"),
                ),
            )
        assertTrue("client=WEB_REMIX" in event && "elapsedMs=123" in event)
        assertFalse("private" in event || "AAAAAAAAAAA" in event || "123456" in event)
        assertEquals("media_http_status", safeError(IllegalStateException("Media HTTP status")))
        assertEquals("failure", safeError(IllegalStateException("private remote message")))
    }

    private fun stream() =
        ExtractedStream(
            videoId = "AAAAAAAAAAA",
            audioUrl = "https://a.googlevideo.com/videoplayback?secret=1",
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = null,
            contentLengthBytes = 2_097_152,
            itag = 251,
            mimeType = "audio/webm",
            codecs = "opus",
            bitrate = 128000,
            sampleRate = 48000,
            clientName = "test",
            profileId = "WEB_REMIX__nopo",
            requireBoundedRange = true,
            rangeChunkSizeBytes = 1_048_576,
        )

    @Test fun httpCapturePreservesBodyAndRedacts() =
        runBlocking {
            val body =
                """{"playabilityStatus":{"status":"OK","reason":"private-title"},"streamingData":{"adaptiveFormats":[{"itag":251}]},""" +
                    """"unknownId":123456}"""
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/youtubei/v1/player") { exchange ->
                exchange.requestBody.readAllBytes()
                exchange.responseHeaders.set("Content-Type", "application/json")
                val response = if (exchange.requestURI.rawQuery == "large") "{" + " ".repeat(Capture.LIMIT + 1) + "}" else body
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, if (exchange.requestURI.rawQuery == "chunked") 0 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val archive = Capture()
                HttpClient(OkHttp) { engine { addInterceptor(archive) } }.use { http ->
                    val response =
                        http.post("http://127.0.0.1:${server.address.port}/youtubei/v1/player?token=private") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"videoId":"AAAAAAAAAAA","cookie":"private"}""")
                        }
                    assertTrue(response.bodyAsText().contains("private-title"))
                    assertTrue(
                        http.post("http://127.0.0.1:${server.address.port}/youtubei/v1/player?large").bodyAsText().length > Capture.LIMIT,
                    )
                    assertTrue(
                        http
                            .post(
                                "http://127.0.0.1:${server.address.port}/youtubei/v1/player?chunked",
                            ).bodyAsText()
                            .contains("private-title"),
                    )
                }
                assertTrue(archive.entries[1]["response"].toString().contains("omitted"))
                assertTrue(archive.entries[2]["response"].toString().contains("251"))
                val saved = archive.entries[0].toString()
                assertTrue("251" in saved && "OK" in saved && "200" in saved)
                for (secret in listOf("private-title", "AAAAAAAAAAA", "123456", "cookie", "token=private")) assertFalse(secret in saved)
            } finally {
                server.stop(0)
            }
            Unit
        }

    @Test fun sabrInitIsNotAudio() {
        val progress = SabrProgress()
        progress.record(SabrChunk(ByteArray(20), 0, true, null, null, null), 7, 100)
        assertTrue(progress.received == 20L && progress.media == 0L && progress.firstMediaByteMs == -1L)
        progress.record(SabrChunk(ByteArray(10), 20, false, 0, 0, 1000), 42, 100)
        assertTrue(progress.media == 10L && progress.durationMs == 1000L && progress.firstMediaByteMs == 42L)
        assertFailsWith<IllegalStateException> {
            progress.record(SabrChunk(ByteArray(80), 30, false, 1, 1000, 1000), 99, 100)
        }
        assertEquals(42L, progress.firstMediaByteMs)
    }

    @Test fun transportDiagnostics() {
        assertTrue(hints(Case("live", "AAAAAAAAAAA", "live"), "AUTO", false).allowHls)
        assertEquals("none", safeSabrFailure(null))
        assertEquals("ATTESTATION_REQUIRED", safeSabrFailure("ATTESTATION_REQUIRED"))
        assertEquals("other", safeSabrFailure("unknown-response"))
        assertEquals("unknown", sabrHttpStatus(null))
        assertEquals("206", sabrHttpStatus(206))
        val events = mutableListOf<String>()
        repeat(256) { archiveEvent(events, "event-$it") }
        assertEquals(128, events.size)
        assertEquals("event-128", events.first())
        assertEquals("event-255", events.last())
        events.clear()
        archiveEvent(events, "warm")
        assertEquals(listOf("warm"), events)
    }

    @Test fun cleanupFailureIsNotSilent() {
        val dir = Files.createTempDirectory("harness-cleanup-")
        val child = Files.createFile(dir.resolve("sample"))
        try {
            assertFailsWith<java.io.IOException> { deleteCapture(dir) }
        } finally {
            deleteCapture(child)
            deleteCapture(dir)
        }
    }

    @Test fun matrixAndProfile() {
        assertTrue(matchesProfile("WEB_REMIX", stream()))
        assertFalse(matchesProfile("WEB_REMIX_SABR", stream()))
        assertTrue(matchesProfile("AUTO", stream()))
        assertFalse(matchesProfile("AUTO", stream().copy(profileId = "ANDROID_VR_1_65_10__nopo")))
        assertFalse(matchesProfile("AUTO", stream().copy(profileId = "WEB_REMIX__invalid")))
        assertTrue(matchesProfile("WEB_REMIX", stream().copy(profileId = "WEB_REMIX__po")))
        val unsupported = Attempt("sample", "WEB_REMIX", "cold", 1, "unsupported")
        val passed = unsupported.copy(status = "pass")
        val failed = unsupported.copy(status = "fail")
        assertTrue(shouldFail(listOf(unsupported)))
        assertTrue(shouldFail(listOf(passed, failed)))
        assertFalse(shouldFail(listOf(passed, unsupported)))
        val dir = Files.createTempDirectory("harness-report-").toFile()
        try {
            writeReport(dir, listOf(passed, failed, unsupported))
            assertTrue(File(dir, "report.json").readText().contains("\"fail\""))
            assertTrue(File(dir, "summary.md").readText().contains("Passed 1, failed 1, unsupported 1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun laterRangeFailureAndEmptyBody() =
        runBlocking {
            val file = Files.createTempFile("harness-test", ".bin").toFile()
            try {
                for (second in listOf(403, 206)) {
                    val engine =
                        MockEngine(
                            MockEngineConfig().apply {
                                addHandler { request ->
                                    val range = request.headers[HttpHeaders.Range]
                                    if (range ==
                                        "bytes=0-1048575"
                                    ) {
                                        respond(
                                            ByteArray(1_048_576),
                                            headers = headersOf(HttpHeaders.ContentRange, "bytes 0-1048575/2097152"),
                                            status = io.ktor.http.HttpStatusCode.PartialContent,
                                        )
                                    } else {
                                        respond(
                                            if (second ==
                                                403
                                            ) {
                                                "forbidden"
                                            } else {
                                                ""
                                            },
                                            status =
                                                if (second ==
                                                    403
                                                ) {
                                                    io.ktor.http.HttpStatusCode.Forbidden
                                                } else {
                                                    io.ktor.http.HttpStatusCode.PartialContent
                                                },
                                            headers = headersOf(HttpHeaders.ContentRange, "bytes 1048576-2097151/2097152"),
                                        )
                                    }
                                }
                            },
                        )
                    HttpClient(engine).use { http ->
                        var received = 0L
                        assertFailsWith<IllegalStateException> {
                            direct(http, stream(), file, 2_097_152, 1, "matroska") { bytes, _ -> received = bytes }
                        }
                        assertTrue(received == 1_048_576L)
                    }
                }
            } finally {
                file.delete()
            }
        }

    @Test fun deadlineRetainsPartialMediaEvidence() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/player")) {
                        respond(
                            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"AAAAAAAAAAA"},
                    "streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://a.googlevideo.com/videoplayback",
                    "mimeType":"audio/webm; codecs=\"opus\"","bitrate":128000,"contentLength":"2097152"}]}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else if (request.headers[HttpHeaders.Range] == "bytes=0-1048575") {
                        respond(
                            ByteArray(1_048_576),
                            status = io.ktor.http.HttpStatusCode.PartialContent,
                            headers = headersOf(HttpHeaders.ContentRange, "bytes 0-1048575/2097152"),
                        )
                    } else {
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            HttpClient(engine) {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
                }
            }.use { http ->
                val tube = com.metrolist.innertubex.InnerTube(http)
                val cipher =
                    com.metrolist.innertubex.cipher
                        .YouTubeCipherService(http)
                try {
                    val extractor =
                        com.metrolist.innertubex.extraction.InnerTubeExtractor(
                            com.metrolist.innertubex.extraction
                                .YtConfigParserImpl(http, tube),
                            cipher,
                            tube,
                        )
                    var partial: Attempt? = null
                    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                        kotlinx.coroutines.withTimeout(1000) {
                            probe(
                                Case("sample", "AAAAAAAAAAA"),
                                "AUTO",
                                "cold",
                                1,
                                extractor,
                                http,
                                com.metrolist.innertubex.extraction.AudioQuality.HIGH,
                                30,
                                2_097_152,
                                false,
                                false,
                                onProgress = { partial = it },
                            )
                        }
                    }
                    assertEquals("VISIONOS_0_1", partial?.actual)
                    assertEquals(1_048_576L, partial?.mediaBytes)
                    assertEquals("deadline", partial?.reason)
                    assertTrue(partial?.firstMediaByteMs != null)
                } finally {
                    cipher.dispose()
                    tube.close()
                }
            }
        }

    @Test fun youtubeMediaHosts() {
        assertTrue(allowedMedia("https://a.googlevideo.com/videoplayback"))
        assertTrue(allowedMedia("https://youtube.com/videoplayback"))
        assertTrue(allowedMedia("https://www.youtube.com/videoplayback"))
        for (url in listOf(
            "http://www.youtube.com/videoplayback",
            "https://youtube.com.evil.test/videoplayback",
            "https://youtube.com/other",
            "https://user@youtube.com/videoplayback",
            "https://youtube.com:8443/videoplayback",
        )) {
            assertFalse(allowedMedia(url))
        }
    }

    @Test fun localDecodeAndDeadline() =
        runBlocking {
            val source = Files.createTempFile("harness-sleeper-", ".java").toFile()
            try {
                source.writeText(
                    "class SlowHarnessChild { public static void main(String[] args) throws Exception { Thread.sleep(10000); } }",
                )
                val bin = File(System.getProperty("java.home"), "bin")
                val java = File(bin, "java.exe").takeIf { it.isFile } ?: File(bin, "java")
                val sleeping = ProcessBuilder(java.absolutePath, source.absolutePath).start()
                assertFailsWith<IllegalStateException> { awaitProcess(sleeping, 0) }
                assertFalse(sleeping.isAlive)
            } finally {
                source.delete()
            }
            assumeTrue(
                System
                    .getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparator)
                    .any { File(it, "ffmpeg").canExecute() },
            )
            val file = Files.createTempFile("harness-test", ".webm").toFile()
            try {
                val maker =
                    ProcessBuilder(
                        "ffmpeg",
                        "-nostdin",
                        "-v",
                        "error",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=2",
                        "-c:a",
                        "libopus",
                        "-y",
                        file.absolutePath,
                    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                awaitProcess(maker, 10)
                assertEquals(0, maker.exitValue())
                val victim = Files.createTempFile("harness-victim-", ".txt")
                Files.writeString(victim, "sentinel")
                val oldPcm = File(file.parentFile, "${file.nameWithoutExtension}.pcm").toPath()
                val linked =
                    if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
                        runCatching {
                            Files.createSymbolicLink(oldPcm, victim)
                            true
                        }.getOrDefault(false)
                    } else {
                        false
                    }
                try {
                    assertTrue(decode(file, 2, 5, "matroska") >= 1.95)
                    val audio = file.readBytes()
                    val downloaded = Files.createTempFile("harness-short-", ".webm").toFile()
                    try {
                        HttpClient(
                            MockEngine {
                                respond(
                                    audio,
                                    status = io.ktor.http.HttpStatusCode.PartialContent,
                                    headers = headersOf(HttpHeaders.ContentRange, "bytes 0-${audio.size - 1}/${audio.size}"),
                                )
                            },
                        ).use { http ->
                            val result =
                                direct(http, stream().copy(contentLengthBytes = audio.size.toLong()), downloaded, 2_097_152, 2, "matroska")
                            assertEquals(audio.size.toLong(), result.bytes)
                            assertTrue(decode(downloaded, 2, 5, "matroska") >= 1.95)
                        }
                    } finally {
                        deleteCapture(downloaded.toPath())
                    }
                    if (linked) assertEquals("sentinel", Files.readString(victim))
                    assertFailsWith<IllegalStateException> { decode(File(file.parentFile, "absent.webm"), 2, 5, "matroska") }
                    assertFailsWith<IllegalArgumentException> { decode(file, 2, 5, "concat") }
                } finally {
                    if (linked) Files.deleteIfExists(oldPcm)
                    Files.deleteIfExists(victim)
                }
            } finally {
                file.delete()
            }
            Unit
        }
}
