package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.EjsPlayerFixture.OTHER_PLAYER_URL
import com.metrolist.innertubex.cipher.EjsPlayerFixture.PLAYER_CODE
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.MEDIA_URL
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.REMOTE_PLAYER_CODE
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CipherReliabilityTest {
    @Test
    fun batchParserInitializationFailurePreservesSolvedSignaturesAndNValues() =
        runBlocking {
            val code =
                REMOTE_PLAYER_CODE
                    .replace(
                        "function S(a,b,input){",
                        "var omitted = []; var helper = {reverse: omitted.reverse};" +
                            "var sigFn = function(a){a=a.split(\"\");helper.reverse(a);return a.join(\"\")};" +
                            "function unused(params, x) { params.set(\"signature\", sigFn(x)); }" +
                            "function S(a,b,input){if (input === 'bad') return '';",
                    )
            val parserEngine = QuickJsEngine()
            try {
                parserEngine.initialize()
                parserEngine.setupYoutubeGlobals()
                assertFailsWith<Exception> {
                    parserEngine.execute(PlayerScriptParser.generateSolverScript(PlayerScriptParser.parse(code)))
                }
            } finally {
                parserEngine.dispose()
            }
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith(".js")) respond(code) else respond("missing", HttpStatusCode.NotFound)
                }
            val http = HttpClient(engine)
            val service = YouTubeCipherService(http, RemotePlayerConfigStore(http, YouTubeCipherServiceTest().configRepository()))
            try {
                val direct = format("$MEDIA_URL?direct=1")
                val goodSignature = format(null).copy(signatureCipher = "s=abc&url=$MEDIA_URL")
                val badSignature = format(null).copy(signatureCipher = "s=bad&url=$MEDIA_URL")
                val signatureResult = service.processFormats(PLAYER_URL, listOf(direct, goodSignature, badSignature))
                assertEquals(direct, signatureResult[0])
                assertEquals("$MEDIA_URL?signature=cba", signatureResult[1].url)
                assertNull(signatureResult[2].url)

                val nResult =
                    service.processFormats(
                        PLAYER_URL,
                        listOf(direct, format("$MEDIA_URL?n=remote"), format("$MEDIA_URL?n=local")),
                    )
                assertEquals(direct, nResult[0])
                assertEquals("$MEDIA_URL?n=remote_done", nResult[1].url)
                assertNull(nResult[2].url)
                assertEquals(1, engine.requestHistory.count { it.url.encodedPath.endsWith(".js") })
            } finally {
                service.dispose()
                http.close()
            }
        }

    @Test
    fun foregroundPrefetchHonorsZemerCooldownAfterRawPlayerEvictionAndRetriesNewEpoch() =
        runBlocking {
            val repository = YouTubeCipherServiceTest().configRepository()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith(".js") -> respond(PLAYER_CODE)
                        request.url.encodedPath.endsWith("player_configs.json") -> respond(repository.cachedJson.replace("20613", "20614"))
                        else -> respond("missing", HttpStatusCode.NotFound)
                    }
                }
            val http = HttpClient(engine)
            val service = YouTubeCipherService(http, RemotePlayerConfigStore(http, repository))
            try {
                service.setPreprocessedPlayerCache({ N_ONLY }, { _, _ -> })

                suspend fun solve(challenge: String) {
                    assertEquals(
                        "$MEDIA_URL?n=${challenge}_done",
                        service.processFormats(PLAYER_URL, listOf(format("$MEDIA_URL?n=$challenge"))).single().url,
                    )
                }
                solve("first")
                service.playerCode(OTHER_PLAYER_URL)
                solve("cooldown")
                assertEquals(2, engine.requestHistory.count { it.url.encodedPath.endsWith(".js") })
                assertTrue(service.refreshAfterStreamRejection())
                solve("new_epoch")
                assertEquals(3, engine.requestHistory.count { it.url.encodedPath.endsWith(".js") })
            } finally {
                service.dispose()
                http.close()
            }
        }

    @Test
    fun warmupPreservesPersistentNOnlyPreprocessingWithoutLoadingRawPlayer() =
        runBlocking {
            val solver = EjsChallengeSolver(QuickJsEngine(), InnerTubeLogger.NONE)
            val writes = mutableListOf<String?>()
            try {
                solver.setPreprocessedPlayerCache({ N_ONLY }, { _, value -> writes += value })
                solver.warmPlayer(PLAYER_URL) { error("Warmup must use n-only preprocessing") }
                assertTrue(writes.isEmpty())
                assertFalse(solver.needsFullPlayer(PLAYER_URL))
                val solved = solver.solve(PLAYER_URL, { error("Warmup must retain compiled n-transform") }, listOf("n" to listOf("fresh")))
                assertEquals("fresh_done", solved.nByChallenge["fresh"])
                assertTrue(writes.isEmpty())
            } finally {
                solver.dispose()
            }
        }

    @Test
    fun warmupEvictsCompilationFailureAndRepairsPersistentPreprocessing() =
        runBlocking {
            val engine = QuickJsEngine()
            val solver = EjsChallengeSolver(engine, InnerTubeLogger.NONE)
            var stored: String? = "_result.n = ;"
            val writes = mutableListOf<String?>()
            var rawLoads = 0
            try {
                solver.setPreprocessedPlayerCache({ stored }, { _, value ->
                    stored = value
                    writes += value
                })
                solver.warmPlayer(PLAYER_URL) {
                    rawLoads++
                    null
                }
                assertEquals(listOf<String?>(null), writes)
                assertTrue(solver.needsFullPlayer(PLAYER_URL))
                assertEquals("false", engine.evaluate("__itxPlayers.has(${QuickJsEngine.jsStringLiteral(PLAYER_URL)})", 8))
                solver.warmPlayer(PLAYER_URL) {
                    rawLoads++
                    PLAYER_CODE
                }
                assertEquals(2, rawLoads)
                assertNotNull(stored)
                assertEquals("true", engine.evaluate("__itxPlayers.has(${QuickJsEngine.jsStringLiteral(PLAYER_URL)})", 8))
                assertEquals("cba", solver.solve(PLAYER_URL, "", listOf("sig" to listOf("abc"))).sigByChallenge["abc"])
            } finally {
                solver.dispose()
            }
        }

    @Test
    fun partialLegacyFailureDoesNotCoolDownWorkingNTransform() =
        runBlocking {
            val httpEngine = MockEngine { respond(legacyConfig(N_ONLY)) }
            val http = HttpClient(httpEngine)
            val solver = EjsChallengeSolver(QuickJsEngine(), InnerTubeLogger.NONE)
            val client = GitHubPlayerConfigClient(http, solver, legacyRepository(), { Url(it) }, InnerTubeLogger.NONE)
            try {
                val partial = client.solve(PLAYER_URL, listOf("sig"), listOf("first"))
                assertTrue(partial.sigByChallenge.isEmpty())
                assertEquals("first_done", partial.nByChallenge["first"])
                assertEquals("fresh_done", client.solve(PLAYER_URL, emptyList(), listOf("fresh")).nByChallenge["fresh"])
                assertTrue(client.solve(PLAYER_URL, listOf("another"), emptyList()).sigByChallenge.isEmpty())
                assertEquals(1, httpEngine.requestHistory.size)
            } finally {
                solver.dispose()
                http.close()
            }
        }

    @Test
    fun compiledHitsRefreshBothLruOrderings() =
        runBlocking {
            val engine = QuickJsEngine()
            val solver = EjsChallengeSolver(engine, InnerTubeLogger.NONE)
            val thirdPlayer = PLAYER_URL.replace("12345678", "aaaaaaaa")
            try {
                suspend fun install(url: String) {
                    solver.cachePreprocessedPlayer(url, N_ONLY)
                    assertEquals("first_done", solver.solve(url, "", listOf("n" to listOf("first"))).nByChallenge["first"])
                }
                install(PLAYER_URL)
                install(OTHER_PLAYER_URL)
                assertEquals("hit_done", solver.solve(PLAYER_URL, "", listOf("n" to listOf("hit"))).nByChallenge["hit"])
                install(thirdPlayer)
                assertEquals("false", engine.evaluate("__itxPlayers.has(${QuickJsEngine.jsStringLiteral(OTHER_PLAYER_URL)})", 8))
                assertEquals("2", engine.evaluate("__itxPlayers.size", 8))
                var loads = 0
                val result =
                    solver.solve(PLAYER_URL, {
                        loads++
                        null
                    }, listOf("n" to listOf("retained")))
                assertEquals("retained_done", result.nByChallenge["retained"])
                assertEquals(0, loads)
            } finally {
                solver.dispose()
            }
        }

    @Test
    fun failedZemerWarmupFallsBackToEjs() =
        runBlocking {
            val engine = MockEngine { respond(PLAYER_CODE) }
            val http = HttpClient(engine)
            var ejsEvaluations = 0
            val logger = InnerTubeLogger { event -> if (event.message.startsWith("EJS evaluate done")) ejsEvaluations++ }
            val service = YouTubeCipherService(http, RemotePlayerConfigStore(http, YouTubeCipherServiceTest().configRepository()), logger)
            try {
                service.setPreprocessedPlayerCache({ N_ONLY }, { _, _ -> })
                service.preloadPlayerCode(PLAYER_URL)
                assertEquals(1, ejsEvaluations)
                assertEquals(1, engine.requestHistory.size)
                assertEquals(
                    "$MEDIA_URL?n=fresh_done",
                    service.processFormats(PLAYER_URL, listOf(format("$MEDIA_URL?n=fresh"))).single().url,
                )
            } finally {
                service.dispose()
                http.close()
            }
        }

    @Test
    fun failedZemerWarmupReusesDownloadedPlayerForEjs() =
        runBlocking {
            val engine = MockEngine { respond(PLAYER_CODE) }
            val http = HttpClient(engine)
            val service = YouTubeCipherService(http, RemotePlayerConfigStore(http, YouTubeCipherServiceTest().configRepository()))
            val writes = mutableListOf<String?>()
            try {
                service.setPreprocessedPlayerCache({ null }, { _, value -> writes += value })
                service.preloadPlayerCode(PLAYER_URL)
                assertNotNull(writes.single())
                service.preloadPlayerCode(PLAYER_URL)
                assertEquals(1, engine.requestHistory.size)
                assertEquals(1, writes.size)
            } finally {
                service.dispose()
                http.close()
            }
        }

    @Test
    fun failedLegacyArtifactDoesNotReplaceWorkingLocalPreprocessing() =
        runBlocking {
            val httpEngine = MockEngine { respond(legacyConfig(N_ONLY)) }
            val http = HttpClient(httpEngine)
            val engine = QuickJsEngine()
            val solver = EjsChallengeSolver(engine, InnerTubeLogger.NONE)
            val client = GitHubPlayerConfigClient(http, solver, legacyRepository(), { Url(it) }, InnerTubeLogger.NONE)
            val writes = mutableListOf<String?>()
            try {
                solver.setPreprocessedPlayerCache({ null }, { _, value -> writes += value })
                val partial = client.solve(PLAYER_URL, listOf("sig"), listOf("n"))
                assertTrue(partial.sigByChallenge.isEmpty())
                assertEquals("n_done", partial.nByChallenge["n"])
                assertNotNull(solver.solve(PLAYER_URL, PLAYER_CODE, listOf("sig" to listOf("local"))).preprocessedPlayer)
                val writesBeforeRetry = writes.size
                assertTrue(client.solve(PLAYER_URL, listOf("new"), listOf("fresh")).nByChallenge.isEmpty())
                assertEquals(writesBeforeRetry, writes.size)
                assertEquals("wen", solver.solve(PLAYER_URL, "", listOf("sig" to listOf("new"))).sigByChallenge["new"])
                assertEquals(1, httpEngine.requestHistory.size)
            } finally {
                solver.dispose()
                http.close()
            }
        }

    @Test
    fun serviceDisposeClearsLegacyPreprocessingAndMissingConfigCaches() =
        runBlocking {
            for (initiallyMissing in listOf(false, true)) {
                var disposed = false
                val engine =
                    MockEngine {
                        if (initiallyMissing && !disposed) {
                            respond("missing", HttpStatusCode.NotFound)
                        } else {
                            respond(legacyConfig(if (disposed) N_ONLY.replace("_done", "_new") else N_ONLY))
                        }
                    }
                val http = HttpClient(engine)
                val service = YouTubeCipherService(http, RemotePlayerConfigStore(http, legacyRepository()))
                try {
                    val first = service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=first")
                    if (initiallyMissing) assertNull(first) else assertEquals("$MEDIA_URL?n=first_done", first)
                    val requestsBeforeDispose = engine.requestHistory.size
                    service.dispose()
                    disposed = true
                    assertEquals("$MEDIA_URL?n=second_new", service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=second"))
                    assertEquals(requestsBeforeDispose + 1, engine.requestHistory.size)
                } finally {
                    service.dispose()
                    http.close()
                }
            }
        }

    private fun format(url: String?) = Format(itag = 140, url = url, mimeType = "audio/mp4")

    private fun legacyRepository(): PlayerConfigRepository =
        object : PlayerConfigRepository {
            override val enabled = true
            override val sourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/{playerHash}.json"
            override val defaultSourceUrl = sourceUrl
            override var cachedJson = ""
            override var cachedAtMs = 0L
            override var cachedSourceUrl = ""
            override var cachedEtag = ""
        }

    private fun legacyConfig(code: String): String {
        val encoded = Base64.encode(code.encodeToByteArray())
        return """
            {"playerHash":"12345678","nTransform":{"type":"yt-dlp-ejs-preprocessed-player",
            "preprocessedPlayerEncoding":"base64","preprocessedPlayer":"$encoded"}}
            """.trimIndent()
    }

    private companion object {
        const val PLAYER_URL = "https://www.youtube.com/s/player/12345678/player_ias.vflset/en_GB/base.js"
        const val N_ONLY = "_result.n = function(c) { return c + '_done'; };"
    }
}
