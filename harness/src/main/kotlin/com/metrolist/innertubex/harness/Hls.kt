package com.metrolist.innertubex.harness

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ExtractedStream
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Accept only the YouTube media and manifest endpoints. Do not give remote playlists to FFmpeg.
internal fun allowedHls(
    url: String,
    localFixture: Boolean = false,
): Boolean =
    runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return@runCatching false
        val path = uri.rawPath.orEmpty()
        val origin =
            (
                uri.scheme == "https" &&
                    (uri.port == -1 || uri.port == 443) &&
                    listOf("googlevideo.com", "youtube.com").any { host == it || host.endsWith(".$it") }
            ) ||
                (localFixture && uri.scheme == "http" && host == "127.0.0.1" && uri.port in 1..65535)
        origin &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null &&
            url.length <= 8192 &&
            !path.contains("..") &&
            !Regex("(?i)%(2e|2f|5c|00|25|3f|23)").containsMatchIn(path) &&
            !path.contains('\\') &&
            (
                path == "/videoplayback" ||
                    path.startsWith("/videoplayback/") ||
                    path.startsWith("/api/manifest/") ||
                    path.startsWith("/manifest/") ||
                    (localFixture && path.startsWith("/fixture/"))
            )
    }.getOrDefault(false)

internal data class HlsPart(
    val uri: String,
    val start: Long,
    val length: Long,
    val key: String? = null,
    val iv: ByteArray? = null,
    val map: String? = null,
    val mapRange: String? = null,
    val range: String? = null,
    val discontinuity: Int = 0,
)

internal data class HlsList(
    val parts: List<HlsPart>,
    val end: Boolean,
    val sequence: Long,
    val target: Long,
)

internal fun hlsAttrs(text: String): Map<String, String> =
    Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        .findAll(text)
        .associate { it.groupValues[1] to it.groupValues[2].trim('"') }

internal fun hlsRange(
    value: String,
    previousEnd: Long = 0L,
): Pair<String, Long> {
    val pair = value.split('@')
    check(pair.size in 1..2) { "Invalid HLS range" }
    val size = pair[0].toLongOrNull()
    val offset = pair.getOrNull(1)?.toLongOrNull() ?: previousEnd
    check(size != null && size in 1..8_388_608 && offset >= 0 && offset < Long.MAX_VALUE - size) { "Invalid HLS range" }
    return "bytes=$offset-${offset + size - 1}" to offset + size
}

internal fun hlsUri(
    base: String,
    relative: String,
    localFixture: Boolean,
): String {
    check(relative.length in 1..8192 && !relative.contains('\\')) { "Invalid HLS endpoint" }
    val url = URI(base).resolve(relative).toString()
    check(allowedHls(url, localFixture)) { "Invalid HLS endpoint" }
    return url
}

internal fun hlsMaster(
    body: String,
    base: String,
    localFixture: Boolean,
    bitrate: Int? = null,
): String? = hlsSelection(body, base, localFixture, bitrate, AudioQuality.AUTO)?.first

internal fun hlsSelection(
    body: String,
    base: String,
    localFixture: Boolean,
    bitrate: Int?,
    quality: AudioQuality,
): Pair<String, String>? {
    val lines = body.lines().map(String::trim).filter(String::isNotEmpty)
    check(lines.size <= 8192 && lines.firstOrNull() == "#EXTM3U") { "Invalid HLS playlist" }
    val variants =
        lines.mapIndexedNotNull { i, line ->
            if (line.startsWith("#EXT-X-STREAM-INF:") && i + 1 < lines.size) {
                val attrs = hlsAttrs(line.substringAfter(':'))
                attrs["BANDWIDTH"]?.toLongOrNull()?.let { Triple(it, lines[i + 1], attrs["AUDIO"]) }
            } else {
                null
            }
        }
    // Total muxed BANDWIDTH measures video as well, not audio quality. Prefer a separate audio
    // group, then a declared audio-only variant, otherwise the smallest muxed representation.
    val audio =
        lines
            .filter { it.startsWith("#EXT-X-MEDIA:") }
            .map { hlsAttrs(it.substringAfter(':')) }
            .filter { it["TYPE"] == "AUDIO" && it["URI"] != null }
    val audioUris =
        lines
            .mapIndexedNotNull { i, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:") || i + 1 >= lines.size) return@mapIndexedNotNull null
                val attrs = hlsAttrs(line.substringAfter(':'))
                val codecs = attrs["CODECS"]?.split(',') ?: return@mapIndexedNotNull null
                lines[i + 1].takeIf {
                    "RESOLUTION" !in attrs &&
                        codecs.isNotEmpty() &&
                        codecs.all { codec ->
                            codec.trim().let { it.startsWith("mp4a.") || it in setOf("opus", "flac", "ac-3", "ec-3") }
                        }
                }
            }.toSet()
    val separate = variants.filter { variant -> audio.any { it["GROUP-ID"] == variant.third } }
    val audioOnly = variants.filter { it.second in audioUris }
    val pool = separate.ifEmpty { audioOnly.ifEmpty { variants } }
    val selected =
        when {
            separate.isNotEmpty() || audioOnly.isEmpty() -> pool.minByOrNull { it.first }
            bitrate != null -> pool.minByOrNull { kotlin.math.abs(it.first - bitrate) }
            quality == AudioQuality.HIGH || quality == AudioQuality.MP4 -> pool.maxByOrNull { it.first }
            else -> pool.minByOrNull { it.first }
        }
    val rendition =
        audio.firstOrNull { selected?.third != null && it["GROUP-ID"] == selected.third && it["DEFAULT"] == "YES" }
            ?: audio.firstOrNull { selected?.third != null && it["GROUP-ID"] == selected.third }
    val uri = rendition?.get("URI") ?: selected?.second ?: return null
    val label = if (rendition != null) "AUDIO_${audio.indexOf(rendition)}" else "VARIANT_${variants.indexOf(selected)}"
    return hlsUri(base, uri, localFixture) to label
}

internal fun parseHls(
    body: String,
    base: String,
    localFixture: Boolean = false,
): HlsList {
    val lines = body.lines().map(String::trim).filter(String::isNotEmpty)
    check(lines.size <= 8192 && lines.firstOrNull() == "#EXTM3U") { "Invalid HLS playlist" }
    val sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
    val discontinuitySequence =
        lines.firstOrNull { it.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") }?.substringAfter(':')?.let {
            checkNotNull(it.toIntOrNull()) { "Invalid HLS playlist" }
        } ?: 0
    val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')?.toLongOrNull() ?: 6L
    check(sequence >= 0 && discontinuitySequence >= 0 && target in 1..60) { "Invalid HLS playlist" }
    val parts = mutableListOf<HlsPart>()
    var start = 0L
    var duration: Long? = null
    var key: String? = null
    var iv: ByteArray? = null
    var map: String? = null
    var mapRange: String? = null
    var range: String? = null
    var lastEnd = 0L
    var lastUri: String? = null
    var discontinuity = discontinuitySequence
    for (line in lines) {
        when {
            line.startsWith("#EXTINF:") -> {
                val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                check(seconds != null && seconds > 0 && seconds <= 60) { "Invalid HLS duration" }
                duration = (seconds * 1000).toLong()
            }
            line.startsWith("#EXT-X-KEY:") -> {
                val attrs = hlsAttrs(line.substringAfter(':'))
                when (attrs["METHOD"]) {
                    "NONE" -> {
                        key = null
                        iv = null
                    }
                    "AES-128" -> {
                        key = hlsUri(base, checkNotNull(attrs["URI"]), localFixture)
                        iv =
                            attrs["IV"]?.let { value ->
                                check(value.matches(Regex("0x[0-9a-fA-F]{32}"))) { "Invalid HLS IV" }
                                value
                                    .drop(2)
                                    .chunked(2)
                                    .map { it.toInt(16).toByte() }
                                    .toByteArray()
                            }
                    }
                    else -> error("HLS DRM unsupported")
                }
            }
            line.startsWith("#EXT-X-MAP:") -> {
                check(key == null) { "Encrypted HLS init unsupported" }
                val attrs = hlsAttrs(line.substringAfter(':'))
                map = hlsUri(base, checkNotNull(attrs["URI"]), localFixture)
                mapRange =
                    attrs["BYTERANGE"]?.let {
                        check('@' in it) { "Invalid HLS range" }
                        hlsRange(it).first
                    }
            }
            line.startsWith("#EXT-X-BYTERANGE:") -> range = line.substringAfter(':')
            line == "#EXT-X-DISCONTINUITY" -> {
                check(discontinuity < Int.MAX_VALUE) { "Invalid HLS playlist" }
                discontinuity++
            }
            !line.startsWith('#') -> {
                val length = checkNotNull(duration) { "HLS segment without duration" }
                check(parts.size < 4096 && start <= Long.MAX_VALUE - length) { "HLS playlist limit" }
                val uri = hlsUri(base, line, localFixture)
                if (range != null && '@' !in range) check(uri == lastUri) { "Invalid HLS range" }
                val normalizedRange = range?.let { hlsRange(it, lastEnd).also { result -> lastEnd = result.second }.first }
                parts += HlsPart(uri, start, length, key, iv, map, mapRange, normalizedRange, discontinuity)
                lastUri = uri
                start += length
                duration = null
                range = null
            }
        }
    }
    check(parts.isNotEmpty() && duration == null) { "Empty HLS playlist" }
    return HlsList(parts, "#EXT-X-ENDLIST" in lines, sequence, target)
}

internal class HlsSession(
    private val http: HttpClient,
    private val stream: ExtractedStream,
    private val budget: MediaBudget,
    private val localFixture: Boolean = false,
    private val quality: AudioQuality = AudioQuality.AUTO,
) {
    var representation: String = "MEDIA_0"
        private set
    private var selected: String? = null
    private var firstStart = 0L
    private var last: HlsList? = null

    private suspend fun load(): HlsList {
        if (selected == null) {
            val root = fetch(stream.audioUrl, limit = 262_144).decodeToString()
            val choice = hlsSelection(root, stream.audioUrl, localFixture, stream.bitrate, quality)
            selected = choice?.first ?: stream.audioUrl
            representation = choice?.second ?: "MEDIA_0"
            last = if (selected == stream.audioUrl) parseHls(root, stream.audioUrl, localFixture) else null
        }
        last?.takeIf { it.end }?.let { return it }
        val playlist = parseHls(fetch(checkNotNull(selected), limit = 262_144).decodeToString(), checkNotNull(selected), localFixture)
        last?.let { previous ->
            val skipped = playlist.sequence - previous.sequence
            check(skipped in 0..previous.parts.size.toLong()) { "HLS window advanced without timebase" }
            firstStart += previous.parts.take(skipped.toInt()).sumOf { it.length }
        }
        last = playlist
        return playlist
    }

    private suspend fun fetch(
        url: String,
        range: String? = null,
        limit: Int = 8_388_608,
    ): ByteArray {
        check(allowedHls(url, localFixture)) { "Invalid HLS endpoint" }
        return http
            .prepareGet(url) {
                // Host-provided auth is scoped to the original host, never to a nested playlist host.
                if (URI(url).host ==
                    URI(stream.audioUrl).host
                ) {
                    stream.headers.filterKeys { !it.equals(HttpHeaders.Range, true) }.forEach { (k, v) -> header(k, v) }
                }
                if (range != null) header(HttpHeaders.Range, range)
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                check(response.status.value == if (range == null) 200 else 206) { "Media HTTP status" }
                if (range !=
                    null
                ) {
                    check(
                        response.headers[HttpHeaders.ContentRange]?.matches(Regex("${range.replace("bytes=", "bytes ")}/[0-9]+")) == true,
                    ) { "Invalid content range" }
                }
                val data = java.io.ByteArrayOutputStream()
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(32768)
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    check(data.size() + count <= limit) { "HLS body limit" }
                    budget.add(count.toLong())
                    data.write(buffer, 0, count)
                }
                if (range != null) {
                    val expected = range.substringAfter('-').toLong() - range.substringAfter('=').substringBefore('-').toLong() + 1
                    check(data.size().toLong() == expected) { "Incomplete range" }
                }
                data.toByteArray()
            }
    }

    suspend fun initialTarget(
        initialMs: Long,
        backMs: Long,
    ): Long {
        val playlist = load()
        if (playlist.end) return 0
        val windowMs = playlist.parts.last().start + playlist.parts.last().length
        check(windowMs >= initialMs + backMs) { "Seek outside HLS window" }
        // A live stage must start far enough behind the edge to play forward, but not so far back
        // that the 20-second backward seek expires as MEDIA-SEQUENCE advances during playback.
        return firstStart + windowMs - initialMs
    }

    suspend fun play(
        target: Long,
        duration: Long,
        allowBoundaryShort: Boolean = false,
        sample: (Long, Long, Long) -> Unit,
    ): Pair<Long, Long> {
        val list = load()
        check(quality != AudioQuality.MP4 || list.parts.all { it.map != null }) { "HLS MP4 unavailable" }
        val relative = target - firstStart
        val index = list.parts.indexOfLast { it.start <= relative }
        check(index >= 0 && (relative < list.parts.last().start + list.parts.last().length)) { "Seek outside HLS window" }
        if (list.end) check(relative + duration <= list.parts.last().start + list.parts.last().length + 1000) { "Seek outside HLS window" }
        val part = list.parts[index]
        val boundary =
            list.parts
                .drop(index + 1)
                .firstOrNull { it.discontinuity != part.discontinuity }
                ?.start
                ?.plus(firstStart)
        if (boundary != null && boundary < target + duration) {
            val before = play(target, boundary - target, allowBoundaryShort = true, sample = sample)
            val remaining = duration * 16 - before.first
            val after =
                play(boundary, (remaining + 15) / 16) { _, bytes, _ ->
                    val played = minOf(duration * 16, before.first + bytes)
                    sample(target + played / 16, played, before.second)
                }
            return minOf(duration * 16, before.first + after.first) to before.second
        }
        // No init map does not imply MPEG-TS: separate audio renditions can carry ADTS AAC.
        val decoder = decoder("pipe:0", relative - part.start, if (part.map != null) listOf("-f", "mp4") else emptyList())
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        val writer =
            scope.async(Dispatchers.IO) {
                try {
                    var previousMap: Pair<String, String?>? = null
                    var active = list
                    var nextSequence = list.sequence + index
                    var refreshes = 0
                    while (true) {
                        val at = nextSequence - active.sequence
                        check(at >= 0) { "Seek outside HLS window" }
                        if (at >= active.parts.size) {
                            if (active.end) break
                            check(++refreshes <= 128) { "HLS refresh limit" }
                            delay((active.target * 500).coerceIn(500, 2_000))
                            active = load()
                            continue
                        }
                        val item = active.parts[at.toInt()]
                        if (firstStart + item.start >= target + duration + 2000) break
                        if (item.discontinuity != part.discontinuity) break
                        if (item.map != null && (item.map to item.mapRange) != previousMap) {
                            decoder.outputStream.write(fetch(item.map, item.mapRange))
                            previousMap = item.map to item.mapRange
                        }
                        val encrypted = fetch(item.uri, item.range)
                        val content =
                            if (item.key != null) {
                                val key = fetch(item.key, limit = 16)
                                check(key.size == 16) { "Invalid HLS key" }
                                val iv =
                                    item.iv
                                        ?: ByteArray(16).also {
                                            java.nio.ByteBuffer
                                                .wrap(it)
                                                .putLong(8, nextSequence)
                                        }
                                Cipher
                                    .getInstance("AES/CBC/PKCS5Padding")
                                    .apply {
                                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                    }.doFinal(encrypted)
                            } else {
                                encrypted
                            }
                        decoder.outputStream.write(content)
                        nextSequence++
                        if (firstStart + item.start + item.length >= target + duration + 2000) break
                    }
                } finally {
                    runCatching { decoder.outputStream.close() }
                }
            }
        try {
            val result =
                try {
                    consumePcm(
                        decoder,
                        target,
                        duration,
                        shortToleranceMs = if (allowBoundaryShort) 50 else 0,
                        checkSource = { if (writer.isCompleted) writer.await() },
                        onSample = sample,
                    )
                } catch (error: Throwable) {
                    if (writer.isCompleted) writer.await()
                    throw error
                }
            if (writer.isCompleted) writer.await()
            return result
        } finally {
            writer.cancel()
            stopDecoder(decoder)
            writer.join()
        }
    }
}
