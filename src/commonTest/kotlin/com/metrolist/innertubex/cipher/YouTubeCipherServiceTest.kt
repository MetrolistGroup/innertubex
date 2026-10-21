package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class YouTubeCipherServiceTest {
    @Test
    fun hintedAndDiscoveredProgramsSolveBatchesAndSingleChallenges() =
        runBlocking {
            for (hinted in listOf(true, false)) {
                val engine = MockEngine { respond(if (hinted) PLAYER_CODE.substringBefore("var landmark=") else PLAYER_CODE) }
                val client = HttpClient(engine)
                val service = YouTubeCipherService(client, if (hinted) RemotePlayerConfigStore(client, configRepository()) else null)
                try {
                    service.setPreprocessedPlayerCache(
                        { error("Obsolete cache must not be read") },
                        { _, _ -> error("Obsolete cache must not be written") },
                    )
                    service.prewarmEjs()
                    service.preloadPlayerCode(PLAYER_URL)
                    repeat(3) {
                        val cipher = format(MEDIA_URL).copy(signatureCipher = "s=abc&sp=sig&url=$MEDIA_URL?n=value")
                        val solved = service.processFormats(PLAYER_URL, listOf(cipher, format("$MEDIA_URL?n=direct")))
                        assertEquals("sig=cba", Url(solved[0].url!!).parameters.let { "sig=${it["sig"]}" })
                        assertEquals("value_done", Url(solved[0].url!!).parameters["n"])
                        assertEquals("$MEDIA_URL?n=direct_done", solved[1].url)
                        assertNull(solved[0].signatureCipher)
                        assertEquals("$MEDIA_URL?signature=cba", service.deobfuscateSignatureCipher(PLAYER_URL, "s=abc&url=$MEDIA_URL"))
                        assertEquals("$MEDIA_URL?n=value_done", service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=value"))
                    }
                    assertEquals(1, engine.requestHistory.size)
                } finally {
                    service.dispose()
                    client.close()
                }
            }
        }

    @Test
    fun staleHintsCanFallBackToKotlinLandmarkDiscovery() =
        runBlocking {
            val repository = configRepository().apply { cachedJson = cachedJson.replace("S(1,2,INPUT)", "Wrong(1,2,INPUT)") }
            val client = HttpClient(MockEngine { respond(PLAYER_CODE) })
            val service = YouTubeCipherService(client, RemotePlayerConfigStore(client, repository))
            try {
                assertEquals("$MEDIA_URL?signature=cba", service.deobfuscateSignatureCipher(PLAYER_URL, "s=abc&url=$MEDIA_URL"))
                assertEquals("$MEDIA_URL?n=value_done", service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=value"))
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun suppliedPlayerVariantIsUsedWhenPreferredPlayerIsUnavailableOrUnsupported() =
        runBlocking {
            val preferred = "https://www.youtube.com/s/player/12345678/player_ias.vflset/en_GB/base.js"
            for (suffix in listOf("player_ias.vflset/pl_PL/base.js", "player_es6.vflset/pl_PL/base.js")) {
                val supplied = "https://www.youtube.com/s/player/12345678/$suffix"
                for (hinted in listOf(true, false)) {
                    for (httpFailure in listOf(true, false)) {
                        val engine =
                            MockEngine { request ->
                                when (request.url.toString()) {
                                    preferred -> {
                                        if (httpFailure) {
                                            respond(
                                                "not found",
                                                HttpStatusCode.NotFound,
                                            )
                                        } else {
                                            respond("unsupported player")
                                        }
                                    }

                                    supplied -> {
                                        respond(PLAYER_CODE)
                                    }

                                    else -> {
                                        error("Unexpected request")
                                    }
                                }
                            }
                        val client = HttpClient(engine)
                        val service =
                            YouTubeCipherService(client, if (hinted) RemotePlayerConfigStore(client, configRepository()) else null)
                        try {
                            service.preloadPlayerCode(supplied)
                            repeat(2) {
                                assertEquals(
                                    "$MEDIA_URL?signature=cba",
                                    service.deobfuscateSignatureCipher(supplied, "s=abc&url=$MEDIA_URL"),
                                )
                                assertEquals("$MEDIA_URL?n=value_done", service.processNParameter(supplied, "$MEDIA_URL?n=value"))
                                val cipher = format(MEDIA_URL).copy(signatureCipher = "s=abc&url=$MEDIA_URL?n=value")
                                val result = service.processFormats(supplied, listOf(cipher)).single()
                                assertEquals("cba", Url(result.url!!).parameters["signature"])
                                assertEquals("value_done", Url(result.url).parameters["n"])
                            }
                            val expected = if (hinted || suffix.startsWith("player_ias")) listOf(preferred, supplied) else listOf(supplied)
                            assertEquals(expected, engine.requestHistory.map { it.url.toString() })
                        } finally {
                            service.dispose()
                            client.close()
                        }
                    }
                }
            }
        }

    @Test
    fun cancelledPreferredDownloadDoesNotTryAnotherVariant() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val requests = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requests += request.url.encodedPath
                    started.complete(Unit)
                    awaitCancellation()
                }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client, RemotePlayerConfigStore(client, configRepository()))
            val preload = async { service.preloadPlayerCode("https://www.youtube.com/s/player/12345678/player_es6.vflset/pl_PL/base.js") }
            try {
                started.await()
                preload.cancelAndJoin()
                assertEquals(listOf("/s/player/12345678/player_ias.vflset/en_GB/base.js"), requests)
            } finally {
                preload.cancelAndJoin()
                service.dispose()
                client.close()
            }
        }

    @Test
    fun unsupportedPlayersFailClosedWithoutFallback() =
        runBlocking {
            val engine = MockEngine { respond("function unsupported() { return new Proxy({}, {}); }") }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client)
            try {
                repeat(3) {
                    assertNull(service.deobfuscateSignatureCipher(PLAYER_URL, "s=abc&url=$MEDIA_URL"))
                    assertNull(service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=value"))
                    val formats =
                        listOf(
                            format("$MEDIA_URL?n=value"),
                            format(MEDIA_URL).copy(signatureCipher = "s=abc&url=$MEDIA_URL"),
                            format("$MEDIA_URL?direct=1"),
                        )
                    val result = service.processFormats(PLAYER_URL, formats)
                    assertNull(result[0].url)
                    assertNull(result[1].url)
                    assertEquals(formats[2], result[2])
                }
                assertEquals(1, engine.requestHistory.size)
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun malformedCipherDoesNotAbortUsableFormats() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(PLAYER_CODE) })
            val service = YouTubeCipherService(client)
            try {
                val direct = format("$MEDIA_URL?direct=1")
                val valid = format(MEDIA_URL).copy(url = null, signatureCipher = "s=abc&url=$MEDIA_URL")
                for (bad in listOf(
                    "s=%zz&url=$MEDIA_URL",
                    "s=abc&url=%zz",
                    "s=abc&url=$MEDIA_URL&sp=%zz",
                    "s=abc&url=$MEDIA_URL&sp=bad%26name",
                )) {
                    for (signatureField in listOf(true, false)) {
                        val malformed =
                            if (signatureField) {
                                format(
                                    MEDIA_URL,
                                ).copy(signatureCipher = bad)
                            } else {
                                format(MEDIA_URL).copy(cipher = bad)
                            }
                        val result = service.processFormats(PLAYER_URL, listOf(malformed, direct, valid))
                        assertNull(result[0].url)
                        assertEquals(direct, result[1])
                        assertEquals("$MEDIA_URL?signature=cba", result[2].url)
                    }
                }
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun batchUrlReusePreservesAuthorityValidationAndFragments() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(PLAYER_CODE) })
            val service = YouTubeCipherService(client)
            try {
                val unsafe =
                    listOf(
                        "http://example.googlevideo.com/videoplayback?n=bad",
                        "https://example.googlevideo.com:444/videoplayback?n=bad",
                        "https://example.googlevideo.com.evil.test/videoplayback?n=bad",
                        "https://user@example.googlevideo.com/videoplayback?n=bad",
                        "https://example.googlevideo.com/other?n=bad",
                    )
                val untouched = unsafe.map(::format) + format("$MEDIA_URL#fragment&n=bad")
                val valid = format("$MEDIA_URL?n=good&x=1#fragment")
                val result = service.processFormats(PLAYER_URL, untouched + listOf(valid, valid))
                assertEquals(untouched, result.take(untouched.size))
                assertEquals(List(2) { "$MEDIA_URL?n=good_done&x=1#fragment" }, result.takeLast(2).map { it.url })
                for (url in unsafe) {
                    val cipher = format(MEDIA_URL).copy(url = null, signatureCipher = "s=abc&url=$url")
                    assertNull(service.processFormats(PLAYER_URL, listOf(cipher)).single().url)
                    assertNull(service.deobfuscateSignatureCipher(PLAYER_URL, "s=abc&url=$url"))
                }
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun nChallengesAreDecodedOnceAndOutputsEncodedOnce() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(PLAYER_CODE) })
            val service = YouTubeCipherService(client)
            try {
                val url = "$MEDIA_URL?n=a%26b%2Bc&x=1#fragment"
                val result = service.processNParameter(PLAYER_URL, url)
                assertEquals("a&b+c_done", Url(result!!).parameters["n"])
                assertEquals(result, service.processFormats(PLAYER_URL, listOf(format(url))).single().url)
                assertNull(service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=%zz"))
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun signatureChallengesAreDataAndEncodedExactlyOnce() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(PLAYER_CODE) })
            val service = YouTubeCipherService(client)
            try {
                val input = "a&b+c#d';throw 'injected';"
                val cipher = "s=${com.metrolist.innertubex.utils.encodeQueryComponent(input)}&url=$MEDIA_URL%3Fx%3D1%23fragment"
                val single = service.deobfuscateSignatureCipher(PLAYER_URL, cipher)
                assertEquals(input.reversed(), Url(single!!).parameters["signature"])
                assertEquals("fragment", Url(single).fragment)
                assertEquals(single, service.processFormats(PLAYER_URL, listOf(format(MEDIA_URL).copy(cipher = cipher))).single().url)
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun oversizedAndUnchangedChallengesAreRejected() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(PLAYER_CODE.replace("o.V.n + '_done'", "o.V.n")) })
            val service = YouTubeCipherService(client)
            try {
                assertNull(service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=value"))
                assertNull(service.deobfuscateSignatureCipher(PLAYER_URL, "s=${"x".repeat(16_385)}&url=$MEDIA_URL"))
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun rawPlayerCacheKeepsOnlyTheCurrentPlayer() =
        runBlocking {
            val engine = MockEngine { respond("player code") }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client)
            try {
                val other = "https://www.youtube.com/s/player/87654321/player.js"
                service.initialize()
                assertEquals("player code", service.playerCode(PLAYER_URL))
                assertEquals("player code", service.playerCode(PLAYER_URL))
                assertEquals(1, engine.requestHistory.size)
                service.playerCode(other)
                service.playerCode(PLAYER_URL)
                assertEquals(3, engine.requestHistory.size)
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun directFormatsDoNotWaitForPlayerPreload() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        started.complete(Unit)
                        awaitCancellation()
                    },
                )
            val service = YouTubeCipherService(client)
            val preload = async { service.preloadPlayerCode(PLAYER_URL) }
            try {
                started.await()
                val direct = format("$MEDIA_URL?expire=1")
                assertEquals(listOf(direct), withTimeout(1_000) { service.processFormats("unused", listOf(direct)) })
                assertFalse(preload.isCompleted)
            } finally {
                preload.cancelAndJoin()
                service.dispose()
                client.close()
            }
        }

    @Test
    fun unavailableOrUntrustedPlayersAreNotRetriedAcrossSolvePaths() =
        runBlocking {
            for (url in listOf(
                PLAYER_URL,
                "https://example.com/s/player/hash/base.js",
                "https://user@www.youtube.com/s/player/hash/base.js",
            )) {
                val engine = MockEngine { respond("not found", HttpStatusCode.NotFound) }
                val client = HttpClient(engine)
                val service = YouTubeCipherService(client)
                try {
                    repeat(2) {
                        assertNull(service.processFormats(url, listOf(format("$MEDIA_URL?n=challenge"))).single().url)
                        assertNull(service.processNParameter(url, "$MEDIA_URL?n=challenge"))
                        assertNull(service.deobfuscateSignatureCipher(url, "s=abc&url=$MEDIA_URL"))
                    }
                    assertEquals(if (url == PLAYER_URL) 1 else 0, engine.requestHistory.size)
                } finally {
                    service.dispose()
                    client.close()
                }
            }
        }

    @Test
    fun redirectIsNotFollowedAndTransientFailuresRetryOnlyThreeTimes() =
        runBlocking {
            for (status in listOf(HttpStatusCode.Found, HttpStatusCode.ServiceUnavailable)) {
                val engine = MockEngine { respond("failure", status, headersOf(HttpHeaders.Location, "https://evil.test/player.js")) }
                val client = HttpClient(engine)
                val service = YouTubeCipherService(client)
                try {
                    assertNull(service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=value"))
                    assertEquals(if (status == HttpStatusCode.Found) 1 else 3, engine.requestHistory.size)
                    assertTrue(engine.requestHistory.all { it.url.host == "www.youtube.com" })
                } finally {
                    service.dispose()
                    client.close()
                }
            }
        }

    @Test
    fun cipherQueryValuesAreEncodedWithoutMovingFragments() {
        val service = YouTubeCipherService(HttpClient(MockEngine { error("No request expected") }))
        assertEquals(
            "$MEDIA_URL?expire=1&sig=a%26b%2Bc%23d#fragment",
            service.appendQueryParameter("$MEDIA_URL?expire=1#fragment", "sig", "a&b+c#d"),
        )
        assertNull(service.appendQueryParameter(MEDIA_URL, "sig&other", "value"))
        assertNull(service.appendQueryParameter(MEDIA_URL, "sig", ""))
        assertNull(service.appendQueryParameter("https://example.test/videoplayback", "sig", "value"))
        val url = "$MEDIA_URL?n=old&x=1#fragment"
        val match = Regex("[&?]n=([^&#]+)").find(url)!!
        assertEquals("$MEDIA_URL?n=a%26b%2Bc&x=1#fragment", service.replaceQueryParameter(url, match, "a&b+c"))
        val fragmentUrl = "$MEDIA_URL?x=1#fragment&n=old"
        assertNull(service.replaceQueryParameter(fragmentUrl, Regex("[&?]n=([^&#]+)").find(fragmentUrl)!!, "new"))
    }

    internal fun format(url: String) = PlayerResponse.StreamingData.Format(itag = 140, url = url, mimeType = "audio/mp4")

    internal fun configRepository(): PlayerConfigRepository =
        object : PlayerConfigRepository {
            override val enabled = true
            override val sourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
            override val defaultSourceUrl = sourceUrl
            override var cachedJson = """{"schemaVersion":1,"players":{"12345678":{"sig":"S(1,2,INPUT)","nClass":"X","sts":20613}}}"""
            override var cachedAtMs = Clock.System.now().toEpochMilliseconds()
            override var cachedSourceUrl = sourceUrl
            override var cachedEtag = ""
        }

    internal companion object {
        const val PLAYER_URL = "https://www.youtube.com/s/player/12345678/player.js"
        const val MEDIA_URL = "https://example.googlevideo.com/videoplayback"
        val PLAYER_CODE =
            """
            var g={};
            var S=function(a,b,input){return input.split('').reverse().join('');};
            g.X=function(raw,enabled){this.raw=raw;this.V={};};
            g.X.prototype.set=function(k,v){this.V[k]=v;};
            g.X.prototype.get=function(k){applyN(7,19,this);return this.V[k];};
            var applyN=function(a,b,o){var key=a^b;if(key==20){o.set('n',o.V.n + '_done');}};
            var decode=function(a,b,x){return x;};
            var landmark=function(M,k,X){M=new g.X(M,!0);M.set("alr","yes");X&&(X=S(1,2,decode(3,673,X)));};
            """.trimIndent()
    }
}
