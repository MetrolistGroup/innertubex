package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.EjsPlayerFixture.OTHER_PLAYER_URL
import com.metrolist.innertubex.cipher.EjsPlayerFixture.PLAYER_CODE
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class CipherReliabilityJvmTest {
    @Test
    fun coolingZemerPreloadWarmsEjsWithoutDownloadingUnderOperationLock() =
        runBlocking {
            lateinit var service: YouTubeCipherService
            val engine =
                MockEngine {
                    val operationMutex = privateField<Mutex>(service, "operationMutex")
                    assertTrue(operationMutex.tryLock(), "Preload must download outside the operation lock")
                    operationMutex.unlock()
                    respond(PLAYER_CODE)
                }
            val http = HttpClient(engine)
            service = YouTubeCipherService(http, RemotePlayerConfigStore(http, YouTubeCipherServiceTest().configRepository()))
            try {
                service.setPreprocessedPlayerCache({ "_result.n = function(c) { return c + '_done'; };" }, { _, _ -> })
                service.preloadPlayerCode(PLAYER_URL)
                assertFalse(privateField<Map<*, *>>(service, "zemerFailures").isEmpty())
                privateField<EjsChallengeSolver>(service, "ejs").dispose()
                service.playerCode(OTHER_PLAYER_URL)
                service.preloadPlayerCode(PLAYER_URL)
                assertEquals(2, engine.requestHistory.size)
                val runtime = privateField<QuickJsEngine>(service, "engine")
                assertEquals("true", runtime.evaluate("__itxPlayers.has(${QuickJsEngine.jsStringLiteral(PLAYER_URL)})", 8))
            } finally {
                service.dispose()
                http.close()
            }
        }

    @Test
    fun legacyFailureCooldownExpiresAndChangedArtifactsAreNotBlocked() =
        runBlocking {
            val repository =
                object : PlayerConfigRepository {
                    override val enabled = true
                    override var sourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/old/{playerHash}.json"
                    override val defaultSourceUrl = sourceUrl
                    override var cachedJson = ""
                    override var cachedAtMs = 0L
                    override var cachedSourceUrl = ""
                    override var cachedEtag = ""
                }
            var code = "_result.n = function(c) { return ''; };"
            val httpEngine =
                MockEngine {
                    val encoded = Base64.encode(code.encodeToByteArray())
                    respond(
                        """
                        {"playerHash":"12345678","nTransform":{"type":"yt-dlp-ejs-preprocessed-player",
                        "preprocessedPlayerEncoding":"base64","preprocessedPlayer":"$encoded"}}
                        """.trimIndent(),
                    )
                }
            val http = HttpClient(httpEngine)
            val solver = EjsChallengeSolver(QuickJsEngine(), InnerTubeLogger.NONE)
            val client = GitHubPlayerConfigClient(http, solver, repository, { Url(it) }, InnerTubeLogger.NONE)
            var installs = 0
            try {
                solver.setPreprocessedPlayerCache({ null }, { _, value -> if (value != null) installs++ })
                assertTrue(client.solve(PLAYER_URL, emptyList(), listOf("first")).nByChallenge.isEmpty())
                assertEquals(1, installs)
                client.solve(PLAYER_URL, emptyList(), listOf("cooling"))
                assertEquals(1, installs)
                val failures = privateField<MutableMap<Pair<String, String>, TimeSource.Monotonic.ValueTimeMark>>(client, "failedArtifacts")
                failures[failures.keys.single()] = TimeSource.Monotonic.markNow() - 61.seconds
                client.solve(PLAYER_URL, emptyList(), listOf("expired"))
                assertEquals(2, installs)
                assertEquals(1, httpEngine.requestHistory.size)

                repository.sourceUrl = repository.sourceUrl.replace("/old/", "/new/")
                code = "_result.n = function(c) { return c + '_new'; };"
                assertEquals("changed_new", client.solve(PLAYER_URL, emptyList(), listOf("changed")).nByChallenge["changed"])
                assertEquals(2, httpEngine.requestHistory.size)
                client.clearCache()
                assertTrue(failures.isEmpty())
                assertTrue(privateField<Map<*, *>>(client, "cachedConfigs").isEmpty())
            } finally {
                solver.dispose()
                http.close()
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(
        instance: Any,
        name: String,
    ): T =
        instance.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(instance) as T

    private companion object {
        const val PLAYER_URL = "https://www.youtube.com/s/player/12345678/player_ias.vflset/en_GB/base.js"
    }
}
