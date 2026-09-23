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
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Serializable
internal data class Stage(
    val name: String,
    val targetMs: Long,
    val observedMs: Long? = null,
    val advancedMs: Long = 0,
    val pcmBytes: Long = 0,
    val mediaBytes: Long = 0,
    val settleMs: Long? = null,
    val elapsedMs: Long = 0,
    val status: String = "pending",
    val reason: String? = null,
)

internal data class PlaybackPlan(
    val initialMs: Long = 70_000,
    val backMs: Long = 20_000,
    val afterMs: Long = 10_000,
    val forwardMs: Long = 30_000,
) {
    init {
        require(initialMs > 0 && backMs > 0 && afterMs > 0 && forwardMs > 0 && backMs < initialMs)
    }
}

internal class MediaBudget(
    private val max: Long,
) {
    @Volatile var bytes = 0L
        private set

    @Synchronized fun add(count: Long) {
        check(count >= 0 && count <= max - bytes) { "Media byte limit" }
        bytes += count
    }
}

internal fun stageNow(start: Long) = (System.nanoTime() - start) / 1_000_000

internal suspend fun <T> awaitFirstPcm(
    started: Long,
    running: Deferred<T>,
    first: CompletableDeferred<Unit>,
    limitMs: Long = 10_000,
): T {
    running.invokeOnCompletion { first.completeExceptionally(it ?: IllegalStateException("Insufficient decoded audio")) }
    check(
        withTimeoutOrNull((limitMs - stageNow(started)).coerceAtLeast(1)) {
            first.await()
            true
        } == true,
    ) {
        "Seek settle deadline"
    }
    return running.await()
}

internal fun sabrPrerollMs(
    target: Long,
    firstMedia: SabrChunk,
): Long {
    val start = checkNotNull(firstMedia.startMs) { "SABR seek position" }
    val length = checkNotNull(firstMedia.durationMs) { "SABR seek position" }
    check(!firstMedia.isInitialization && start >= 0 && start <= target && length > 0 && target - start < length) {
        "SABR seek position"
    }
    return target - start
}

private class DecoderClock(
    val pipeSeekMs: Long?,
) {
    @Volatile var firstPtsMs: Long? = null
    var reader: Thread? = null

    fun drain(process: Process) {
        // Only one numeric timestamp survives. Discard arbitrary FFmpeg metadata/error text.
        process.errorStream.buffered().use { input ->
            val line = StringBuilder()
            var oversized = false
            while (true) {
                val byte = input.read()
                if (byte < 0) break
                if (byte == 10 || byte == 13) {
                    if (!oversized && firstPtsMs == null && line.contains("Parsed_ashowinfo")) {
                        Regex("pts_time:([-+0-9.eE]+)")
                            .find(line)
                            ?.groupValues
                            ?.get(1)
                            ?.toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it in -60.0..86_400.0 }
                            ?.let { firstPtsMs = (it * 1000).toLong() }
                    }
                    line.setLength(0)
                    oversized = false
                } else if (line.length < 4096 && !oversized) {
                    line.append(byte.toChar())
                } else {
                    line.setLength(0)
                    oversized = true
                }
            }
        }
    }
}

private val decoderClocks = ConcurrentHashMap<Process, DecoderClock>()

// One read = real decoded audio. Its first timestamp and consumed sample count drive the playback clock.
internal suspend fun consumePcm(
    process: Process,
    targetMs: Long,
    durationMs: Long,
    shortToleranceMs: Long = 0,
    checkSource: suspend () -> Unit = {},
    onSample: (Long, Long, Long) -> Unit,
): Pair<Long, Long> {
    val started = System.nanoTime()
    val pcm = process.inputStream
    val buffer = ByteArray(1600)
    var bytes = 0L
    var first = -1L
    var lastProgress = System.nanoTime()
    var paceAt = System.nanoTime()
    var firstPosition = targetMs
    val wanted = durationMs * 16L
    while (bytes < wanted) {
        currentCoroutineContext().ensureActive()
        checkSource()
        if (pcm.available() == 0) {
            check(stageNow(lastProgress) < 15_000) { "Decoder stalled" }
            if (!process.isAlive) break
            delay(20)
            continue
        }
        val count = pcm.read(buffer, 0, minOf(buffer.size.toLong(), wanted - bytes).toInt())
        if (count < 0) break
        lastProgress = System.nanoTime()
        if (first < 0) {
            val clock = checkNotNull(decoderClocks[process]) { "Decoder timestamp missing" }
            withTimeoutOrNull(1000) {
                while (clock.firstPtsMs == null) delay(5)
            }
            val pts = checkNotNull(clock.firstPtsMs) { "Decoder timestamp missing" }
            firstPosition = clock.pipeSeekMs?.let { targetMs + pts - it } ?: pts
            check(kotlin.math.abs(firstPosition - targetMs) <= 1000) { "Decoder seek mismatch" }
            first = stageNow(started)
        }
        bytes += count
        onSample(firstPosition + bytes / 16, bytes, first)
        paceAt = maxOf(paceAt, System.nanoTime()) + count * 1_000_000L / 16
        val waitMs = (paceAt - System.nanoTime() + 999_999) / 1_000_000
        if (waitMs > 0) delay(waitMs)
    }
    check(bytes >= wanted - shortToleranceMs * 16) { "Insufficient decoded audio" }
    check(process.isAlive || process.exitValue() == 0) { "Decode failed" }
    return bytes to first
}

internal fun decoder(
    input: String,
    seekMs: Long,
    extra: List<String> = emptyList(),
): Process =
    try {
        ProcessBuilder(
            listOf(
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "info",
                "-protocol_whitelist",
                if (input.startsWith("pipe:")) "pipe" else "pipe,http,tcp",
            ) +
                (
                    if (input.startsWith("pipe:")) {
                        emptyList()
                    } else {
                        listOf(
                            "-copyts",
                            "-ss",
                            "%.3f".format(
                                java.util.Locale.ROOT,
                                seekMs / 1000.0,
                            ),
                        )
                    }
                ) +
                extra + (if ("mp4" in extra) listOf("-enable_drefs", "0") else emptyList()) + listOf("-i", input) +
                listOf(
                    "-af",
                    if (input.startsWith("pipe:")) {
                        "atrim=start=${"%.3f".format(java.util.Locale.ROOT, seekMs / 1000.0)},ashowinfo"
                    } else {
                        "ashowinfo"
                    },
                ) +
                listOf("-vn", "-ac", "1", "-ar", "8000", "-f", "s16le", "pipe:1"),
        ).start().also { process ->
            val clock = DecoderClock(seekMs.takeIf { input.startsWith("pipe:") })
            decoderClocks[process] = clock
            clock.reader =
                Thread({ runCatching { clock.drain(process) } }, "harness-decoder-timestamp").apply {
                    isDaemon = true
                    start()
                }
        }
    } catch (_: java.io.IOException) {
        throw IllegalStateException("ffmpeg_missing")
    }

internal fun stopDecoder(process: Process) {
    process.destroyForcibly()
    runCatching { process.inputStream.close() }
    runCatching { process.outputStream.close() }
    val stopped = process.waitFor(2, TimeUnit.SECONDS)
    decoderClocks.remove(process)?.reader?.join(2000)
    check(stopped) { "Decoder cleanup deadline" }
}

// A private loopback byte-range bridge preserves FFmpeg's container seek algorithm. Never give signed URLs to FFmpeg.
internal class RangeBridge(
    private val http: HttpClient,
    private val stream: ExtractedStream,
    private val budget: MediaBudget,
    length: Long,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val length =
        length.also {
            check(allowedMedia(stream.audioUrl)) { "Invalid media endpoint" }
            check(it in 1..(1024L * 1024 * 1024 * 8)) { "Unknown media length" }
        }
    private val path = "/${UUID.randomUUID()}"
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 2)
    private val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "harness-range").apply { isDaemon = true } }
    private val jobs: MutableSet<Job> = Collections.synchronizedSet(mutableSetOf())
    val url: String get() = "http://127.0.0.1:${server.address.port}$path"

    @Volatile var ranged = false
        private set

    init {
        server.executor = workers
        server.createContext(path) { exchange ->
            val job = Job()
            jobs.add(job)
            try {
                check(exchange.requestMethod == "GET" && exchange.requestURI.rawPath == path && exchange.requestURI.rawQuery == null) {
                    "Invalid bridge request"
                }
                val header = exchange.requestHeaders.getFirst("Range")
                val start =
                    if (header == null) {
                        0L
                    } else {
                        checkNotNull(
                            Regex("bytes=(\\d{1,12})-")
                                .matchEntire(header)
                                ?.groupValues
                                ?.get(1)
                                ?.toLongOrNull(),
                        ) {
                            "Invalid bridge range"
                        }
                    }
                check(start in 0 until length) { "Invalid bridge range" }
                if (start > 0) ranged = true
                exchange.responseHeaders.set("Content-Range", "bytes $start-${length - 1}/$length")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(206, length - start)
                var upstreamError: Throwable? = null
                try {
                    exchange.responseBody.use { output ->
                        var position = start
                        while (position < length) {
                            val end = minOf(length - 1, position + 262143)
                            val data =
                                try {
                                    runBlocking(job) { fetchRange(http, stream, position, end, budget, length).first }
                                } catch (error: Throwable) {
                                    upstreamError = error
                                    throw error
                                }
                            try {
                                output.write(data)
                                output.flush()
                            } catch (_: java.io.IOException) {
                                break // FFmpeg closed this response to issue a different seek range.
                            }
                            position = end + 1
                        }
                    }
                } catch (error: java.io.IOException) {
                    upstreamError?.let { throw it }
                    if (error.message != "insufficient bytes written to stream") throw error
                }
            } catch (error: Throwable) {
                onError(error)
                runCatching { exchange.sendResponseHeaders(502, -1) }
            } finally {
                jobs.remove(job)
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() {
        synchronized(jobs) { jobs.forEach { it.cancel() } }
        server.stop(0)
        workers.shutdownNow()
        check(workers.awaitTermination(2, TimeUnit.SECONDS)) { "Range bridge cleanup deadline" }
    }
}

internal suspend fun rangeBridge(
    http: HttpClient,
    stream: ExtractedStream,
    budget: MediaBudget,
    onError: (Throwable) -> Unit,
): RangeBridge = RangeBridge(http, stream, budget, stream.contentLengthBytes ?: fetchRange(http, stream, 0, 0, budget).second, onError)

internal suspend fun fetchRange(
    http: HttpClient,
    stream: ExtractedStream,
    start: Long,
    end: Long,
    budget: MediaBudget,
    expectedTotal: Long? = stream.contentLengthBytes,
): Pair<ByteArray, Long> {
    check(allowedMedia(stream.audioUrl)) { "Invalid media endpoint" }
    return http
        .prepareGet(stream.audioUrl) {
            stream.headers
                .filterKeys { !it.equals(HttpHeaders.Range, true) && !it.equals(HttpHeaders.AcceptEncoding, true) }
                .forEach { (key, value) -> header(key, value) }
            header(HttpHeaders.Range, "bytes=$start-$end")
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            check(response.status.value == 206) { "Media HTTP status" }
            val total =
                response.headers[HttpHeaders.ContentRange]
                    ?.let {
                        Regex("bytes $start-$end/([0-9]{1,12})")
                            .matchEntire(it)
                            ?.groupValues
                            ?.get(1)
                            ?.toLongOrNull()
                    }
            check(
                total != null &&
                    total in 1..(1024L * 1024 * 1024 * 8) &&
                    end < total &&
                    (expectedTotal == null || expectedTotal == total),
            ) { "Invalid content range" }
            val expected = (end - start + 1).toInt()
            val bytes = ByteArray(expected)
            var n = 0
            val channel = response.bodyAsChannel()
            while (n < expected) {
                val got = channel.readAvailable(bytes, n, expected - n)
                if (got == -1) break
                budget.add(got.toLong())
                n += got
            }
            check(n == expected) { "Incomplete range" }
            bytes to total
        }
}

@OptIn(ExperimentalSabrApi::class)
internal suspend fun playbackProbe(
    case: Case,
    requested: String,
    phase: String,
    iteration: Int,
    extractor: InnerTubeExtractor,
    http: HttpClient,
    quality: AudioQuality,
    maxBytes: Long,
    premium: Boolean,
    authenticated: Boolean,
    tokenCapabilities: TokenProviderCapabilities = TokenProviderCapabilities(),
    plan: PlaybackPlan = PlaybackPlan(),
    budget: MediaBudget = MediaBudget(maxBytes),
    onProgress: (Attempt) -> Unit = {},
): Attempt {
    val manifest = requested.takeUnless { it == "AUTO" }?.let(PlaybackClientCatalog::findBenchmark)?.manifest
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
    val start = System.nanoTime()
    val stream =
        extractor.extract(case.videoId, hints(case, requested, premium), audioQuality = quality)
            ?: return Attempt(case.alias, requested, phase, iteration, "fail", "no_stream")
    val extractionMs = stageNow(start)
    val attempts =
        stream.streamDiagnostics
            ?.attempts
            .orEmpty()
            .filterNot { it.outcome.startsWith("selection:") }
    val base =
        Attempt(
            case.alias,
            requested,
            phase,
            iteration,
            "fail",
            actual = PlaybackClientCatalog.manifestIdFromProfileId(stream.profileId),
            profile =
                stream.profileId.takeIf { id ->
                    PlaybackClientCatalog.manifests.any {
                        id in
                            PlaybackClientCatalog.profileIds(it, false) + PlaybackClientCatalog.profileIds(it, true)
                    }
                },
            transport =
                if (stream.sabrBootstrap != null) {
                    "SABR"
                } else if (stream.mimeType == "application/x-mpegURL") {
                    "HLS"
                } else {
                    "DIRECT"
                },
            itag = stream.itag,
            sabrLastModified = stream.sabrBootstrap?.audioFormat?.lastModified,
            bitrate = stream.bitrate,
            codec = stream.codecs?.takeIf { it in setOf("opus", "mp4a.40.2", "mp4a.40.5", "vorbis", "flac") },
            extractionMs = extractionMs,
            attempts =
                attempts.take(100).map {
                    "${it.profileId?.let(PlaybackClientCatalog::manifestIdFromProfileId) ?: "unknown"}:${safeAttempt(it.outcome)}"
                },
        )
    onProgress(base)
    if (!matchesProfile(requested, stream)) return base.copy(reason = "profile_mismatch")
    if (base.transport != "HLS" && stream.mimeType !in setOf("audio/webm", "audio/mp4")) {
        return base.copy(reason = "decoder_format")
    }
    if (requested != "AUTO" &&
        (attempts.size != 1 || PlaybackClientCatalog.manifestIdFromProfileId(attempts.single().profileId) != requested)
    ) {
        return base.copy(reason = "profile_attempts")
    }
    val stages = mutableListOf<Stage>()
    val current = AtomicLong(0L)
    var firstPcm: Long? = null
    var failure: Throwable? = null
    val sabrLog = java.util.concurrent.CopyOnWriteArrayList<String>()
    var hls: HlsSession? = null
    var hlsAnchor = 0L
    var activeSnapshot: (() -> Stage)? = null
    val runner: suspend (Long, Long, (Long, Long, Long) -> Unit) -> Pair<Long, Long> = { target, duration, sample ->
        when (base.transport) {
            "SABR" -> {
                val bootstrap = checkNotNull(stream.sabrBootstrap)
                check(target < bootstrap.durationMs) { "Seek outside media" }
                val firstMedia = CompletableDeferred<SabrChunk>()
                val decoderReady = CompletableDeferred<Process>()
                val writer =
                    kotlinx.coroutines.CoroutineScope(currentCoroutineContext() + SupervisorJob()).async(Dispatchers.IO) {
                        var init: ByteArray? = null
                        try {
                            SabrAudioStream(
                                http,
                                bootstrap,
                                initialPlayerTimeMs = target,
                                playbackPositionMs = { current.get() },
                                onResponse = { diagnostic ->
                                    if (sabrLog.size < 128) {
                                        sabrLog += "request=${diagnostic.requestNumber},http=${sabrHttpStatus(diagnostic.httpStatus)}," +
                                            "bytes=${diagnostic.responseBytes},media=${diagnostic.selectedMediaBytes}," +
                                            "segments=${diagnostic.selectedSegmentCount},failure=${safeSabrFailure(
                                                diagnostic.failureCategory,
                                            )}"
                                    }
                                },
                            ).chunks().collect { chunk ->
                                if (chunk.isInitialization) {
                                    init = chunk.data
                                } else {
                                    check(init != null) { "SABR media without init" }
                                    check(
                                        chunk.startMs != null &&
                                            chunk.durationMs != null &&
                                            chunk.startMs!! <= maxOf(target, current.get()) + 30_000,
                                    ) {
                                        "SABR media gap"
                                    }
                                    val player =
                                        if (firstMedia.isCompleted) {
                                            decoderReady.await()
                                        } else {
                                            firstMedia.complete(chunk)
                                            decoderReady.await().also { it.outputStream.write(checkNotNull(init)) }
                                        }
                                    player.outputStream.write(chunk.data)
                                }
                            }
                            if (!firstMedia.isCompleted) {
                                firstMedia.completeExceptionally(IllegalStateException("Insufficient SABR media"))
                            } else {
                                decoderReady.await().outputStream.close()
                            }
                        } catch (error: Throwable) {
                            firstMedia.completeExceptionally(error)
                            if (decoderReady.isCompleted && !decoderReady.isCancelled) {
                                runCatching { decoderReady.await().outputStream.close() }
                            }
                            throw error
                        }
                    }
                var player: Process? = null
                try {
                    val prerollMs = sabrPrerollMs(target, firstMedia.await())
                    player = decoder("pipe:0", prerollMs, listOf("-f", if (stream.mimeType == "audio/webm") "matroska" else "mp4"))
                    decoderReady.complete(player)
                    val result =
                        try {
                            consumePcm(
                                player,
                                target,
                                duration,
                                checkSource = { if (writer.isCompleted) writer.await() },
                                onSample = sample,
                            )
                        } catch (error: Throwable) {
                            if (writer.isCompleted) writer.await()
                            throw error
                        }
                    if (writer.isCompleted) writer.await()
                    result
                } finally {
                    writer.cancel()
                    player?.let(::stopDecoder)
                    writer.join()
                }
            }
            "HLS" -> {
                val session = hls ?: HlsSession(http, stream, budget, quality = quality).also { hls = it }
                session.play(target, duration, sample = sample)
            }
            else -> {
                val bridgeError = AtomicReference<Throwable?>()
                rangeBridge(http, stream, budget, { bridgeError.compareAndSet(null, it) }).use { bridge ->
                    val decoder =
                        decoder(
                            bridge.url,
                            target,
                            listOf(
                                "-f",
                                if (stream.mimeType ==
                                    "audio/webm"
                                ) {
                                    "matroska"
                                } else {
                                    "mp4"
                                },
                                "-rw_timeout",
                                "15000000",
                            ),
                        )
                    try {
                        consumePcm(
                            decoder,
                            target,
                            duration,
                            checkSource = { bridgeError.get()?.let { throw it } },
                            onSample = sample,
                        ).also {
                            bridgeError.get()?.let { error -> throw error }
                            if (target > 0) check(bridge.ranged) { "Seek did not use network range" }
                        }
                    } finally {
                        stopDecoder(decoder)
                    }
                }
            }
        }
    }
    try {
        for ((name, offset, duration) in listOf(
            Triple("initial", 0L, plan.initialMs),
            Triple("backward", plan.initialMs - plan.backMs, plan.afterMs),
            Triple("forward", plan.initialMs - plan.backMs + plan.afterMs + plan.forwardMs, plan.afterMs),
        )) {
            val started = System.nanoTime()
            var target = hlsAnchor + offset
            val before = budget.bytes
            var pcm = 0L
            var observed: Long? = null
            var settle: Long? = null

            fun snapshot(
                status: String,
                reason: String? = null,
            ): Stage =
                Stage(
                    name,
                    target,
                    observed,
                    pcm / 16,
                    pcm,
                    budget.bytes - before,
                    settle,
                    stageNow(started),
                    status,
                    reason,
                )
            activeSnapshot = { snapshot("pending") }
            stages += snapshot("pending")
            onProgress(base.copy(stages = stages.toList(), mediaBytes = budget.bytes, hlsRendition = hls?.representation))
            try {
                if (name == "initial" && base.transport == "HLS") {
                    val session = hls ?: HlsSession(http, stream, budget, quality = quality).also { hls = it }
                    hlsAnchor = session.initialTarget(plan.initialMs, plan.backMs)
                    target = hlsAnchor
                    stages[stages.lastIndex] = snapshot("pending")
                    onProgress(base.copy(stages = stages.toList(), mediaBytes = budget.bytes, hlsRendition = session.representation))
                }
                current.set(target)
                val first = CompletableDeferred<Unit>()
                val (decoded, _) =
                    coroutineScope {
                        val running =
                            async {
                                runner(target, duration) { position, bytes, _ ->
                                    if (settle == null) {
                                        val elapsed = stageNow(started)
                                        check(elapsed <= 10_000) { "Seek settle deadline" }
                                        settle = elapsed
                                        first.complete(Unit)
                                    }
                                    pcm = bytes
                                    observed = position
                                    current.set(position)
                                    if (firstPcm == null) firstPcm = extractionMs + stageNow(started)
                                    stages[stages.lastIndex] = snapshot("pending")
                                    onProgress(
                                        base.copy(
                                            stages = stages.toList(),
                                            mediaBytes = budget.bytes,
                                            startupMs = firstPcm,
                                            hlsRendition = hls?.representation,
                                        ),
                                    )
                                }
                            }
                        try {
                            awaitFirstPcm(started, running, first)
                        } finally {
                            running.cancel()
                        }
                    }
                check(settle != null && settle in 0..10_000) { "Seek settle deadline" }
                check(decoded >= duration * 16L) { "Insufficient decoded audio" }
                stages[stages.lastIndex] = snapshot("pass")
            } catch (error: Throwable) {
                stages[stages.lastIndex] = snapshot("fail", safeError(error))
                failure = error
                onProgress(
                    base.copy(
                        stages = stages.toList(),
                        mediaBytes = budget.bytes,
                        startupMs = firstPcm,
                        reason = safeError(error),
                        hlsRendition = hls?.representation,
                    ),
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
                break
            }
            onProgress(
                base.copy(stages = stages.toList(), mediaBytes = budget.bytes, startupMs = firstPcm, hlsRendition = hls?.representation),
            )
            activeSnapshot = null
        }
        return base.copy(
            status = if (failure == null && stages.size == 3 && stages.all { it.status == "pass" }) "pass" else "fail",
            reason = failure?.let(::safeError),
            stages = stages.toList(),
            mediaBytes = budget.bytes,
            pcmSeconds = stages.sumOf { it.advancedMs } / 1000.0,
            startupMs = firstPcm,
            sabr = sabrLog,
            hlsRendition = hls?.representation,
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        if (stages.lastOrNull()?.status == "pending") {
            activeSnapshot?.let { stages[stages.lastIndex] = it().copy(status = "fail", reason = safeError(error)) }
        }
        onProgress(
            base.copy(
                stages = stages.toList(),
                mediaBytes = budget.bytes,
                startupMs = firstPcm,
                reason = safeError(error),
                hlsRendition = hls?.representation,
                sabr = sabrLog.toList(),
            ),
        )
        throw error
    }
}
