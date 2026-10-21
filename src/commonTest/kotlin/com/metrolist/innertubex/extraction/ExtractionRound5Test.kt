package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackTransportPreference
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtractionRound5Test {
    @Test
    fun unhintedGateUsesOneRemixRequestAndSabrWithoutMinting() =
        runBlocking {
            for (authenticated in listOf(true, false)) {
                val fixture = Fixture(authenticated = authenticated)
                try {
                    val result = fixture.fetch().playableResponses.single()
                    assertEquals("WEB_REMIX_SABR__nopo", result.profileId)
                    assertTrue(result.useSabr)
                    assertEquals(listOf("VISIONOS", "WEB_EMBEDDED_PLAYER", "WEB_REMIX"), fixture.requests)
                    assertEquals(0, fixture.tokens.calls)
                } finally {
                    fixture.client.close()
                }
            }
        }

    @Test
    fun missingSabrDataReusesRemixResponseAndMintsOnceForDirect() =
        runBlocking {
            for (body in listOf(
                RESPONSE.replace("\"serverAbrStreamingUrl\":\"https://r1.googlevideo.com/videoplayback\",", ""),
                RESPONSE.replace("\"lastModified\":\"5\",", ""),
                RESPONSE.replace("\"lengthSeconds\":\"10\"", "\"lengthSeconds\":\"0\""),
            )) {
                val fixture = Fixture(body)
                try {
                    val result = fixture.fetch().playableResponses.single()
                    assertEquals("WEB_REMIX__po", result.profileId)
                    assertFalse(result.useSabr)
                    assertEquals(listOf("VISIONOS", "WEB_EMBEDDED_PLAYER", "WEB_REMIX"), fixture.requests)
                    assertEquals(1, fixture.tokens.calls)
                } finally {
                    fixture.client.close()
                }
            }
        }

    @Test
    fun confirmedPremiumKeepsDirectRemixWithoutMinting() =
        runBlocking {
            val fixture = Fixture()
            try {
                val result = fixture.fetch(ContentHints().withPremium()).playableResponses.single()
                assertEquals("WEB_REMIX__nopo", result.profileId)
                assertFalse(result.useSabr)
                assertEquals(listOf("VISIONOS", "WEB_EMBEDDED_PLAYER", "WEB_REMIX"), fixture.requests)
                assertEquals(0, fixture.tokens.calls)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun autoOnlyMovesEligibleRemixSabrAndLeavesEarlierClientsInPlace() {
        val strategy = ContentAwareFallbackStrategy()
        val request =
            ClientSelectionRequest(
                ContentHints(),
                authenticated = true,
                availablePoTokenProviders = setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION),
            )

        fun ids(request: ClientSelectionRequest) =
            strategy.selectClients(request, premiumHighQuality = false, visitorDataAvailable = true).candidates.map { it.manifest!!.id }

        val original = ids(request.copy(premium = true))
        val directIndex = original.indexOf("WEB_REMIX")
        assertTrue(original.indexOf("WEB_REMIX_SABR") > directIndex)
        val expected = original.toMutableList()
        expected.remove("WEB_REMIX_SABR")
        expected.add(directIndex, "WEB_REMIX_SABR")
        assertEquals(expected, ids(request))
        assertEquals("VISIONOS_0_1", ids(request).first())
        for (transport in listOf(PlaybackTransportPreference.DIRECT, PlaybackTransportPreference.HLS)) {
            val unchanged = ids(request.copy(transportPreference = transport))
            assertTrue(unchanged.indexOf("WEB_REMIX") < unchanged.indexOf("WEB_REMIX_SABR"))
        }
        val video = ids(request.copy(hints = ContentHints(wantVideo = true)))
        assertTrue(video.indexOf("WEB_REMIX") < video.indexOf("WEB_REMIX_SABR"))
        for (excluded in listOf(setOf("WEB_REMIX_SABR"), setOf("WEB_REMIX_SABR__nopo", "WEB_REMIX_SABR__po"))) {
            assertFalse("WEB_REMIX_SABR" in ids(request.copy(excludedClients = excluded)))
        }
        assertFalse("WEB_REMIX_SABR" in ids(request.copy(hints = ContentHints().withStreamCapabilities(allowSabr = false))))
        assertEquals(listOf("WEB_REMIX"), ids(request.copy(hints = ContentHints(playbackClientOverrideId = "WEB_REMIX"))))
    }

    private class Fixture(
        remixResponse: String = RESPONSE,
        authenticated: Boolean = true,
    ) {
        val requests = mutableListOf<String>()
        val tokens = CountingTokens()
        val client =
            HttpClient(
                MockEngine { request ->
                    assertTrue(request.url.encodedPath.endsWith("/player"))
                    val context = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["context"]!!.jsonObject
                    val name = context["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content
                    requests += name
                    val body =
                        when (name) {
                            "VISIONOS" -> """{"playabilityStatus":{"status":"UNPLAYABLE"}}"""
                            "WEB_EMBEDDED_PLAYER" -> """{"playabilityStatus":{"status":"ERROR"}}"""
                            "WEB_REMIX" -> remixResponse
                            else -> error("Unexpected player client: $name")
                        }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        private val tube =
            InnerTube(client).also {
                it.visitorData = "synthetic-visitor"
                if (authenticated) it.cookie = "SAPISID=synthetic"
            }

        suspend fun fetch(hints: ContentHints = ContentHints()) =
            PlayerClientDirector(tube, ContentAwareFallbackStrategy(), tokens).fetchPlayerResponses("video", CONFIG, hints)
    }

    private class CountingTokens : TokenProvider {
        var calls = 0
        override val capabilities = TokenProviderCapabilities(setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION))

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): PoTokenResult {
            calls++
            return PoTokenResult("AQID", "AQID", visitorData)
        }
    }

    private companion object {
        val CONFIG = PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, "synthetic-visitor", null)
        const val RESPONSE = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","lengthSeconds":"10"},
            "streamingData":{"serverAbrStreamingUrl":"https://r1.googlevideo.com/videoplayback","adaptiveFormats":[
            {"itag":140,"mimeType":"audio/mp4","bitrate":128000,"lastModified":"5","contentLength":"1000",
            "url":"https://r1.googlevideo.com/videoplayback?id=synthetic"},
            {"itag":160,"mimeType":"video/mp4","width":256,"height":144,"lastModified":"7"}]},
            "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}}
        """
    }
}
