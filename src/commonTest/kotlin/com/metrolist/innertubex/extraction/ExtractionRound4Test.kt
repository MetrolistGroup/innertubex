package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.SelectedClient
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
import kotlin.test.assertTrue

class ExtractionRound4Test {
    @Test
    fun gatedSelectionPlacesAllAutomaticWebSabrBeforeDirectWeb() {
        val strategy = ContentAwareFallbackStrategy()
        val request =
            ClientSelectionRequest(
                ContentHints(isAgeRestricted = true),
                authenticated = true,
                availablePoTokenProviders = setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION),
            )
        val ids = strategy.selectClients(request).candidates.map { it.manifest!!.id }
        assertEquals(SABR_ORDER, ids.take(4))
        for (direct in listOf("WEB_REMIX", "WEB_CREATOR")) {
            assertTrue(ids.indexOf(direct) >= 4)
        }
        assertTrue("WEB_SAFARI" !in ids && "MWEB" !in ids)
        val premium = strategy.selectClients(request.copy(hints = request.hints.withPremium(), premium = true)).candidates
        assertTrue(premium.indexOfFirst { it.manifest!!.id == "WEB_REMIX" } < premium.indexOfFirst { it.manifest!!.id == "WEB_REMIX_SABR" })
    }

    @Test
    fun hintedAndLearnedAgeReorderAllWebSabrAheadOfDirectProfiles() =
        runBlocking {
            for (hinted in listOf(false, true)) {
                val fixture = Fixture(hinted)
                try {
                    val count = if (hinted) 4 else 5
                    val batch = fixture.director(count).fetchPlayerResponses("video", CONFIG, ContentHints(isAgeRestricted = hinted))
                    assertTrue(batch.playableResponses.isEmpty())
                    assertEquals((if (hinted) emptyList() else listOf("VISIONOS")) + SABR_ORDER, fixture.requests)
                } finally {
                    fixture.client.close()
                }
            }
        }

    @Test
    fun remixSuccessMintsNothingAndWebFallbackMintsOnceOnlyWhenAttempted() =
        runBlocking {
            for (hinted in listOf(false, true)) {
                for (success in listOf("WEB_REMIX_SABR", "WEB_SABR")) {
                    val fixture = Fixture(hinted, success)
                    try {
                        val batch = fixture.director().fetchPlayerResponses("video", CONFIG, ContentHints(isAgeRestricted = hinted))
                        val tokenized = success == "WEB_SABR"
                        assertEquals("${success}__${if (tokenized) "po" else "nopo"}", batch.playableResponses.single().profileId)
                        assertEquals(if (tokenized) 1 else 0, fixture.tokens.calls)
                        assertEquals(
                            (if (hinted) emptyList() else listOf("VISIONOS")) + SABR_ORDER.take(if (tokenized) 2 else 1),
                            fixture.requests,
                        )
                        if (tokenized) assertEquals("AQID", batch.playableResponses.single().streamingDataPoToken)
                    } finally {
                        fixture.client.close()
                    }
                }
            }
        }

    @Test
    fun confirmedPremiumKeepsDirectFirstWithoutMinting() =
        runBlocking {
            val fixture = Fixture(hinted = true, success = "WEB_REMIX")
            try {
                val batch =
                    PlayerClientDirector(fixture.tube, FixedStrategy(listOf("WEB_REMIX") + SABR_ORDER), fixture.tokens)
                        .fetchPlayerResponses("video", CONFIG, ContentHints(isAgeRestricted = true).withPremium())
                assertEquals("WEB_REMIX__nopo", batch.playableResponses.single().profileId)
                assertEquals(listOf("WEB_REMIX"), fixture.requests)
                assertEquals(0, fixture.tokens.calls)
            } finally {
                fixture.client.close()
            }
        }

    private class Fixture(
        hinted: Boolean,
        success: String? = null,
    ) {
        val requests = mutableListOf<String>()
        val tokens = CountingTokens()
        val client =
            HttpClient(
                MockEngine { request ->
                    assertTrue(request.url.encodedPath.endsWith("/player"))
                    val context = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["context"]!!.jsonObject
                    val name = context["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content
                    val id =
                        when (name) {
                            "WEB_REMIX" -> {
                                if (success == "WEB_REMIX") "WEB_REMIX" else "WEB_REMIX_SABR"
                            }

                            "WEB" -> {
                                if (request.headers[HttpHeaders.UserAgent] ==
                                    PlaybackClientCatalog.findManifest("WEB_SAFARI_SABR")!!.client.userAgent
                                ) {
                                    "WEB_SAFARI_SABR"
                                } else {
                                    "WEB_SABR"
                                }
                            }

                            "MWEB" -> {
                                "MWEB_SABR"
                            }

                            else -> {
                                name
                            }
                        }
                    requests += id
                    if (id == "WEB_REMIX_SABR") assertEquals(0, tokens.calls)
                    val body =
                        when {
                            id == success -> if (success == "WEB_REMIX") DIRECT else SABR
                            !hinted && requests.size == 1 -> """{"playabilityStatus":{"status":"AGE_CHECK_REQUIRED"}}"""
                            else -> """{"playabilityStatus":{"status":"UNPLAYABLE"}}"""
                        }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val tube =
            InnerTube(client).also {
                it.visitorData = "synthetic-visitor"
                it.cookie = "SAPISID=synthetic"
            }

        fun director(maxRequests: Int = 20) =
            PlayerClientDirector(
                tube,
                FixedStrategy(listOf("VISIONOS", "WEB_REMIX", "WEB_CREATOR", "WEB_SAFARI", "MWEB") + SABR_ORDER.reversed()),
                tokens,
                maxPlayerRequests = maxRequests,
            )
    }

    private class FixedStrategy(
        ids: List<String>,
    ) : ClientFallbackStrategy {
        private val manifests = ids.map { checkNotNull(PlaybackClientCatalog.findManifest(it)) }

        override fun resolveClients(hints: ContentHints) = manifests.map { it.client }

        override fun selectClients(request: ClientSelectionRequest) = ClientSelectionResult(manifests.map { SelectedClient(it.client, it) })
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
        val SABR_ORDER = listOf("WEB_REMIX_SABR", "WEB_SABR", "WEB_SAFARI_SABR", "MWEB_SABR")
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
