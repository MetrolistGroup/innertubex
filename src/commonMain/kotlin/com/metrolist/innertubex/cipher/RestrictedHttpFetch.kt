package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.bodyAsTextLimited
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess

internal data class RestrictedTextResponse(
    val status: HttpStatusCode,
    val headers: Headers,
    val body: String?,
)

/**
 * Creates a redirect-disabled client sharing this client's engine without taking engine ownership or
 * inheriting caller plugins (redirects, cookies, auth interceptors).
 */
internal fun HttpClient.createNoRedirectClient(): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        // A fresh client does not inherit the caller's timeouts; per-request timeout {} blocks override these.
        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
        }
    }

/**
 * Executes a bounded GET on an already-isolated no-redirect [HttpClient].
 */
internal suspend fun HttpClient.getTextOnDirectClient(
    url: Url,
    maxBytes: Int,
    stopWhen: (suspend (String) -> Boolean)? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
): RestrictedTextResponse =
    prepareGet(url, configure).execute { response ->
        if (response.status.isSuccess()) {
            RestrictedTextResponse(response.status, response.headers, response.bodyAsTextLimited(maxBytes, stopWhen))
        } else {
            response.bodyAsChannel().cancel(null)
            RestrictedTextResponse(response.status, response.headers, null)
        }
    }

/**
 * Uses the caller's engine without inheriting its redirect policy or taking engine ownership.
 * The body is streamed, so [maxBytes] bounds memory and [stopWhen] can end the download early.
 */
internal suspend fun HttpClient.getTextWithoutRedirects(
    url: Url,
    maxBytes: Int,
    stopWhen: (suspend (String) -> Boolean)? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
): RestrictedTextResponse {
    val directClient = createNoRedirectClient()
    return try {
        directClient.getTextOnDirectClient(url, maxBytes, stopWhen, configure)
    } finally {
        directClient.close()
    }
}

private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
private const val DEFAULT_SOCKET_TIMEOUT_MS = 10_000L
