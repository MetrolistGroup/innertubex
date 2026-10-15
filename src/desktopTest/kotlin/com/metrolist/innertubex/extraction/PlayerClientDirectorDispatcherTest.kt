package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class PlayerClientDirectorDispatcherTest {
    @Test
    fun largePlayerResponseIsDecodedOffTheCallerThread() =
        runBlocking {
            val callerThread = Thread.currentThread()
            val decodeThread = AtomicReference<Thread>()
            val padding = "x".repeat(1024 * 1024)
            val payload =
                """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback","mimeType":"audio/webm"}]},"padding":"$padding"}"""
            val client =
                HttpClient(MockEngine { respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                val director =
                    PlayerClientDirector(
                        InnerTube(client, retryDelay = {}),
                        object : ClientFallbackStrategy {
                            override fun resolveClients(hints: ContentHints) = listOf(YouTubeClient.VISIONOS)
                        },
                        object : TokenProvider {
                            override suspend fun getPoToken(
                                videoId: String,
                                visitorData: String,
                                cookie: String?,
                            ): PoTokenResult? = null
                        },
                        logger =
                            InnerTubeLogger { event ->
                                if (event.message == "player response decoded") decodeThread.set(Thread.currentThread())
                            },
                    )
                val batch = director.fetchPlayerResponses("video", PlayerConfig("player.js", null, null, null), ContentHints())
                assertEquals(1, batch.playableResponses.size)
                assertNotNull(decodeThread.get())
                assertNotSame(callerThread, decodeThread.get())
                assertEquals(callerThread, Thread.currentThread())
            } finally {
                client.close()
            }
        }
}
