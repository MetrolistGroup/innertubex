package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubPlayerConfigClientTest {
    @Test
    fun cachedBase64ConfigDoesNotReinstallOrPersistUnchangedPlayer() =
        runBlocking {
            val code = "globalThis.compiles = (globalThis.compiles || 0) + 1; _result.n = function(c) { return c + '_done'; };"
            val encoded = Base64.encode(code.encodeToByteArray())
            val httpEngine =
                MockEngine {
                    respondOk(
                        """{"playerHash":"abcdef12","nTransform":{"type":"yt-dlp-ejs-preprocessed-player","preprocessedPlayerEncoding":"base64","preprocessedPlayer":"$encoded"}}""",
                    )
                }
            val repository =
                object : PlayerConfigRepository {
                    override val enabled = true
                    override val defaultSourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/{playerHash}.json"
                    override val sourceUrl = defaultSourceUrl
                    override var cachedJson = ""
                    override var cachedAtMs = 0L
                    override var cachedSourceUrl = ""
                    override var cachedEtag = ""
                }
            val engine = QuickJsEngine()
            val solver = EjsChallengeSolver(engine, InnerTubeLogger.NONE)
            val http = HttpClient(httpEngine)
            var writes = 0
            solver.setPreprocessedPlayerCache({ null }, { _, _ -> writes++ })
            try {
                val client = GitHubPlayerConfigClient(http, solver, repository, { Url(it) }, InnerTubeLogger.NONE)
                val playerUrl = "https://www.youtube.com/s/player/abcdef12/base.js"
                repeat(3) { i ->
                    assertEquals("n${i}_done", client.solve(playerUrl, emptyList(), listOf("n$i")).nByChallenge["n$i"])
                }
                assertEquals(1, httpEngine.requestHistory.size)
                assertEquals(1, writes)
                assertEquals("1", engine.evaluate("compiles", 8))
                // Cached HTTP content must still be supplied after the solver loses its player.
                solver.dispose()
                assertEquals("fresh_done", client.solve(playerUrl, emptyList(), listOf("fresh")).nByChallenge["fresh"])
                assertEquals(1, httpEngine.requestHistory.size)
                assertEquals(2, writes)
            } finally {
                solver.dispose()
                http.close()
            }
        }

    @Test
    fun missingConfigIsNotRefetchedForEveryCipherPass() =
        runBlocking {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
            val repository =
                object : PlayerConfigRepository {
                    override val enabled = true
                    override val defaultSourceUrl = ""
                    override val sourceUrl =
                        "https://cdn.jsdelivr.net/gh/MetrolistGroup/faraday@{playerTag}/registry/players/{playerHash}.json"
                    override var cachedJson = ""
                    override var cachedAtMs = 0L
                    override var cachedSourceUrl = ""
                    override var cachedEtag = ""
                }
            val engineJs = QuickJsEngine()
            try {
                val client =
                    GitHubPlayerConfigClient(
                        HttpClient(engine),
                        EjsChallengeSolver(engineJs, InnerTubeLogger.NONE),
                        repository,
                        { runCatching { Url(it) }.getOrNull() },
                        InnerTubeLogger.NONE,
                    )
                val playerUrl = "https://www.youtube.com/s/player/abcdef12/player_ias.vflset/en_GB/base.js"

                repeat(3) { client.solve(playerUrl, listOf("sig"), listOf("n")) }

                // One jsDelivr request plus its raw GitHub fallback, then both misses are cached.
                assertEquals(2, engine.requestHistory.size)
            } finally {
                engineJs.dispose()
            }
        }
}
