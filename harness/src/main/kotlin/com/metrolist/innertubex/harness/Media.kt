package com.metrolist.innertubex.harness

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.AuthenticationPolicy
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenRequirement
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrChunk
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun matchesProfile(
    requested: String,
    stream: ExtractedStream,
): Boolean =
    (
        requested in AUTOMATIC_SELECTIONS &&
            PlaybackClientCatalog.automaticManifests.any {
                stream.profileId in PlaybackClientCatalog.profileIds(it, stream.profileId.endsWith("__po"))
            }
    ) ||
        (
            PlaybackClientCatalog.manifestIdFromProfileId(stream.profileId) == requested &&
                stream.profileId in
                PlaybackClientCatalog.profileIds(
                    checkNotNull(PlaybackClientCatalog.findBenchmark(requested)).manifest,
                    stream.profileId.endsWith("__po"),
                )
        )

internal fun allowedMedia(url: String): Boolean =
    runCatching {
        val value = Url(url)
        value.protocol.name == "https" &&
            value.port == 443 &&
            value.user == null &&
            value.password == null &&
            listOf("googlevideo.com", "youtube.com").any { value.host == it || value.host.endsWith(".$it") } &&
            value.encodedPath == "/videoplayback"
    }.getOrDefault(false)

private fun ms(start: Long): Long = (System.nanoTime() - start) / 1_000_000

internal fun sabrHttpStatus(status: Int?): String = status?.toString() ?: "unknown"

internal fun deleteCapture(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (error: java.io.IOException) {
        path.toFile().deleteOnExit()
        throw java.io.IOException("capture_cleanup", error)
    }
}

internal fun safeSabrFailure(category: String?): String =
    when (category) {
        null -> "none"
        "ATTESTATION_REQUIRED", "RELOAD_PLAYER", "PROTOCOL" -> category
        else -> "other"
    }

internal data class DirectResult(
    val bytes: Long,
    val firstByteMs: Long,
    val decodeMs: Long,
)

internal class SabrProgress {
    var received = 0L
        private set
    var media = 0L
        private set
    var durationMs = 0L
        private set
    var firstMediaByteMs = -1L
        private set
    private var initialized = false

    fun record(
        chunk: SabrChunk,
        elapsedMs: Long,
        limit: Long,
    ) {
        check(received + chunk.data.size <= limit) { "Media byte limit" }
        received += chunk.data.size
        if (chunk.isInitialization) {
            initialized = true
        } else {
            check(initialized) { "SABR media without init" }
            val start = checkNotNull(chunk.startMs)
            val length = checkNotNull(chunk.durationMs)
            check(start >= 0 && length > 0 && start <= Long.MAX_VALUE - length && start <= durationMs + 1000) {
                "SABR media gap"
            }
            if (firstMediaByteMs < 0 && chunk.data.isNotEmpty()) firstMediaByteMs = elapsedMs
            media += chunk.data.size
            durationMs = maxOf(durationMs, start + length)
        }
    }
}

internal suspend fun direct(
    http: HttpClient,
    stream: ExtractedStream,
    file: File,
    maxBytes: Long,
    duration: Int,
    format: String,
    onDecodeTime: (Long) -> Unit = {},
    onProgress: (Long, Long) -> Unit = { _, _ -> },
): DirectResult {
    require(allowedMedia(stream.audioUrl)) { "Invalid media endpoint" }
    var received = 0L
    var first = -1L
    var decodeMs = 0L
    val started = System.nanoTime()
    val chunk = stream.rangeChunkSizeBytes.coerceIn(64 * 1024, 1_048_576).toInt()
    file.outputStream().buffered().use { out ->
        // Check the boundary after 1 MiB unless the complete representation is shorter.
        while (received < maxBytes) {
            val start = received
            val end =
                (received + chunk - 1).coerceAtMost(maxBytes - 1).let { limit ->
                    stream.contentLengthBytes?.let { minOf(limit, it - 1) } ?: limit
                }
            check(end >= start) { "Media ended before required duration" }
            http
                .prepareGet(stream.audioUrl) {
                    stream.headers
                        .filterKeys {
                            !it.equals(HttpHeaders.Range, ignoreCase = true) &&
                                !it.equals(HttpHeaders.AcceptEncoding, ignoreCase = true)
                        }.forEach { (name, value) ->
                            header(name, value)
                        }
                    header(HttpHeaders.Range, "bytes=$received-$end")
                    header(HttpHeaders.AcceptEncoding, "identity")
                }.execute { response ->
                    check(response.status.value == 206) { "Media HTTP status" }
                    check(response.headers[HttpHeaders.ContentRange]?.matches(Regex("bytes $start-$end/[0-9]+")) == true) {
                        "Invalid content range"
                    }
                    check(response.headers[HttpHeaders.ContentType]?.startsWith("text/") != true) { "Non-media response" }
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(32 * 1024)
                    var countForRange = 0L
                    while (received <= end) {
                        val count = channel.readAvailable(buffer, 0, minOf(buffer.size.toLong(), end - received + 1).toInt())
                        if (count == -1) break
                        if (count == 0) continue
                        if (first < 0) first = ms(started)
                        out.write(buffer, 0, count)
                        received += count
                        onProgress(received, first)
                        countForRange += count
                        if (received > end) break
                    }
                    check(countForRange == end - start + 1) { "Incomplete range" }
                }
            // Decode at each boundary after the second range; stop only after requested PCM duration.
            out.flush()
            if (received >= 1_048_577) {
                val decodeStart = System.nanoTime()
                val decoded =
                    try {
                        decode(file, duration, 15, format)
                    } catch (error: IllegalStateException) {
                        if (error.message != "Decode failed") throw error
                        0.0
                    } finally {
                        decodeMs += ms(decodeStart)
                        onDecodeTime(decodeMs)
                    }
                if (decoded >= duration - 0.05) break
            }
            if (stream.contentLengthBytes?.let { received >= it } == true) {
                break
            }
        }
    }
    check(received > 1_048_576 || (received > 0 && received == stream.contentLengthBytes)) { "Later range not verified" }
    return DirectResult(received, first, decodeMs)
}

// ffmpeg reads a local file only; it can neither open remote URLs nor spawn a protocol helper.
internal suspend fun decode(
    file: File,
    seconds: Int,
    deadlineSeconds: Int,
    format: String,
): Double {
    require(format in setOf("matroska", "mp4"))
    val privateDir = Files.createTempDirectory("harness-decoder-")
    try {
        val pcm = Files.createTempFile(privateDir, "pcm-", ".raw")
        try {
            val process =
                ProcessBuilder(
                    "ffmpeg",
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-protocol_whitelist",
                    "file,pipe",
                    "-f",
                    format,
                    "-i",
                    file.absolutePath,
                    "-t",
                    seconds.toString(),
                    "-vn",
                    "-ac",
                    "1",
                    "-ar",
                    "8000",
                    "-f",
                    "s16le",
                    "-y",
                    pcm.toAbsolutePath().toString(),
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            awaitProcess(process, deadlineSeconds)
            check(process.exitValue() == 0) { "Decode failed" }
            return Files.size(pcm) / 16000.0
        } finally {
            deleteCapture(pcm)
        }
    } finally {
        deleteCapture(privateDir)
    }
}

internal suspend fun awaitProcess(
    process: Process,
    deadlineSeconds: Int,
) {
    try {
        val until = System.nanoTime() + deadlineSeconds * 1_000_000_000L
        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            currentCoroutineContext().ensureActive()
            check(System.nanoTime() < until) { "Decoder deadline" }
        }
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}

@OptIn(ExperimentalSabrApi::class)
internal suspend fun probe(
    case: Case,
    requested: String,
    phase: String,
    iteration: Int,
    extractor: InnerTubeExtractor,
    http: HttpClient,
    quality: AudioQuality,
    seconds: Int,
    maxBytes: Long,
    premium: Boolean,
    authenticated: Boolean,
    onProgress: (Attempt) -> Unit = {},
    tokenCapabilities: TokenProviderCapabilities = TokenProviderCapabilities(),
): Attempt {
    val manifest = requested.takeUnless { it in AUTOMATIC_SELECTIONS }?.let(PlaybackClientCatalog::findBenchmark)?.manifest
    if (!authenticated && (case.hint == "uploads" || manifest?.authentication == AuthenticationPolicy.REQUIRED)) {
        return Attempt(case.alias, requested, phase, iteration, "unsupported", "login_required")
    }
    if (manifest?.poTokens?.player?.let {
            it.requirement == PoTokenRequirement.REQUIRED &&
                it.providers.none(tokenCapabilities.providers::contains)
        } ==
        true
    ) {
        return Attempt(case.alias, requested, phase, iteration, "unsupported", "token_provider_missing")
    }
    val started = System.nanoTime()
    val stream =
        extractor.extract(case.videoId, hints(case, requested, premium), audioQuality = quality)
            ?: return Attempt(case.alias, requested, phase, iteration, "fail", "no_stream")
    val extractionMs = ms(started)
    val chain =
        stream.streamDiagnostics
            ?.attempts
            ?.map { attempt ->
                "${attempt.profileId?.let(PlaybackClientCatalog::manifestIdFromProfileId) ?: "unknown"}:${safeAttempt(attempt.outcome)}"
            }.orEmpty()
            .take(100)
    val base =
        Attempt(
            case.alias,
            requested,
            phase,
            iteration,
            "fail",
            actual =
                PlaybackClientCatalog.manifestIdFromProfileId(
                    stream.profileId,
                ),
            profile =
                stream.profileId.takeIf {
                    it in
                        PlaybackClientCatalog.manifests.flatMap { manifest ->
                            PlaybackClientCatalog.profileIds(manifest, false) + PlaybackClientCatalog.profileIds(manifest, true)
                        }
                },
            itag = stream.itag,
            bitrate = stream.bitrate,
            codec = stream.codecs?.takeIf { it in setOf("opus", "mp4a.40.2", "mp4a.40.5", "vorbis", "flac") },
            extractionMs = extractionMs,
            attempts = chain,
        )
    onProgress(base)
    if (!matchesProfile(requested, stream)) return base.copy(reason = "profile_mismatch")
    if (stream.mimeType == "application/x-mpegURL") {
        val budget = MediaBudget(maxBytes)
        val session = HlsSession(http, stream, budget, quality = quality)
        var decoded = 0L
        var startup: Long? = null
        try {
            val target = session.initialTarget(seconds * 1000L, 0)
            session.play(target, seconds * 1000L) { _, bytes, _ ->
                decoded = bytes
                if (startup == null) startup = ms(started)
                onProgress(
                    base.copy(
                        transport = "HLS",
                        startupMs = startup,
                        mediaBytes = budget.bytes,
                        pcmSeconds = decoded / 16000.0,
                        hlsRendition = session.representation,
                    ),
                )
            }
            return base.copy(
                status = "pass",
                transport = "HLS",
                startupMs = startup,
                mediaBytes = budget.bytes,
                pcmSeconds = decoded / 16000.0,
                hlsRendition = session.representation,
            )
        } catch (error: Exception) {
            val failure =
                base.copy(
                    reason = safeError(error),
                    transport = "HLS",
                    startupMs = startup,
                    mediaBytes = budget.bytes,
                    pcmSeconds = decoded / 16000.0,
                    hlsRendition = session.representation,
                )
            onProgress(failure)
            if (error is kotlinx.coroutines.CancellationException) throw error
            return failure
        }
    }
    val format =
        when (stream.mimeType) {
            "audio/webm" -> "matroska"
            "audio/mp4" -> "mp4"
            else -> return base.copy(status = "unsupported", reason = "decoder_format")
        }
    val file = Files.createTempFile("harness-audio-", ".bin").toFile()
    val downloadStart = System.nanoTime()
    var firstByte = -1L
    var bytes = 0L
    var incrementalDecodeMs = 0L
    var downloadMs: Long? = null
    var finalDecodeStart: Long? = null
    val sabrProgress = SabrProgress()
    val sabrLog = mutableListOf<String>()
    val transport =
        if (stream.sabrBootstrap != null) {
            "SABR"
        } else if (stream.requireBoundedRange) {
            "BOUNDED"
        } else {
            "DIRECT"
        }
    try {
        if (stream.sabrBootstrap != null) {
            file.outputStream().buffered().use { out ->
                SabrAudioStream(http, stream.sabrBootstrap!!, onResponse = { d ->
                    if (sabrLog.size < 128) {
                        val failure = safeSabrFailure(d.failureCategory)
                        sabrLog +=
                            "request=${d.requestNumber},http=${sabrHttpStatus(d.httpStatus)},bytes=${d.responseBytes}," +
                            "media=${d.selectedMediaBytes},segments=${d.selectedSegmentCount}," +
                            "init=${d.initializationReceived},failure=$failure"
                    }
                })
                    .chunks()
                    .takeWhile { chunk ->
                        sabrProgress.record(chunk, ms(started), maxBytes)
                        firstByte = sabrProgress.firstMediaByteMs
                        out.write(chunk.data)
                        sabrProgress.durationMs < seconds * 1000L
                    }.collect()
            }
            check(sabrProgress.durationMs >= seconds * 1000L && sabrProgress.firstMediaByteMs >= 0) {
                "Insufficient SABR media"
            }
            bytes = sabrProgress.media
            firstByte = sabrProgress.firstMediaByteMs
        } else {
            val result =
                direct(http, stream, file, maxBytes, seconds, format, onDecodeTime = { incrementalDecodeMs = it }) { count, first ->
                    bytes = count
                    firstByte = extractionMs + first
                }
            bytes = result.bytes
            incrementalDecodeMs = result.decodeMs
            firstByte = if (result.firstByteMs < 0) -1 else extractionMs + result.firstByteMs
        }
        downloadMs = ms(downloadStart) - incrementalDecodeMs
        val decodeStart = System.nanoTime()
        finalDecodeStart = decodeStart
        val pcmSeconds = decode(file, seconds, 20, format)
        check(pcmSeconds >= seconds - 0.05) { "Insufficient decoded audio" }
        return base.copy(
            status = "pass",
            transport = transport,
            startupMs = firstByte,
            firstMediaByteMs = firstByte,
            downloadMs = downloadMs,
            decodeMs = incrementalDecodeMs + ms(decodeStart),
            mediaBytes = bytes,
            pcmSeconds = pcmSeconds,
            sabr = sabrLog,
        )
    } catch (error: Exception) {
        val failure =
            base.copy(
                status = if (error is java.io.IOException && error.message?.contains("ffmpeg") == true) "unsupported" else "fail",
                reason = safeError(error),
                transport = transport,
                mediaBytes = if (transport == "SABR") sabrProgress.media else file.length(),
                firstMediaByteMs = firstByte.takeIf { it >= 0 },
                startupMs = firstByte.takeIf { it >= 0 },
                downloadMs = downloadMs ?: (ms(downloadStart) - incrementalDecodeMs),
                decodeMs = finalDecodeStart?.let { incrementalDecodeMs + ms(it) } ?: incrementalDecodeMs.takeIf { it > 0 },
                sabr = sabrLog,
            )
        onProgress(failure)
        if (error is kotlinx.coroutines.CancellationException) throw error
        return failure
    } finally {
        deleteCapture(file.toPath())
    }
}
