package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.MEDIA_URL
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.PLAYER_CODE
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.PLAYER_URL
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// JVM reflection checks bounded caches without exposing service internals in the public API.
class YouTubeCipherServiceJvmTest {
    @Test
    fun failedCompilationRetriesAfterConfigChange() =
        runBlocking {
            val repository = YouTubeCipherServiceTest().configRepository()
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("player_configs.json")) {
                            respond(repository.cachedJson.replace("20613", "20614"))
                        } else {
                            respond("unsupported player")
                        }
                    },
                )
            var failures = 0
            val service =
                YouTubeCipherService(
                    client,
                    RemotePlayerConfigStore(client, repository),
                    InnerTubeLogger {
                        if (it.message.startsWith("Kotlin player unavailable")) failures++
                    },
                )
            try {
                repeat(3) { assertNull(service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=challenge")) }
                assertEquals(2, failures)
                assertTrue(service.refreshAfterStreamRejection())
                assertNull(service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=challenge"))
                assertEquals(4, failures)
                val failedPrograms = privateField<Map<*, Any>>(service, "compileFailures")
                assertEquals(3, failedPrograms.size)
                for (failed in failedPrograms.values) {
                    // ValueTimeMark's JVM backing field is a monotonic nanosecond reading.
                    val field = failed.javaClass.getDeclaredField("failedAt").apply { isAccessible = true }
                    field.setLong(failed, field.getLong(failed) - 61.seconds.inWholeNanoseconds)
                }
                assertNull(service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=expired"))
                assertEquals(6, failures)
                assertNull(service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=cooling"))
                assertEquals(6, failures)
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun failingBatchTransformsExecuteOncePerDistinctChallenge() =
        runBlocking {
            for ((signatureFailure, nFailure) in listOf(
                "throw 'unsupported';" to "throw 'unsupported';",
                "return '';" to "o.set('n','');return;",
                "return '';" to "return;",
            )) {
                val code =
                    PLAYER_CODE
                        .replace("return input.split", "if(input=='badSig'){$signatureFailure}return input.split")
                        .replace("o.set('n',o.V.n + '_done');", "if(o.V.n=='badN'){$nFailure}o.set('n',o.V.n + '_done');")
                val client = HttpClient(MockEngine { respond(code) })
                val service = YouTubeCipherService(client)
                try {
                    service.preloadPlayerCode(PLAYER_URL)
                    val program = privateField<Map<Any, Any>>(service, "programs").values.single()
                    val parsed = privateField<Map<String, Any?>>(program, "parsed")
                    var executions = 0
                    // Each isolated runtime resolves its fixture's transform function once, even on failure.
                    val counted =
                        object : Map<String, Any?> by parsed {
                            override fun get(key: String): Any? {
                                if (key == "S" || key == "applyN") executions++
                                return parsed[key]
                            }
                        }
                    program.javaClass
                        .getDeclaredField("parsed")
                        .apply { isAccessible = true }
                        .set(program, counted)
                    val formats =
                        List(50) {
                            listOf(
                                YouTubeCipherServiceTest().format(MEDIA_URL).copy(signatureCipher = "s=badSig&url=$MEDIA_URL"),
                                YouTubeCipherServiceTest().format("$MEDIA_URL?n=badN"),
                                YouTubeCipherServiceTest().format(MEDIA_URL).copy(signatureCipher = "s=abc&url=$MEDIA_URL?n=good"),
                                YouTubeCipherServiceTest().format("$MEDIA_URL?n=good"),
                            )
                        }.flatten()
                    repeat(2) {
                        executions = 0
                        val result = service.processFormats(PLAYER_URL, formats)
                        for (group in result.chunked(4)) {
                            assertNull(group[0].url)
                            assertNull(group[1].url)
                            assertEquals("$MEDIA_URL?n=good_done&signature=cba", group[2].url)
                            assertEquals("$MEDIA_URL?n=good_done", group[3].url)
                        }
                        assertEquals(4, executions, "One execution per distinct signature/n per batch, including null results")
                    }
                } finally {
                    service.dispose()
                    client.close()
                }
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
                    kotlinx.coroutines.delay(100)
                    assertTrue(waiter.isActive)
                } finally {
                    mutex.unlock()
                }
                withTimeout(5_000) {
                    assertEquals("retry code", waiter.await())
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
    fun compiledProgramsAreReusedAndLruBounded() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(PLAYER_CODE) })
            val service = YouTubeCipherService(client)
            try {
                assertEquals("$MEDIA_URL?n=one_done", service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=one"))
                val programs = privateField<Map<Any, Any>>(service, "programs")
                val cached = programs.values.single()
                assertEquals("$MEDIA_URL?n=two_done", service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=two"))
                assertSame(cached, programs.values.single())
                repeat(6) {
                    val url = "https://www.youtube.com/s/player/${10000000 + it}/player.js"
                    assertEquals("$MEDIA_URL?n=value_done", service.processNParameter(url, "$MEDIA_URL?n=value"))
                }
                assertEquals(4, programs.size)
                assertEquals(1, privateField<Map<*, *>>(service, "playerCodeCache").size)
                service.dispose()
                assertTrue(programs.isEmpty())
                assertTrue(privateField<Map<*, *>>(service, "playerCodeCache").isEmpty())
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun compiledProgramsUseExactVariantAndHints() =
        runBlocking {
            val repository = YouTubeCipherServiceTest().configRepository()
            val source = PLAYER_CODE.replace("return input.split('').reverse().join('');", "return a+'_'+input;")
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("player_configs.json")) {
                        respond(repository.cachedJson.replace("S(1,2,INPUT)", "S(3,4,INPUT)"))
                    } else {
                        respond(source)
                    }
                }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client, RemotePlayerConfigStore(client, repository))
            try {
                assertEquals("$MEDIA_URL?signature=1_abc", service.deobfuscateSignatureCipher(PLAYER_URL, "s=abc&url=$MEDIA_URL"))
                val programs = privateField<Map<Any, Any>>(service, "programs")
                val first = programs.values.single()
                assertTrue(service.refreshAfterStreamRejection())
                assertEquals("$MEDIA_URL?signature=3_abc", service.deobfuscateSignatureCipher(PLAYER_URL, "s=abc&url=$MEDIA_URL"))
                assertEquals(2, programs.size)
                assertTrue(programs.values.last() !== first)
                assertEquals(1, engine.requestHistory.count { it.url.encodedPath.endsWith(".js") })
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun failureCachesAreBoundedAndDisposeClearsCooldowns() =
        runBlocking {
            for (downloadFailure in listOf(false, true)) {
                val client =
                    HttpClient(
                        MockEngine {
                            respond(
                                "unsupported",
                                if (downloadFailure) io.ktor.http.HttpStatusCode.NotFound else io.ktor.http.HttpStatusCode.OK,
                            )
                        },
                    )
                val service = YouTubeCipherService(client)
                try {
                    repeat(6) {
                        assertNull(service.processNParameter(PLAYER_URL.replace("12345678", "${10000000 + it}"), "$MEDIA_URL?n=value"))
                    }
                    val failures = privateField<Map<*, *>>(service, if (downloadFailure) "playerCodeFailures" else "compileFailures")
                    assertEquals(4, failures.size)
                    service.dispose()
                    assertTrue(failures.isEmpty())
                    assertTrue(privateField<Map<*, *>>(service, "playerCodeCache").isEmpty())
                } finally {
                    service.dispose()
                    client.close()
                }
            }
        }

    @Test
    fun disposalPreventsInflightPreloadFromRepopulatingCaches() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        started.complete(Unit)
                        release.await()
                        respond(PLAYER_CODE)
                    },
                )
            val service = YouTubeCipherService(client)
            val preload = async { service.preloadPlayerCode(PLAYER_URL) }
            try {
                started.await()
                service.dispose()
                release.complete(Unit)
                preload.await()
                assertTrue(privateField<Map<*, *>>(service, "programs").isEmpty())
                assertTrue(privateField<Map<*, *>>(service, "playerCodeCache").isEmpty())
            } finally {
                preload.cancelAndJoin()
                service.dispose()
                client.close()
            }
        }

    @Test
    fun oldDownloadCannotClearOrPoisonPostDisposeReservation() =
        runBlocking {
            for (oldFails in listOf(false, true)) {
                val oldStarted = CompletableDeferred<Unit>()
                val newStarted = CompletableDeferred<Unit>()
                val releaseOld = CompletableDeferred<Unit>()
                val releaseNew = CompletableDeferred<Unit>()
                var requests = 0
                val client =
                    HttpClient(
                        MockEngine {
                            if (++requests == 1) {
                                oldStarted.complete(Unit)
                                releaseOld.await()
                                if (oldFails) respond("missing", io.ktor.http.HttpStatusCode.NotFound) else respond(PLAYER_CODE)
                            } else {
                                newStarted.complete(Unit)
                                releaseNew.await()
                                respond(PLAYER_CODE.replace("_done", "_fresh"))
                            }
                        },
                    )
                val service = YouTubeCipherService(client)
                val old = async { service.preloadPlayerCode(PLAYER_URL) }
                var fresh: kotlinx.coroutines.Deferred<Unit>? = null
                try {
                    oldStarted.await()
                    service.dispose()
                    fresh = async { service.preloadPlayerCode(PLAYER_URL) }
                    newStarted.await()
                    releaseOld.complete(Unit)
                    old.await()
                    assertTrue(privateField<Map<*, *>>(service, "programs").isEmpty())
                    assertTrue(privateField<Map<*, *>>(service, "playerCodeCache").isEmpty())
                    assertTrue(privateField<Map<*, *>>(service, "playerCodeFailures").isEmpty())
                    assertEquals(1, privateField<Map<*, *>>(service, "playerCodeDownloads").size)
                    releaseNew.complete(Unit)
                    fresh.await()
                    assertEquals("$MEDIA_URL?n=value_fresh", service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=value"))
                    assertEquals(2, requests)
                    assertTrue(privateField<Map<*, *>>(service, "playerCodeDownloads").isEmpty())
                } finally {
                    old.cancelAndJoin()
                    fresh?.cancelAndJoin()
                    service.dispose()
                    client.close()
                }
            }
        }

    @Test
    fun disposalWhileResolvingConfigCannotStartANewDownload() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = YouTubeCipherServiceTest().configRepository()
            val config = repository.cachedJson
            repository.cachedJson = ""
            val engine =
                MockEngine {
                    started.complete(Unit)
                    release.await()
                    respond(config)
                }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client, RemotePlayerConfigStore(client, repository))
            val preload = async { service.preloadPlayerCode(PLAYER_URL) }
            try {
                started.await()
                service.dispose()
                release.complete(Unit)
                preload.await()
                assertEquals(1, engine.requestHistory.size)
                assertTrue(privateField<Map<*, *>>(service, "programs").isEmpty())
                assertTrue(privateField<Map<*, *>>(service, "playerCodeCache").isEmpty())
                assertTrue(privateField<Map<*, *>>(service, "playerCodeDownloads").isEmpty())
            } finally {
                preload.cancelAndJoin()
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
}
