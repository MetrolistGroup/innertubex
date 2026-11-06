package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class YtConfigParserTest {
    @Test
    fun loosePlayerUrlScanHasMeasuredBoundedCost() {
        val parser = parser()
        val page = "/s/player/" + "/".repeat(8_192)
        val legacy = Regex("""(?<![A-Za-z0-9:/])(/s/player/[^"'\\]+/[^"'\\]*\.js[^"'\\]*)""")
        val legacyTime = List(3) { measureTime { assertNull(legacy.find(page)) } }.sorted()[1]
        val currentTime = List(3) { measureTime { assertNull(parser.extractPlayerUrl(page)) } }.sorted()[1]
        println("Loose player URL scan, 8192 slashes: legacy=$legacyTime current=$currentTime")
        assertTrue(currentTime < legacyTime / 4, "Malformed player paths must avoid overlapping repetitions")
        assertEquals(
            "https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js",
            parser.extractPlayerUrl(" /s/player/abc/player_ias.vflset/en_US/base.js"),
        )
    }

    @Test
    fun decodesEscapedConfigStrings() =
        runBlocking {
            val html =
                """{"jsUrl":"/s/player/abc123/base.js","STS":20668,"visitorData":"visitor\u003d", """ +
                    """"INNERTUBE_CLIENT_VERSION":"2\u002e0","encryptedHostFlags":"a\/b\"c"}"""
            val client = HttpClient(MockEngine { respond(html) })
            val innerTube = InnerTube(client)
            try {
                val config = YtConfigParserImpl(client, innerTube).fetchEmbeddedConfig("video")
                assertEquals("visitor=", config.visitorData)
                assertEquals("2.0", config.clientVersion)
                assertEquals("a/b\"c", config.encryptedHostFlags)
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun rejectsIncompleteInvalidAndOversizedConfigStrings() {
        val parser = parser()
        assertNull(parser.extractClientVersion("""{"INNERTUBE_CLIENT_VERSION":"unfinished\"}"""))
        assertNull(parser.extractEncryptedHostFlags("""{"encryptedHostFlags":"invalid\q"}"""))
        val large = "x".repeat(64 * 1024)
        assertNull(parser.extractEncryptedHostFlags("""{"encryptedHostFlags":"$large"}"""))
    }

    @Test
    fun timestampSurvivesHtmlApostrophesAndRegexQuotes() {
        val parser = parser()
        assertEquals(20668, parser.extractSignatureTimestamp("<p>Don't stop</p><script>var c={STS:20668};</script>"))
        assertEquals(20668, parser.extractSignatureTimestamp("""var quote=/["']/; var c={STS:20668};"""))
        assertEquals(20668, parser.extractSignatureTimestamp("""var decoy=/signatureTimestamp:12345["']/; var c={STS:20668};"""))
        assertEquals(
            20668,
            parser.extractSignatureTimestamp("<p>Don't stop</p><script>/* STS:12345 */ 'sts:12345'</script><script>{STS:20668}"),
        )
    }

    @Test
    fun timestampIgnoresStringsAndComments() {
        val parser = parser()
        assertEquals(20668, parser.extractSignatureTimestamp("""{"STS":20668,"title":"signatureTimestamp:12345"}"""))
        assertEquals(20668, parser.extractSignatureTimestamp("""{"title":"signatureTimestamp:12345","STS":20668}"""))
        assertEquals(20668, parser.extractSignatureTimestamp("/* signatureTimestamp:12345 */ {sts:20668}"))
        assertEquals(20668, parser.extractSignatureTimestamp("// signatureTimestamp:12345\n{sts:20668}"))
        assertNull(parser.extractSignatureTimestamp("""{"title":"signatureTimestamp:12345"}"""))
    }

    @Test
    fun extractsConfigFieldsAndRejectsUnsafePlayerUrls() {
        val parser = parser()
        assertEquals(
            "https://www.youtube.com/s/player/abc123/player_ias.vflset/en_US/base.js",
            parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"\\/s\\/player\\/abc123\\/player_ias.vflset\\/en_US\\/base.js\"}"),
        )
        assertEquals("abc123", parser.extractPlayerId("https:\\/\\/www.youtube.com\\/s\\/player\\/abc123\\/www-widgetapi.js"))
        assertEquals(20668, parser.extractSignatureTimestamp("signatureTimestamp:20668"))
        assertEquals(20668, parser.extractSignatureTimestamp("{\"signatureTimestamp\":20668}"))
        assertEquals(20668, parser.extractSignatureTimestamp("{\"sts\":20668}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://evil.test/s/player/x/base.js\"}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://www.youtube.com/watch?v=x\"}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://user:pass@www.youtube.com/s/player/x/base.js\"}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://www.youtube.com.evil.test/s/player/x/base.js\"}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://www.youtube.com:8443/s/player/x/base.js\"}"))
    }

    @Test
    fun extractsEmbeddedContextFields() {
        val parser = parser()
        val html = "{\"INNERTUBE_CLIENT_VERSION\":\"2.20260807.00.00\",\"encryptedHostFlags\":\"flags\"}"
        assertEquals("2.20260807.00.00", parser.extractClientVersion(html))
        assertEquals("flags", parser.extractEncryptedHostFlags(html))
    }

    @Test
    fun embeddedRequestUsesValidatedUrlAndReferer() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("https://www.youtube.com/embed/video?html5=1", request.url.toString())
                        assertEquals("https://www.reddit.com/", request.headers["Referer"])
                        respond(
                            "{\"PLAYER_JS_URL\":\"/s/player/embed/base.js\",\"STS\":20668,\"visitorData\":\"visitor\",\"INNERTUBE_CLIENT_VERSION\":\"2.0\",\"encryptedHostFlags\":\"flags\"}",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    },
                )
            val config = YtConfigParserImpl(client, InnerTube(client)).fetchEmbeddedConfig("video")
            assertEquals("flags", config.encryptedHostFlags)
            assertEquals(20668, config.signatureTimestamp)
            client.close()
        }

    @Test
    fun authenticatedRequestOmitsPreferenceCookie() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("SAPISID=session; SID=other", request.headers[HttpHeaders.Cookie])
                        respond(
                            "{\"PLAYER_JS_URL\":\"/s/player/auth/base.js\",\"STS\":20684}",
                            HttpStatusCode.OK,
                        )
                    },
                )
            val innerTube = InnerTube(client).also { it.cookie = "SAPISID=session; PREF=app=m; SID=other" }

            YtConfigParserImpl(client, innerTube).fetchConfig("video", useLoginCookies = true)

            client.close()
        }

    @Test
    fun authenticatedRedirectFailureRetriesWithoutCookies() =
        runBlocking {
            val cookies = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        cookies += request.headers[HttpHeaders.Cookie]
                        if (cookies.size == 1) {
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "https://accounts.google.com/ServiceLogin"),
                            )
                        } else {
                            respond(
                                "{\"PLAYER_JS_URL\":\"/s/player/anonymous/base.js\",\"STS\":20684}",
                                HttpStatusCode.OK,
                            )
                        }
                    },
                )
            val innerTube = InnerTube(client).also { it.cookie = "SAPISID=session" }

            val config = YtConfigParserImpl(client, innerTube).fetchConfig("video", useLoginCookies = true)

            assertEquals("https://www.youtube.com/s/player/anonymous/base.js", config.playerUrl)
            assertEquals(listOf("SAPISID=session", null), cookies)
            client.close()
        }

    @Test
    fun followsApprovedYouTubeRedirects() =
        runBlocking {
            val requestedHosts = mutableListOf<String>()
            val cookie = "SAPISID=session"
            val client =
                HttpClient(
                    MockEngine { request ->
                        requestedHosts += request.url.host
                        assertEquals(cookie, request.headers[HttpHeaders.Cookie])
                        if (request.url.host == "www.youtube.com") {
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "https://m.youtube.com/watch?v=video"),
                            )
                        } else {
                            respond(
                                "{\"PLAYER_JS_URL\":\"/s/player/mobile/base.js\",\"STS\":20684}",
                                HttpStatusCode.OK,
                            )
                        }
                    },
                )
            val innerTube = InnerTube(client).also { it.cookie = cookie }

            val config = YtConfigParserImpl(client, innerTube).fetchConfig("video", useLoginCookies = true)

            assertEquals("https://www.youtube.com/s/player/mobile/base.js", config.playerUrl)
            assertEquals(listOf("www.youtube.com", "m.youtube.com"), requestedHosts)
            client.close()
        }

    @Test
    fun rejectsRedirectsOutsideApprovedYouTubeEndpoints() =
        runBlocking {
            listOf(
                "https://example.com/watch?v=video",
                "https://www.youtube.com/redirect?target=https://example.com",
            ).forEach { location ->
                var requestCount = 0
                val client =
                    HttpClient(
                        MockEngine {
                            requestCount++
                            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                        },
                    )

                assertFailsWith<IllegalStateException> {
                    YtConfigParserImpl(client, InnerTube(client)).fetchConfig("video")
                }
                assertEquals(1, requestCount)
                client.close()
            }
        }

    @Test
    fun iframeFallbackPreservesCancellation() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/iframe_api") delay(1_000)
                        respond("{}", HttpStatusCode.OK)
                    },
                )
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(
                    25,
                ) { YtConfigParserImpl(client, InnerTube(client)).fetchConfig("video") }
            }
            client.close()
        }

    @Test
    fun stopsReadingWatchPageOnceConfigIsKnown() =
        runBlocking {
            val page = ByteChannel()
            launch {
                val fields =
                    "{\"jsUrl\":\"/s/player/abc123/base.js\",\"STS\":20668," +
                        "\"visitorData\":\"visitor\",\"INNERTUBE_CLIENT_VERSION\":\"2.0\"}"
                // The rest of a real page streams slowly; this one never ends.
                page.writeStringUtf8(fields + " ".repeat(40 * 1024))
                page.flush()
            }
            val client =
                HttpClient(
                    MockEngine {
                        respond(page, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                    },
                )

            val config = withTimeout(5_000) { YtConfigParserImpl(client, InnerTube(client)).fetchConfig("video") }

            assertEquals("https://www.youtube.com/s/player/abc123/base.js", config.playerUrl)
            assertEquals(20668, config.signatureTimestamp)
            assertEquals("visitor", config.visitorData)
            client.close()
        }

    @Test
    fun oversizedWatchPageIsRejectedBeforePlayerParsing() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            "x".repeat(4 * 1024 * 1024 + 1),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    },
                )

            assertFailsWith<IllegalStateException> {
                YtConfigParserImpl(client, InnerTube(client)).fetchConfig("video")
            }
            client.close()
        }

    private fun parser() = YtConfigParserImpl(HttpClient(MockEngine { respond("{}") }), InnerTube(HttpClient(MockEngine { respond("{}") })))
}
