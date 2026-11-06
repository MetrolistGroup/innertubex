package com.metrolist.innertubex

import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.utils.YouTubeUrlParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportAuditTest {
    @Test
    fun authenticatedBoundedPostsRejectCallerEnabledRedirects() = runBlocking { assertRejectsRedirect(false) }

    @Test
    fun authenticatedExtractionRejectsCallerEnabledRedirects() = runBlocking { assertRejectsRedirect(true) }

    private suspend fun assertRejectsRedirect(extraction: Boolean) {
        val engine =
            MockEngine { request ->
                if (request.url.host == "www.example.test") {
                    respond("{}")
                } else {
                    respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://www.example.test/collect"))
                }
            }
        val client =
            HttpClient(engine) {
                install(ContentNegotiation) { json() }
                install(HttpRedirect) { checkHttpMethod = false }
            }
        val innerTube = InnerTube(client).also { it.cookie = "SAPISID=test-value" }
        try {
            if (extraction) {
                assertFailsWith<InnerTubeHttpException> {
                    innerTube.playerTextWithSessionBound(
                        YouTubeClient.WEB_REMIX,
                        "video",
                        null,
                        null,
                        innerTube.sessionSnapshot(),
                        null,
                        4096,
                    )
                }
            } else {
                assertEquals(HttpStatusCode.TemporaryRedirect, innerTube.accountMenu(YouTubeClient.WEB_REMIX).status)
            }
            assertEquals(1, engine.requestHistory.size)
            assertTrue(
                engine.requestHistory
                    .single()
                    .headers[HttpHeaders.Cookie]
                    ?.contains("SAPISID=test-value") == true,
            )
        } finally {
            innerTube.close()
            client.close()
        }
    }

    @Test
    fun statisticsHaveFiniteTransportDeadlines(): Unit =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val timeout = requireNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
                        assertTrue(requireNotNull(timeout.requestTimeoutMillis) in 1..30_000)
                        assertTrue(requireNotNull(timeout.connectTimeoutMillis) in 1..10_000)
                        assertTrue(requireNotNull(timeout.socketTimeoutMillis) in 1..10_000)
                        respond("")
                    },
                )
            val innerTube = InnerTube(client)
            try {
                innerTube.registerPlayback(YouTubeClient.WEB, "https://s.youtube.com/api/stats/playback", "cpn")
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun malformedJsonDoesNotExposeResponseOrCause() =
        runBlocking {
            val client = HttpClient(MockEngine { respond("{\"privateField\":\"sensitive-marker\",}") })
            try {
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        client.get("https://example.test").bodyAsJsonObjectLimited(1024)
                    }
                assertFalse(failure.toString().contains("sensitive-marker"))
                assertNull(failure.cause)
            } finally {
                client.close()
            }
        }

    @Test
    fun transcriptFindsRendererAfterUnrelatedAction() =
        runBlocking {
            assertEquals(listOf(TranscriptCue("first", 10, 20)), transcript("{\"unrelatedAction\":{}}", 1))
        }

    @Test
    fun transcriptKeepsEveryCueInGroup() =
        runBlocking {
            assertEquals(listOf(TranscriptCue("first", 10, 20), TranscriptCue("second", 30, 40)), transcript(null, 2))
        }

    @Test
    fun transcriptEnforcesTotalCueLimitAcrossGroups(): Unit =
        runBlocking {
            val cue = """{"transcriptCueRenderer":{"cue":{"simpleText":"cue"},"startOffsetMs":10}}"""
            val group = """{"transcriptCueGroupRenderer":{"cues":[${List(64) { cue }.joinToString(",")}]}}"""
            val groups = List(313) { group }.joinToString(",")
            val body =
                """{"actions":[{"updateEngagementPanelAction":{"content":{"transcriptRenderer":{ """ +
                    """"body":{"transcriptBodyRenderer":{"cueGroups":[$groups]}}}}}}]}"""
            val failure = assertFailsWith<IllegalArgumentException> { transcriptBody(body) }
            assertEquals("Transcript cue count exceeded the supported limit", failure.message)
        }

    @Test
    fun copiedPlayerConfigKeepsAudioValueSemantics() {
        val original = Json.decodeFromString<PlayerResponse.PlayerConfig>("""{"audioConfig":{"loudnessDb":-5.0}}""")
        val other = Json.decodeFromString<PlayerResponse.PlayerConfig>("""{"audioConfig":{"loudnessDb":-3.0}}""")
        assertEquals(original.audioConfig, original.copy().audioConfig)
        assertEquals(original, original.copy())
        assertEquals(original.hashCode(), original.copy().hashCode())
        assertNotEquals(original, other)
        assertNotEquals(original.hashCode(), other.hashCode())
    }

    @Test
    fun mixedCaseHttpSchemesParse() {
        for (scheme in listOf("HTTPS", "hTtPs", "HTTP", "hTtP")) {
            val url = "$scheme://www.youtube.com/watch?v=dQw4w9WgXcQ"
            assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
            assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.extractVideoId(url))
            assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.createWatchEndpoint(url)?.videoId)
        }
    }

    private suspend fun transcript(
        leadingAction: String?,
        count: Int,
    ): List<TranscriptCue> {
        val cues =
            listOf(
                """{"transcriptCueRenderer":{"cue":{"simpleText":"first"},"startOffsetMs":10,"durationMs":20}}""",
                """{"transcriptCueRenderer":{"cue":{"simpleText":"second"},"startOffsetMs":30,"durationMs":40}}""",
            ).take(count).joinToString(",")
        val action =
            """{"updateEngagementPanelAction":{"content":{"transcriptRenderer":{"body":{ """ +
                """"transcriptBodyRenderer":{"cueGroups":[{"transcriptCueGroupRenderer":{"cues":[$cues]}}]}}}}}}"""
        val body = """{"actions":[${listOfNotNull(leadingAction, action).joinToString(",")}]}"""
        return transcriptBody(body)
    }

    private suspend fun transcriptBody(body: String): List<TranscriptCue> {
        val client =
            HttpClient(
                MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) },
            ) {
                install(ContentNegotiation) { json() }
            }
        val innerTube = InnerTube(client)
        return try {
            innerTube.transcriptCues(YouTubeClient.WEB, "video")
        } finally {
            innerTube.close()
            client.close()
        }
    }
}
