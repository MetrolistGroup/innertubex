package com.metrolist.innertubex

import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpResponseBodyTest {
    @Test
    fun coreResponsesStopOversizedStreamsBeforeEof() =
        runBlocking {
            val operations: List<suspend (InnerTube) -> Unit> =
                listOf(
                    { it.browse(YouTubeClient.WEB) },
                    { it.search(YouTubeClient.WEB, "query") },
                    { it.player(YouTubeClient.WEB, "video") },
                    { it.getTranscript(YouTubeClient.WEB, "video") },
                    { it.uploadSong("track", byteArrayOf(1)) },
                    { it.setPlaylistThumbnail(YouTubeClient.WEB_REMIX, "playlist", byteArrayOf(1)) },
                    { it.playerWithTvBearerSessionBound(YouTubeClient.TVHTML5, "video", null, it.sessionSnapshot(), "test-token") },
                )
            for (operation in operations) {
                val channel = ByteChannel(autoFlush = true)
                var written = 0
                val producer =
                    launch {
                        try {
                            val chunk = ByteArray(8192)
                            repeat(1024) {
                                channel.writeFully(chunk)
                                written += chunk.size
                            }
                        } catch (_: Exception) {
                            // The bounded reader cancels the producer before EOF.
                        } finally {
                            channel.close()
                        }
                    }
                val client =
                    HttpClient(MockEngine { respond(channel) }) {
                        expectSuccess = true
                        install(ContentNegotiation) { json() }
                    }
                val innerTube = InnerTube(client, retryDelay = {})
                try {
                    withTimeout(5_000) {
                        val error = assertFailsWith<IllegalStateException> { operation(innerTube) }
                        assertTrue(error.message.orEmpty().contains("byte limit"))
                        producer.join()
                    }
                    assertTrue(written < 8 * 1024 * 1024)
                } finally {
                    producer.cancel()
                    innerTube.close()
                    client.close()
                }
            }
        }

    @Test
    fun visitorResponsesRejectOversizedBodiesBeforeReading() =
        runBlocking {
            val channels = mutableListOf<ByteChannel>()
            val client =
                HttpClient(
                    MockEngine {
                        val channel = ByteChannel().also { channels += it }
                        respond(channel, headers = headersOf(HttpHeaders.ContentLength, "${8 * 1024 * 1024}"))
                    },
                ) { expectSuccess = true }
            val innerTube = InnerTube(client, retryDelay = {})
            try {
                withTimeout(2_000) { assertNull(innerTube.fetchFreshVisitorData()) }
                assertEquals(2, channels.size)
                assertTrue(channels.all { it.isClosedForRead })
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun boundedResponseRemainsReplayableAndSupportsContentNegotiation() =
        runBlocking {
            val client =
                HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
                    install(ContentNegotiation) { json() }
                }
            val innerTube = InnerTube(client)
            val response = innerTube.browse(YouTubeClient.WEB)
            assertEquals("{}", response.bodyAsTextLimited(1024))
            assertEquals("{}", response.bodyAsTextLimited(1024))
            assertEquals(JsonObject(emptyMap()), response.body<JsonObject>())
            innerTube.close()
            client.close()
        }

    @Test
    fun boundedBodyReaderRejectsOversizedDeclaredLength() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "x".repeat(1024),
                            headers = headersOf(HttpHeaders.ContentLength, "1024"),
                        )
                    },
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    client.get("https://example.test").bodyAsTextLimited(maxBytes = 8)
                }

            assertEquals("Response exceeded the 8 byte limit", error.message)
            client.close()
        }

    @Test
    fun boundedBodyReaderRejectsOversizedChunkedBody() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(ByteReadChannel("x".repeat(1025)))
                    },
                )

            assertFailsWith<IllegalStateException> {
                client.get("https://example.test").bodyAsTextLimited(maxBytes = 1024)
            }
            client.close()
        }
}
