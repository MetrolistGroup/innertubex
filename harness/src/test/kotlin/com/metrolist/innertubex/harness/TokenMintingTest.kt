package com.metrolist.innertubex.harness

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenMintingTest {
    @Test
    fun browserPipeHasLocalPageWithoutNetworkEgress() =
        runBlocking {
            if (listOf(
                    "/usr/bin/chromium",
                    "/usr/bin/node",
                    "/usr/bin/unshare",
                ).any { !Files.isExecutable(Path.of(it)) }
            ) {
                return@runBlocking
            }
            val namespaces = ProcessBuilder("/usr/bin/unshare", "-Un", "--map-current-user", "true").start()
            if (!namespaces.waitFor(2, TimeUnit.SECONDS) || namespaces.exitValue() != 0) return@runBlocking
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 500
                val browser = BrowserRuntime(null, Proxy(Proxy.Type.HTTP, InetSocketAddress("::1", 8181)))
                try {
                    assertEquals(
                        "true",
                        browser.evaluate(
                            "location.origin === 'https://www.youtube.com' && document.documentElement.outerHTML.length < 1024",
                        ),
                    )
                    browser.evaluate(
                        "new Worker(URL.createObjectURL(new Blob([\"fetch('http://127.0.0.1:${server.localPort}/')\"]))); 'started';",
                    )
                    assertFailsWith<SocketTimeoutException> { server.accept() }
                } finally {
                    browser.close()
                }
            }
            Unit
        }

    @Test
    fun rejectsUntrustedInterpreterBeforeAnyInterpreterOrBrowserRequest() =
        runBlocking {
            val calls = mutableListOf<String>()
            val engine =
                MockEngine(
                    MockEngineConfig().apply {
                        addHandler { request ->
                            calls += request.url.toString()
                            assertEquals("www.youtube.com", request.url.host)
                            assertEquals("/watch", request.url.encodedPath)
                            assertEquals("sample-visitor", request.headers["X-Goog-Visitor-Id"])
                            assertEquals("test=opaque; SOCS=CAI", request.headers[HttpHeaders.Cookie])
                            respond(
                                "ytcfg.set({\"EVENT_ID\":\"event\"}); window.ytAtR = '" +
                                    "{\"bgChallenge\":{\"interpreterUrl\":{\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\":" +
                                    "\"https://evil.example/botguard.js\"}}}';",
                            )
                        }
                    },
                )
            HttpClient(engine).use { client ->
                val provider = HarnessTokenProvider(client, browserExecutable = "/missing/chromium")
                val error =
                    assertFailsWith<IllegalStateException> {
                        provider.getPoToken("AAAAAAAAAAA", "sample-visitor", "test=opaque")
                    }
                assertTrue(error.message!!.contains("interpreter"))
                assertFalse(error.message!!.contains("opaque"))
                assertEquals(1, calls.size)
                provider.close()
            }
        }

    @Test
    fun boundsWatchBodyAndDoesNotFollowCredentialRedirects() =
        runBlocking {
            listOf(false, true).forEach { redirect ->
                var calls = 0
                val engine =
                    MockEngine {
                        calls++
                        if (redirect) {
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headers = io.ktor.http.headersOf(HttpHeaders.Location, "https://evil.example/watch"),
                            )
                        } else {
                            respond("x".repeat(4 * 1024 * 1024 + 1))
                        }
                    }
                HttpClient(engine).use { client ->
                    val provider = HarnessTokenProvider(client, browserExecutable = "/missing/chromium")
                    val error =
                        assertFailsWith<IllegalStateException> {
                            provider.getPoToken("AAAAAAAAAAA", "sample-visitor", "test=opaque")
                        }
                    assertFalse(error.message!!.contains("opaque"))
                    assertEquals(1, calls)
                    provider.close()
                }
            }
        }

    @Test
    fun integrityExpiryDoesNotExtendShortLifetimes() {
        assertEquals(2000, tokenExpiry(1000, 2))
        assertEquals(3_241_000L, tokenExpiry(1000, 3600))
        assertFailsWith<IllegalArgumentException> { tokenExpiry(1000, 0) }
        assertFailsWith<IllegalArgumentException> { tokenExpiry(1000, -1) }
    }

    @Test
    fun missingBrowserFailsBeforeNetworkOnPrewarm() =
        runBlocking {
            HttpClient(MockEngine { error("Unexpected network") }).use { client ->
                val provider = HarnessTokenProvider(client, browserExecutable = "/missing/chromium")
                try {
                    assertTrue(assertFailsWith<IllegalStateException> { provider.prewarm() }.message!!.startsWith("Chromium unavailable"))
                } finally {
                    provider.close()
                }
            }
        }
}
