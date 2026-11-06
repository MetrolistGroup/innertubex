package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.cipher.getTextWithoutRedirects
import com.metrolist.innertubex.d
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.w
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

public class YtConfigParserImpl(
    private val httpClient: HttpClient,
    private val innerTube: InnerTube,
    private val remotePlayerConfigStore: RemotePlayerConfigStore? = null,
    private val logger: InnerTubeLogger = InnerTubeLogger.NONE,
    /** When set, the signature-timestamp fallback reuses the cipher service's player download. */
    private val cipherService: YouTubeCipherService? = null,
) : YtConfigParser {
    override suspend fun fetchConfig(
        videoId: String,
        useLoginCookies: Boolean,
    ): PlayerConfig {
        require(SAFE_VIDEO_ID.matches(videoId)) { "Invalid video ID" }
        return fetchConfigPage(
            videoId,
            useLoginCookies,
            "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1",
            "watch",
        )
    }

    override suspend fun fetchEmbeddedConfig(
        videoId: String,
        useLoginCookies: Boolean,
    ): PlayerConfig {
        require(SAFE_VIDEO_ID.matches(videoId)) { "Invalid video ID" }
        return fetchConfigPage(
            videoId,
            useLoginCookies,
            "https://www.youtube.com/embed/$videoId?html5=1",
            "embed",
            "https://www.reddit.com/",
        )
    }

    private suspend fun fetchConfigPage(
        videoId: String,
        useLoginCookies: Boolean,
        pageUrl: String,
        pageKind: String,
        referer: String? = null,
    ): PlayerConfig {
        val cookie =
            innerTube.cookie
                ?.takeIf { useLoginCookies }
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.startsWith("PREF=", ignoreCase = true) }
                ?.joinToString("; ")
                ?.takeIf { it.isNotEmpty() }

        // The fields sit in the first ~60 KB of a ~1.4 MB streamed page; STS often only near its middle,
        // so stop once it is either on the page or already known from the remote player config.
        var storeStsPlayerUrl: String? = null
        var storeSts: Int? = null
        val hasConfig: suspend (String) -> Boolean = hasConfig@{ html ->
            val playerUrl = extractPlayerUrl(html) ?: return@hasConfig false
            if (extractVisitorData(html) == null || extractClientVersion(html) == null) return@hasConfig false
            if (pageKind == "embed" && extractEncryptedHostFlags(html) == null) return@hasConfig false
            if (extractSignatureTimestamp(html) != null) return@hasConfig true
            if (storeStsPlayerUrl != playerUrl) {
                storeStsPlayerUrl = playerUrl
                storeSts = remotePlayerConfigStore?.getCachedSignatureTimestamp(playerUrl)
            }
            storeSts != null
        }

        suspend fun fetchHtml(requestCookie: String?) =
            getText(Url(pageUrl), PAGE_MAX_BYTES, hasConfig) {
                header(HttpHeaders.UserAgent, YouTubeClient.USER_AGENT_WEB)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                referer?.let { header("Referer", it) }
                requestCookie?.let { header(HttpHeaders.Cookie, it) }
                header(HttpHeaders.AcceptLanguage, innerTube.locale.acceptLanguageHeader())
                timeout {
                    requestTimeoutMillis = PAGE_TIMEOUT_MS
                    connectTimeoutMillis = PAGE_TIMEOUT_MS
                    socketTimeoutMillis = PAGE_TIMEOUT_MS
                }
            }
        val html =
            try {
                fetchHtml(cookie)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (cookie == null) throw error
                logger.w(
                    TAG,
                    "authenticated config page unavailable; retrying without cookies",
                    details = mapOf("exceptionType" to (error::class.simpleName ?: "Exception")),
                )
                fetchHtml(null)
            }
        val playerUrl =
            extractPlayerUrl(html) ?: fetchIframePlayerUrl()
                ?: error("Unable to parse YouTube player JavaScript URL")
        val sts = extractSignatureTimestamp(html)
        val resolvedSts = sts ?: remotePlayerConfigStore?.getSignatureTimestamp(playerUrl) ?: fetchPlayerSignatureTimestamp(playerUrl)
        val visitorData = extractVisitorData(html)
        val encryptedHostFlags = extractEncryptedHostFlags(html).takeIf { pageKind == "embed" }
        logger.d(
            TAG,
            "fetchConfig parsed page=$pageKind htmlSize=${html.length} " +
                "hasSts=${resolvedSts != null} hasVisitorData=${visitorData != null} " +
                "hasEncryptedHostFlags=${encryptedHostFlags != null}",
        )
        return PlayerConfig(playerUrl, resolvedSts, visitorData, extractClientVersion(html), encryptedHostFlags)
    }

    private suspend fun fetchPlayerSignatureTimestamp(playerUrl: String): Int? =
        try {
            val js =
                cipherService?.playerCode(playerUrl) ?: getText(Url(playerUrl), PLAYER_JS_MAX_BYTES) {
                    header(HttpHeaders.UserAgent, YouTubeClient.USER_AGENT_WEB)
                    timeout {
                        requestTimeoutMillis = PLAYER_TIMEOUT_MS
                        connectTimeoutMillis = PLAYER_TIMEOUT_MS
                        socketTimeoutMillis = PLAYER_TIMEOUT_MS
                    }
                }
            extractSignatureTimestamp(js)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.d(TAG, "player JS timestamp unavailable type=${error::class.simpleName ?: "Exception"}")
            null
        }

    private suspend fun fetchIframePlayerUrl(): String? =
        try {
            val api =
                getText(Url("https://www.youtube.com/iframe_api"), IFRAME_MAX_BYTES) {
                    header(HttpHeaders.UserAgent, YouTubeClient.USER_AGENT_WEB)
                    timeout {
                        requestTimeoutMillis = PLAYER_TIMEOUT_MS
                        connectTimeoutMillis = PLAYER_TIMEOUT_MS
                        socketTimeoutMillis = PLAYER_TIMEOUT_MS
                    }
                }
            extractPlayerId(api)?.let { validatePlayerUrl("https://www.youtube.com/s/player/$it/player_ias.vflset/en_GB/base.js") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.d(TAG, "iframe player URL unavailable type=${error::class.simpleName ?: "Exception"}")
            null
        }

    internal fun extractPlayerUrl(html: String): String? {
        // Only the loose path pattern needs the whole page unescaped; the keyed patterns unescape their match.
        val keyed =
            KEYED_PLAYER_URL_PATTERNS.asSequence().mapNotNull {
                it
                    .find(html)
                    ?.groupValues
                    ?.get(1)
                    ?.unescapeJs()
            }
        val loose =
            sequence {
                LOOSE_PLAYER_URL_PATTERN
                    .find(html.unescapeJs())
                    ?.groupValues
                    ?.get(1)
                    ?.let { yield(it) }
            }
        return (keyed + loose)
            .mapNotNull { path ->
                validatePlayerUrl(
                    if (path.startsWith("http")) path else "https://www.youtube.com${if (path.startsWith('/')) path else "/$path"}",
                )
            }.firstOrNull()
    }

    private fun String.unescapeJs(): String = replace("\\/", "/").replace("\\u0026", "&")

    internal fun extractPlayerId(script: String): String? = PLAYER_ID_REGEX.find(script.replace("\\/", "/"))?.groupValues?.get(1)

    internal fun extractSignatureTimestamp(html: String): Int? {
        if (html.trimStart().startsWith('<')) {
            return SCRIPT_CONTENT.findAll(html).firstNotNullOfOrNull { scanSignatureTimestamp(it.groupValues[1]) }
        }
        return scanSignatureTimestamp(html)
    }

    private fun scanSignatureTimestamp(html: String): Int? {
        var position = 0
        while (position < html.length) {
            val start = position
            val char = html[position++]
            val key: String
            when {
                char == '/' && html.getOrNull(position) == '/' -> {
                    position = html.indexOf('\n', position + 1).takeIf { it >= 0 } ?: html.length
                    continue
                }

                char == '/' && html.getOrNull(position) == '*' -> {
                    position = html.indexOf("*/", position + 1).takeIf { it >= 0 }?.plus(2) ?: html.length
                    continue
                }

                char == '/' -> {
                    var previous = start - 1
                    while (previous >= 0 && html[previous].isWhitespace()) previous--
                    // shortcut: recognizes regexes at expression starts, extend only for observed player syntax.
                    if (previous < 0 || html[previous] in "=(:,[!&|?;{}") {
                        var inClass = false
                        while (position < html.length && html[position] != '\n' && html[position] != '\r') {
                            when (html[position++]) {
                                '\\' -> position++
                                '[' -> inClass = true
                                ']' -> inClass = false
                                '/' -> if (!inClass) break
                            }
                        }
                    }
                    continue
                }

                char == '"' || char == '\'' || char == '`' -> {
                    while (position < html.length && html[position] != char) {
                        if (html[position] == '\\') position++
                        position++
                    }
                    if (position >= html.length) break
                    key = html.substring(start + 1, position)
                    position++
                }

                char.isLetter() || char == '_' || char == '$' -> {
                    while (position < html.length &&
                        (html[position].isLetterOrDigit() || html[position] == '_' || html[position] == '$')
                    ) {
                        position++
                    }
                    key = html.substring(start, position)
                }

                else -> {
                    continue
                }
            }
            if (key !in TIMESTAMP_KEYS) continue
            while (position < html.length && html[position].isWhitespace()) position++
            if (html.getOrNull(position) != ':') continue
            position++
            while (position < html.length && html[position].isWhitespace()) position++
            val numberStart = position
            while (position < html.length && html[position] in '0'..'9') position++
            if (position - numberStart == 5 && html.getOrNull(position)?.let { it.isLetterOrDigit() || it == '.' || it == '_' } != true) {
                return html.substring(numberStart, position).toIntOrNull()
            }
        }
        return null
    }

    internal fun extractClientVersion(html: String): String? = extractConfigString(html, CLIENT_VERSION_REGEX)

    internal fun extractEncryptedHostFlags(html: String): String? = extractConfigString(html, ENCRYPTED_HOST_FLAGS_REGEX)

    private fun extractVisitorData(html: String): String? = extractConfigString(html, VISITOR_DATA_REGEX)

    private fun extractConfigString(
        html: String,
        key: Regex,
    ): String? {
        for (match in key.findAll(html)) {
            val start = match.range.last + 1
            if (html.getOrNull(start) != '"') continue
            var position = start + 1
            val limit = minOf(html.length, start + MAX_CONFIG_STRING_LENGTH)
            while (position < limit) {
                when (html[position++]) {
                    '\\' -> {
                        position++
                    }

                    '"' -> {
                        val value = runCatching { CONFIG_JSON.decodeFromString<String>(html.substring(start, position)) }.getOrNull()
                        if (!value.isNullOrEmpty()) return value
                        break
                    }
                }
            }
        }
        return null
    }

    private fun validatePlayerUrl(value: String): String? =
        runCatching { Url(value) }
            .getOrNull()
            ?.takeIf {
                it.protocol.name == "https" && it.port == 443 && approvedYouTubeHost(it.host) && it.user == null && it.password == null &&
                    PLAYER_PATH.matches(it.encodedPath)
            }?.toString()

    private suspend fun getText(
        url: Url,
        maxBytes: Int,
        stopWhen: (suspend (String) -> Boolean)? = null,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): String {
        var currentUrl = url
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = httpClient.getTextWithoutRedirects(currentUrl, maxBytes, stopWhen, configure)
            if (response.status.isSuccess()) return requireNotNull(response.body)

            val redirectUrl =
                response.headers[HttpHeaders.Location]
                    ?.takeIf { response.status.value in REDIRECT_STATUS_CODES && redirectCount < MAX_REDIRECTS }
                    ?.let { location -> runCatching { URLBuilder(currentUrl).takeFrom(location).build() }.getOrNull() }
                    ?.takeIf(::approvedYouTubeUrl)
                    ?: error("HTTP ${response.status.value}")
            currentUrl = redirectUrl
        }
        error("Too many redirects")
    }

    private fun approvedYouTubeUrl(url: Url): Boolean =
        url.protocol.name == "https" &&
            url.port == 443 &&
            approvedYouTubeHost(url.host) &&
            url.user == null &&
            url.password == null &&
            (
                url.encodedPath == "/watch" ||
                    url.encodedPath == "/iframe_api" ||
                    EMBED_PATH.matches(url.encodedPath) ||
                    PLAYER_PATH.matches(url.encodedPath)
            )

    private fun approvedYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

    private companion object {
        private const val TAG = "YtConfigParser"
        private const val PAGE_TIMEOUT_MS = 10_000L
        private const val PLAYER_TIMEOUT_MS = 8_000L
        private const val PAGE_MAX_BYTES = 4 * 1024 * 1024
        private const val PLAYER_JS_MAX_BYTES = 8 * 1024 * 1024
        private const val IFRAME_MAX_BYTES = 1 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val SAFE_VIDEO_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val EMBED_PATH = Regex("/embed/[A-Za-z0-9_-]{1,64}")
        private val PLAYER_PATH = Regex("/s/player/[A-Za-z0-9_-]+/.+\\.js")
        private val KEYED_PLAYER_URL_PATTERNS =
            listOf(
                Regex("\"PLAYER_JS_URL\":\"([^\"]+)\""),
                Regex("\"jsUrl\":\"([^\"]+)\""),
            )
        private val LOOSE_PLAYER_URL_PATTERN = Regex("""(?<![A-Za-z0-9:/])(/s/player/[A-Za-z0-9_-]+/[^"'\\]*\.js[^"'\\]*)""")
        private val PLAYER_ID_REGEX = Regex("/s/player/([a-zA-Z0-9_-]+)/")
        private val TIMESTAMP_KEYS = setOf("signatureTimestamp", "sts", "STS")
        private val SCRIPT_CONTENT = Regex("""<script\b[^>]*>([\s\S]*?)(?:</script\s*>|$)""", RegexOption.IGNORE_CASE)
        private const val MAX_CONFIG_STRING_LENGTH = 64 * 1024

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val CONFIG_JSON = Json { exceptionsWithDebugInfo = false }
        private val CLIENT_VERSION_REGEX = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*""")
        private val ENCRYPTED_HOST_FLAGS_REGEX = Regex(""""encryptedHostFlags"\s*:\s*""")
        private val VISITOR_DATA_REGEX = Regex(""""(?:VISITOR_DATA|visitorData)"\s*:\s*""")
    }
}
