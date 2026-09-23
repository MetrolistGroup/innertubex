package com.metrolist.innertubex.harness

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.potoken.parseAttestationChallengeData
import com.metrolist.innertubex.extraction.potoken.parseAttestationInterpreterUrl
import com.metrolist.innertubex.extraction.potoken.parseIntegrityTokenData
import com.metrolist.innertubex.extraction.potoken.parseWebPageAttestationContext
import com.metrolist.innertubex.extraction.potoken.requireTrustedAttestationInterpreterUrl
import com.metrolist.innertubex.extraction.potoken.stringToU8
import com.metrolist.innertubex.extraction.potoken.u8ToBase64
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Proxy

/** Owns an isolated Chromium session. The caller retains ownership of [httpClient]. */
internal class HarnessTokenProvider(
    httpClient: HttpClient,
    private val proxy: Proxy? = null,
    private val browserExecutable: String? = null,
    private val onEvent: (String) -> Unit = {},
) : TokenProvider {
    override val capabilities = TokenProviderCapabilities(setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION), usesWebView = true)
    private val http =
        HttpClient(httpClient.engine) {
            expectSuccess = false
            followRedirects = false
        }
    private val mutex = Mutex()
    private var browser: BrowserRuntime? = null
    private var visitor: String? = null
    private var cookieValue: String? = null
    private var playerToken: String? = null
    private var expiresAt = 0L
    private var closed = false

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed) { "Token provider is closed" }
                require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid video ID" }
                require(
                    visitorData.isNotBlank() && visitorData.length <= 4096 && visitorData.all { it.code in 0x21..0x7e },
                ) { "Invalid visitor data" }
                require(cookie == null || (cookie.length <= 16_384 && '\r' !in cookie && '\n' !in cookie)) { "Invalid cookie" }
                val started = System.nanoTime()
                var stage = "bootstrap"
                try {
                    if ((visitor != null && (visitor != visitorData || cookieValue != cookie)) ||
                        (expiresAt > 0 && System.currentTimeMillis() >= expiresAt)
                    ) {
                        reset()
                    }
                    val runtime = browser ?: BrowserRuntime(browserExecutable, proxy).also { browser = it }
                    val cachedPlayer = playerToken != null
                    if (!cachedPlayer) {
                        stage = "watch"
                        val page =
                            http
                                .get("https://www.youtube.com/watch") {
                                    parameter("v", videoId)
                                    header(HttpHeaders.UserAgent, USER_AGENT)
                                    header(HttpHeaders.Accept, "text/html")
                                    header("X-Goog-Visitor-Id", visitorData)
                                    header(HttpHeaders.Cookie, listOfNotNull(cookie, "SOCS=CAI").joinToString("; "))
                                }.bounded(4 * 1024 * 1024)
                        val attestation = parseWebPageAttestationContext(page)
                        stage = "interpreter"
                        val interpreterUrl = requireTrustedAttestationInterpreterUrl(parseAttestationInterpreterUrl(attestation.challenge))
                        val interpreter = http.get(interpreterUrl) { header(HttpHeaders.UserAgent, USER_AGENT) }.bounded(8 * 1024 * 1024)
                        val challengeData = parseAttestationChallengeData(attestation.challenge, interpreter)
                        stage = "browser"
                        runtime.evaluate(
                            """
                            globalThis.yt = {config_: {EVENT_ID: ${Json.encodeToString(attestation.eventId)}}};
                            window.yt = globalThis.yt;
                            globalThis.__po = {state:'running'};
                            runBotGuard($challengeData).then(r => {
                                globalThis.__signal = r.webPoSignalOutput;
                                globalThis.__po = {state:'ready', value:r.botguardResponse};
                            }).catch(() => {globalThis.__po = {state:'error'}});
                            'started';
                            """.trimIndent(),
                        )
                        val snapshot = await(runtime)
                        stage = "integrity"
                        val response =
                            http
                                .post("https://www.youtube.com/api/jnn/v1/GenerateIT") {
                                    contentType(ContentType.parse("application/json+protobuf"))
                                    header(HttpHeaders.Accept, "application/json")
                                    header(HttpHeaders.UserAgent, USER_AGENT)
                                    header("x-goog-api-key", "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw")
                                    header("x-user-agent", "grpc-web-javascript/0.1")
                                    setBody("[\"O43z0dpjhgX20SCx4KAo\",${Json.encodeToString(snapshot)}]")
                                }.bounded(1024 * 1024)
                        val integrity = parseIntegrityTokenData(response)
                        stage = "minter"
                        runtime.evaluate(
                            """
                            globalThis.__po = {state:'running'};
                            createPoTokenMinter(globalThis.__signal, ${integrity.tokenJavaScript}).then(() => {
                                globalThis.__po = {state:'ready', value:'ready'};
                            }).catch(() => {globalThis.__po = {state:'error'}});
                            'started';
                            """.trimIndent(),
                        )
                        await(runtime)
                        // Never extend a shorter server lifetime; leave a margin when one is available.
                        expiresAt = tokenExpiry(System.currentTimeMillis(), integrity.lifetimeSeconds)
                        stage = "player"
                        playerToken = mint(runtime, visitorData)
                        visitor = visitorData
                        cookieValue = cookie
                    }
                    stage = "video"
                    val gvs = mint(runtime, videoId)
                    check(System.currentTimeMillis() < expiresAt) { "Token integrity expired" }
                    onEvent(
                        "token minted stage=visitor+video elapsedMs=${(System.nanoTime() - started) / 1_000_000} count=${if (cachedPlayer) 1 else 2}",
                    )
                    PoTokenResult(checkNotNull(playerToken), gvs, visitorData)
                } catch (error: CancellationException) {
                    withContext(NonCancellable) { reset() }
                    throw error
                } catch (error: Exception) {
                    reset()
                    onEvent(
                        "token failed stage=$stage elapsedMs=${(System.nanoTime() - started) / 1_000_000} type=${error::class.simpleName}",
                    )
                    // Never propagate third-party HTTP, JavaScript, CDP or interpreter exception content.
                    if (error is IllegalStateException && error.message?.startsWith("Chromium unavailable") == true) throw error
                    throw IllegalStateException("PO token minting failed at $stage (${error::class.simpleName})")
                }
            }
        }

    override suspend fun prewarm(cookie: String?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed) { "Token provider is closed" }
                if (browser == null) {
                    try {
                        BrowserRuntime(browserExecutable, proxy).also { browser = it }.evaluate("'ready'")
                        onEvent("token browser ready")
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) { reset() }
                        throw error
                    } catch (error: Exception) {
                        reset()
                        if (error is IllegalStateException && error.message?.startsWith("Chromium unavailable") == true) throw error
                        throw IllegalStateException("Chromium token browser failed to start (${error::class.simpleName})")
                    }
                }
            }
        }
    }

    override suspend fun invalidateAttestation() = withContext(Dispatchers.IO) { mutex.withLock { reset() } }

    override suspend fun close() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                closed = true
                try {
                    reset()
                } finally {
                    http.close()
                }
            }
        }

    private fun reset() {
        try {
            browser?.close()
        } finally {
            browser = null
            visitor = null
            cookieValue = null
            playerToken = null
            expiresAt = 0
        }
    }

    private suspend fun mint(
        runtime: BrowserRuntime,
        identifier: String,
    ): String {
        runtime.evaluate(
            """
            globalThis.__po = {state:'running'};
            obtainPoToken(${stringToU8(identifier)}).then(t => {
                globalThis.__po = {state:'ready', value:Array.from(t).join(',')};
            }).catch(() => {globalThis.__po = {state:'error'}});
            'started';
            """.trimIndent(),
        )
        return u8ToBase64(await(runtime))
    }

    private suspend fun await(runtime: BrowserRuntime): String =
        withTimeout(17_000) {
            while (true) {
                val state = Json.parseToJsonElement(runtime.evaluate("JSON.stringify(globalThis.__po || null)")).jsonObject
                when (state["state"]?.jsonPrimitive?.content) {
                    "ready" -> return@withTimeout state["value"]?.jsonPrimitive?.content.orEmpty()
                    "error" -> error("Browser token computation failed")
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private suspend fun io.ktor.client.statement.HttpResponse.bounded(limit: Int): String {
        if (!status.isSuccess()) {
            bodyAsChannel().cancel(null)
            error("Token endpoint HTTP ${status.value}")
        }
        return bodyAsTextLimited(limit)
    }
}

internal fun tokenExpiry(
    nowMs: Long,
    lifetimeSeconds: Long,
): Long {
    require(lifetimeSeconds > 1) { "Token integrity expired" }
    val lifetime = lifetimeSeconds.coerceAtMost(6 * 3600)
    return nowMs + (lifetime - minOf(600L, (lifetime / 10).coerceAtLeast(1))) * 1000
}

internal const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
