package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.MEDIA_URL
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.PLAYER_CODE
import com.metrolist.innertubex.cipher.YouTubeCipherServiceTest.Companion.PLAYER_URL
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CipherReliabilityTest {
    @Test
    fun partialTransformFailuresPreserveSolvedSignaturesNValuesAndDirectFormats() =
        runBlocking {
            val code =
                PLAYER_CODE
                    .replace("return input.split", "if(input=='bad')return '';return input.split")
                    .replace("o.set('n',o.V.n + '_done');", "if(o.V.n=='bad')throw 'failure';o.set('n',o.V.n + '_done');")
            val engine = MockEngine { respond(code) }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client, RemotePlayerConfigStore(client, YouTubeCipherServiceTest().configRepository()))
            try {
                val direct = format("$MEDIA_URL?direct=1")
                val formats =
                    listOf(
                        direct,
                        format(MEDIA_URL).copy(signatureCipher = "s=abc&url=$MEDIA_URL?n=good"),
                        format(MEDIA_URL).copy(signatureCipher = "s=bad&url=$MEDIA_URL"),
                        format("$MEDIA_URL?n=bad"),
                    )
                repeat(2) {
                    val result = service.processFormats(PLAYER_URL, formats)
                    assertEquals(direct, result[0])
                    assertEquals("$MEDIA_URL?n=good_done&signature=cba", result[1].url)
                    assertNull(result[2].url)
                    assertNull(result[3].url)
                }
                assertEquals(1, engine.requestHistory.size)
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun compileCooldownSurvivesRawEvictionAndNewConfigEpochRetries() =
        runBlocking {
            val repository = YouTubeCipherServiceTest().configRepository()
            val engine =
                MockEngine { request ->
                    respond(
                        if (request.url.encodedPath.endsWith(
                                "player_configs.json",
                            )
                        ) {
                            repository.cachedJson.replace("20613", "20614")
                        } else {
                            "unsupported"
                        },
                    )
                }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client, RemotePlayerConfigStore(client, repository))
            val url = "https://www.youtube.com/s/player/12345678/player_ias.vflset/en_GB/base.js"
            try {
                assertNull(service.processNParameter(url, "$MEDIA_URL?n=first"))
                service.playerCode("https://www.youtube.com/s/player/87654321/player.js")
                assertNull(service.processNParameter(url, "$MEDIA_URL?n=cooldown"))
                assertEquals(2, engine.requestHistory.size)
                assertTrue(service.refreshAfterStreamRejection())
                assertNull(service.processNParameter(url, "$MEDIA_URL?n=new_epoch"))
                assertEquals(3, engine.requestHistory.count { it.url.encodedPath.endsWith(".js") })
            } finally {
                service.dispose()
                client.close()
            }
        }

    @Test
    fun preloadSharesDownloadWithForegroundAndDoesNotBlockAnotherPlayer() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.url.toString() == PLAYER_URL) {
                        started.complete(Unit)
                        release.await()
                    }
                    respond(PLAYER_CODE)
                }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client)
            val preload = async { service.preloadPlayerCode(PLAYER_URL) }
            try {
                started.await()
                val foreground = async(start = CoroutineStart.UNDISPATCHED) { service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=shared") }
                val other = PLAYER_URL.replace("12345678", "87654321")
                assertEquals("$MEDIA_URL?n=other_done", withTimeout(5_000) { service.processNParameter(other, "$MEDIA_URL?n=other") })
                assertFalse(foreground.isCompleted)
                release.complete(Unit)
                assertEquals("$MEDIA_URL?n=shared_done", withTimeout(5_000) { foreground.await() })
                preload.await()
                assertEquals(1, engine.requestHistory.count { it.url.toString() == PLAYER_URL })
            } finally {
                preload.cancelAndJoin()
                service.dispose()
                client.close()
            }
        }

    @Test
    fun obsoleteCacheHooksNeverTouchPersistentUserData() =
        runBlocking {
            val repository = YouTubeCipherServiceTest().configRepository()
            val original = repository.cachedJson
            val engine = MockEngine { respond(PLAYER_CODE) }
            val client = HttpClient(engine)
            val service = YouTubeCipherService(client, RemotePlayerConfigStore(client, repository))
            try {
                service.setPreprocessedPlayerCache(
                    { error("Must not read EJS storage") },
                    { _, _ -> error("Must not write/delete EJS storage") },
                )
                service.prewarmEjs()
                assertTrue(engine.requestHistory.isEmpty())
                service.preloadPlayerCode(PLAYER_URL)
                service.dispose()
                assertEquals(original, repository.cachedJson)
                assertEquals("$MEDIA_URL?n=fresh_done", service.processNParameter(PLAYER_URL, "$MEDIA_URL?n=fresh"))
                assertEquals(2, engine.requestHistory.size)
            } finally {
                service.dispose()
                client.close()
            }
        }

    private fun format(url: String) = YouTubeCipherServiceTest().format(url)
}
