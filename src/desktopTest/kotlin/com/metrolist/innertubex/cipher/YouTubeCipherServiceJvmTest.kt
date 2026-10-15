package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.MEDIA_URL
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.PLAYER_CODE
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.PLAYER_URL
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.REMOTE_PLAYER_CODE
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

// Tests that inspect private service state through JVM reflection.
class YouTubeCipherServiceJvmTest {
    @Test
    fun failedRemoteSolverRetriesOnlyAfterConfigChangeOrCooldown() =
        runBlocking {
            val bootstrap = EjsChallengeSolver(QuickJsEngine(), InnerTubeLogger.NONE)
            val preprocessed =
                try {
                    assertNotNull(bootstrap.solve(PLAYER_URL, PLAYER_CODE, listOf("sig" to listOf("warm"))).preprocessedPlayer)
                } finally {
                    bootstrap.dispose()
                }
            val repository = configRepository()
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("player_configs.json")) {
                            respond(repository.cachedJson.replace("20613", "20614"))
                        } else {
                            respond(
                                REMOTE_PLAYER_CODE.replace(
                                    "this.input.indexOf(\"local\") === 0 ? this.input : this.input + \"_done\"",
                                    "this.input",
                                ),
                            )
                        }
                    },
                )
            val store = RemotePlayerConfigStore(client, repository)
            var failures = 0
            val service =
                YouTubeCipherService(
                    client,
                    store,
                    InnerTubeLogger { event ->
                        if (event.message.startsWith("Faraday zemer config failed")) failures++
                    },
                )
            try {
                service.setPreprocessedPlayerCache({ preprocessed }, { _, _ -> })

                suspend fun solve(challenge: String) {
                    assertEquals(
                        "$MEDIA_URL?n=${challenge}_done",
                        service.processFormats(PLAYER_URL, listOf(format("$MEDIA_URL?n=$challenge"))).single().url,
                    )
                }
                repeat(3) { solve("local$it") }
                assertEquals(1, failures)
                assertTrue(service.refreshAfterStreamRejection())
                solve("changed")
                assertEquals(2, failures)
                val failuresByUrl = privateField<Map<String, Any>>(service, "zemerFailures")
                val failed = failuresByUrl.values.single()
                failed.javaClass
                    .getDeclaredField("failedAtMs")
                    .apply { isAccessible = true }
                    .setLong(failed, 0L)
                solve("expired")
                assertEquals(3, failures)
                solve("cooldown")
                assertEquals(3, failures)
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun cancelledDownloadReleasesWaitersAndReservationUnderLockContention() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            var attempts = 0
            val client =
                HttpClient(
                    MockEngine {
                        if (++attempts == 1) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                        respond("retry code")
                    },
                )
            val service = YouTubeCipherService(client)
            val owner = async { service.playerCode(PLAYER_URL) }
            try {
                started.await()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { service.playerCode(PLAYER_URL) }
                val mutex = privateField<Mutex>(service, "cacheMutex")
                mutex.lock()
                try {
                    owner.cancel()
                    withTimeout(5_000) {
                        assertFailsWith<CancellationException> { waiter.await() }
                    }
                } finally {
                    mutex.unlock()
                }
                withTimeout(5_000) {
                    owner.join()
                    assertEquals("retry code", service.playerCode(PLAYER_URL))
                }
                assertEquals(2, attempts)
            } finally {
                owner.cancelAndJoin()
                service.dispose()
                client.close()
            }
        }

    @Test
    fun partialRemoteSolveReusesPreprocessedAndCompiledPlayer() =
        runBlocking {
            val bootstrap = EjsChallengeSolver(QuickJsEngine(), InnerTubeLogger.NONE)
            val preprocessed =
                try {
                    assertNotNull(bootstrap.solve(PLAYER_URL, PLAYER_CODE, listOf("sig" to listOf("warm"))).preprocessedPlayer)
                } finally {
                    bootstrap.dispose()
                }
            val client = HttpClient(MockEngine { respond(REMOTE_PLAYER_CODE) })
            val store = RemotePlayerConfigStore(client, configRepository())
            val service = YouTubeCipherService(client, store)
            var reads = 0
            try {
                service.setPreprocessedPlayerCache({
                    reads++
                    preprocessed
                }, { _, _ -> })
                repeat(3) { i ->
                    val formats = listOf(format("$MEDIA_URL?n=remote"), format("$MEDIA_URL?n=local$i"))
                    val result = service.processFormats(PLAYER_URL, formats)
                    assertEquals("$MEDIA_URL?n=remote_done", result[0].url)
                    assertEquals("$MEDIA_URL?n=local${i}_done", result[1].url)
                }
                assertEquals(1, reads)
                val engine = privateField<QuickJsEngine>(service, "engine")
                assertEquals("1", engine.evaluate("__itxPlayers.size", maxResultLength = 8))
                assertEquals(1, (client.engine as MockEngine).requestHistory.count { it.url.encodedPath.endsWith(".js") })
            } finally {
                service.dispose()
                client.close()
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

    private fun format(url: String) = YouTubeCipherServiceTest().format(url)

    private fun configRepository(): PlayerConfigRepository = YouTubeCipherServiceTest().configRepository()
}
