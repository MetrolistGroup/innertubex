package com.metrolist.innertubex.harness

import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrProtocolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

// The archive is an allowlist, not a scrub of an arbitrary HAR. Never save URLs or raw bodies.
internal class Capture : Interceptor {
    val entries: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private val json = Json { ignoreUnknownKeys = true }
    private val requestCount = AtomicInteger()

    @Volatile var sabrBudget: MediaBudget? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        check(requestCount.incrementAndGet() <= 1024) { "HTTP request limit" }
        val request = chain.request()
        val kind =
            when {
                request.url.encodedPath == "/youtubei/v1/player" -> "player"
                request.url.encodedPath == "/watch" -> "watch"
                request.url.encodedPath.startsWith("/embed/") -> "embed"
                request.url.encodedPath.startsWith("/api/manifest/") ||
                    request.url.encodedPath.startsWith("/manifest/") -> "hls_playlist"
                request.url.encodedPath.contains("/sabr") ||
                    (
                        request.method == "POST" &&
                            (request.url.encodedPath == "/videoplayback" || request.url.encodedPath.startsWith("/videoplayback/"))
                    ) -> "sabr"
                request.url.encodedPath == "/videoplayback" || request.url.encodedPath.startsWith("/videoplayback/") -> "media"
                else -> "other"
            }
        val body = request.body
        val payload =
            if (kind == "player" &&
                body != null &&
                !body.isOneShot() &&
                !body.isDuplex() &&
                body.contentLength() in 1..LIMIT.toLong()
            ) {
                runCatching { Buffer().also(body::writeTo).readUtf8().let(::safeJson) }.getOrNull()
            } else {
                null
            }
        val start = System.nanoTime()
        val response =
            try {
                chain.proceed(request)
            } catch (error: Exception) {
                entries +=
                    buildJsonObject {
                        put("endpoint", kind)
                        put("method", request.method)
                        put("elapsedMs", elapsed(start))
                        put("errorType", safeError(error))
                        put("payload", payload ?: omitted(body?.contentLength()))
                    }
                throw error
            }
        // Peek at most LIMIT+1, including chunked/unknown-length JSON; never consume the caller's body.
        val size = response.body.contentLength()
        val responseJson =
            if (kind == "player" &&
                size <= LIMIT &&
                response.header("Content-Type").orEmpty().startsWith("application/json")
            ) {
                runCatching {
                    val preview = response.peekBody(LIMIT.toLong() + 1)
                    if (preview.contentLength() > LIMIT) omitted(size) else safeJson(preview.string())
                }.getOrNull()
            } else {
                null
            }
        entries +=
            buildJsonObject {
                put("endpoint", kind)
                put("method", request.method)
                put("status", response.code)
                put("elapsedMs", elapsed(start))
                put("contentType", response.header("Content-Type")?.substringBefore(';')?.takeIf { it in TYPES } ?: "other")
                put("payload", payload ?: omitted(body?.contentLength()))
                put("response", responseJson ?: omitted(size))
                put("range", request.header("Range")?.takeIf { RANGE.matches(it) } ?: "omitted")
                put("requestHeaders", safeHeaders(request.headers))
                put("responseHeaders", safeHeaders(response.headers))
            }
        val budget = sabrBudget.takeIf { kind == "sabr" } ?: return response
        val original = response.body
        val source =
            object : ForwardingSource(original.source()) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val count = super.read(sink, byteCount)
                    if (count > 0) budget.add(count)
                    return count
                }
            }.buffer()
        return response
            .newBuilder()
            .body(
                object : ResponseBody() {
                    override fun contentType() = original.contentType()

                    override fun contentLength() = original.contentLength()

                    override fun source() = source
                },
            ).build()
    }

    internal fun safeHeaders(headers: okhttp3.Headers): JsonObject =
        buildJsonObject {
            headers["Content-Type"]?.let { value ->
                put("Content-Type", value.substringBefore(';').takeIf { it in TYPES } ?: "other")
            }
            headers["Content-Length"]?.toLongOrNull()?.takeIf { it >= 0 }?.let { put("Content-Length", it) }
            headers["Content-Range"]?.let { value ->
                put("Content-Range", value.takeIf { Regex("bytes [0-9]{1,12}-[0-9]{1,12}/[0-9]{1,12}").matches(it) } ?: "omitted")
            }
            headers["X-Youtube-Client-Name"]?.takeIf { value -> CLIENTS.any { it.clientId == value } }?.let {
                put("X-Youtube-Client-Name", it)
            }
            headers["X-Youtube-Client-Version"]?.takeIf { value -> CLIENTS.any { it.clientVersion == value } }?.let {
                put("X-Youtube-Client-Version", it)
            }
        }

    private fun omitted(size: Long?): JsonObject =
        buildJsonObject {
            put("omitted", true)
            put("declaredBytes", size?.takeIf { it >= 0 })
            put("limitBytes", LIMIT)
        }

    internal fun safeJson(text: String): JsonElement =
        if (text.length > LIMIT) {
            omitted(text.length.toLong())
        } else {
            runCatching { sanitize(json.parseToJsonElement(text), 0, "") }.getOrElse { omitted(text.length.toLong()) }
        }

    private fun sanitize(
        value: JsonElement,
        depth: Int,
        path: String,
    ): JsonElement {
        if (depth > 8) return JsonPrimitive("[omitted]")
        return when (value) {
            is JsonObject ->
                JsonObject(
                    value.entries.take(64).associate { (key, child) ->
                        // Only schema-approved keys survive; even unknown key names can carry identifiers.
                        (key.takeIf { it in KEYS } ?: "other") to
                            if (key !in KEYS) {
                                JsonPrimitive("[redacted]")
                            } else if ((
                                    path in setOf("streamingData/formats", "streamingData/adaptiveFormats") &&
                                        key in NUMBERS ||
                                        path == "playbackContext/contentPlaybackContext" &&
                                        key == "signatureTimestamp"
                                ) &&
                                child is JsonPrimitive &&
                                !child.isString
                            ) {
                                child.longOrNull?.takeIf { it in 0..1_000_000_000 }?.let(::JsonPrimitive) ?: JsonPrimitive("[redacted]")
                            } else if (path == "playabilityStatus" &&
                                key == "status" &&
                                child is JsonPrimitive &&
                                child.content in STATUSES
                            ) {
                                JsonPrimitive(child.content)
                            } else if (path == "context/client" &&
                                child is JsonPrimitive &&
                                (
                                    (key == "clientName" && CLIENTS.any { it.clientName == child.content }) ||
                                        (key == "clientVersion" && CLIENTS.any { it.clientVersion == child.content })
                                )
                            ) {
                                JsonPrimitive(child.content)
                            } else {
                                sanitize(child, depth + 1, "$path${if (path.isEmpty()) "" else "/"}$key")
                            }
                    },
                )
            is JsonArray -> JsonArray(value.take(24).map { sanitize(it, depth + 1, path) })
            else -> JsonPrimitive("[redacted]")
        }
    }

    private fun elapsed(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    companion object {
        const val LIMIT = 256 * 1024
        private val CLIENTS = PlaybackClientCatalog.manifests.map { it.client }
        private val RANGE = Regex("bytes=[0-9]{1,12}-[0-9]{1,12}")
        private val TYPES =
            setOf(
                "application/json",
                "application/vnd.yt-ump",
                "application/octet-stream",
                "text/html",
                "audio/webm",
                "audio/mp4",
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "video/mp2t",
            )
        private val KEYS =
            setOf(
                "context",
                "client",
                "clientName",
                "clientVersion",
                "videoId",
                "playabilityStatus",
                "status",
                "streamingData",
                "adaptiveFormats",
                "formats",
                "itag",
                "bitrate",
                "mimeType",
                "contentLength",
                "audioSampleRate",
                "serverAbrStreamingUrl",
                "url",
                "signatureCipher",
                "poToken",
                "serviceIntegrityDimensions",
                "playbackContext",
                "contentPlaybackContext",
                "signatureTimestamp",
                "racyCheckOk",
                "contentCheckOk",
            )
        private val NUMBERS = setOf("itag", "bitrate", "audioSampleRate")
        private val STATUSES =
            setOf("OK", "UNPLAYABLE", "LOGIN_REQUIRED", "ERROR", "LIVE_STREAM_OFFLINE", "CONTENT_CHECK_REQUIRED", "AGE_CHECK_REQUIRED")
    }
}

internal fun safeEvent(event: InnerTubeLogEvent): String {
    val tag =
        event.tag.takeIf { it in setOf("InnerTubeExtractor", "PlayerClientDirector", "SabrAudioStream", "YtConfigParser", "YouTubeCipher") }
            ?: "other"
    val message =
        event.message.takeIf {
            it in
                setOf(
                    "player response selected",
                    "player response rejected",
                    "player response decode failed",
                    "invalid player response",
                    "player response missing status",
                    "tokenized fallback required",
                    "token binding rejected",
                    "token required for playback stability",
                    "watch page cache unusable",
                    "authenticated watch page retry",
                    "player response batch completed",
                    "prewarm completed",
                )
        } ?: "event"
    val details =
        event.details.mapNotNull { (key, value) ->
            when {
                key in
                    setOf(
                        "elapsedMs",
                        "candidateCount",
                        "rejectedCount",
                        "excludedCount",
                        "resultCount",
                        "formatCount",
                        "httpStatus",
                        "ageMs",
                    ) ->
                    value.toLongOrNull()?.takeIf { it in 0..1_000_000_000 }?.let { "$key=$it" }
                key in setOf("tokenPresent", "authenticated", "streamingPresent", "wantVideo", "hlsPresent", "sabrPresent") ->
                    value.takeIf { it == "true" || it == "false" }?.let { "$key=$it" }
                key == "client" ->
                    value
                        .takeIf { name ->
                            PlaybackClientCatalog.manifests.any { it.client.clientName == name }
                        }?.let { "client=$it" }
                else -> null
            }
        }
    return "${event.level}:$tag:$message ${details.joinToString(" ")}".trimEnd()
}

@OptIn(ExperimentalSabrApi::class)
internal fun safeError(error: Throwable): String =
    when (error) {
        is kotlinx.coroutines.TimeoutCancellationException -> "deadline"
        is java.net.UnknownHostException -> "dns"
        is java.net.ConnectException -> "connect"
        is java.net.SocketTimeoutException -> "socket_timeout"
        is SabrProtocolException -> error.kind.name
        is java.io.IOException -> if (error.message == "capture_cleanup") "capture_cleanup" else "io"
        is IllegalStateException ->
            when (error.message) {
                "Media HTTP status" -> "media_http_status"
                "Invalid content range" -> "content_range"
                "Non-media response" -> "non_media_response"
                "Incomplete range" -> "incomplete_range"
                "Later range not verified" -> "later_range_unverified"
                "Media ended before required duration" -> "short_media"
                "Media byte limit" -> "media_byte_limit"
                "SABR media gap" -> "sabr_media_gap"
                "Insufficient SABR media" -> "short_sabr_media"
                "Decode failed" -> "decode_failed"
                "Decoder deadline" -> "decoder_deadline"
                "Decoder cleanup deadline" -> "decoder_cleanup"
                "Range bridge cleanup deadline" -> "range_bridge_cleanup"
                "Insufficient decoded audio" -> "short_decoded_audio"
                "HTTP request limit" -> "http_request_limit"
                "ffmpeg_missing" -> "ffmpeg_missing"
                "Decoder stalled" -> "decoder_stalled"
                "Decoder seek mismatch" -> "decoder_seek_mismatch"
                "Decoder timestamp missing" -> "decoder_timestamp_missing"
                "Seek settle deadline" -> "seek_settle_deadline"
                "Seek did not use network range" -> "network_seek_missing"
                "Seek outside media", "Seek outside HLS window" -> "seek_unavailable"
                "SABR seek position" -> "sabr_seek_position"
                "HLS DRM unsupported", "Encrypted HLS init unsupported" -> "hls_drm"
                "HLS MP4 unavailable" -> "hls_quality_unavailable"
                "Invalid HLS endpoint" -> "hls_endpoint"
                "Invalid HLS playlist", "HLS playlist limit", "Empty HLS playlist" -> "hls_playlist"
                "HLS body limit" -> "hls_body_limit"
                "HLS window advanced without timebase" -> "hls_window_advanced"
                "HLS refresh limit" -> "hls_refresh_limit"
                "HLS discontinuity" -> "hls_discontinuity"
                "Invalid HLS key", "Invalid HLS IV" -> "hls_key"
                "Invalid HLS range" -> "hls_range"
                "HLS media failure", "SABR media failure" -> "media_failure"
                else -> "failure"
            }
        else -> "failure"
    }
