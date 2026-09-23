package com.metrolist.innertubex.harness

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private val json =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

internal fun shouldFail(results: List<Attempt>): Boolean = results.none { it.status == "pass" } || results.any { it.status == "fail" }

internal fun terminalMedia4xx(
    entries: List<kotlinx.serialization.json.JsonObject>,
    transport: String?,
    status: String,
): Boolean {
    if (status != "pass") return false
    val endpoints = if (transport == "HLS") setOf("media", "hls_playlist") else setOf(if (transport == "SABR") "sabr" else "media")
    return entries.any { entry ->
        val endpoint = entry["endpoint"]?.toString()?.trim('"')
        val code = entry["status"]?.toString()?.toIntOrNull()
        endpoint in endpoints &&
            code != null &&
            code in 400..499 &&
            !(transport == "SABR" && code in setOf(408, 425, 429))
    }
}

internal fun archiveEvent(
    events: MutableList<String>,
    event: String,
) = synchronized(events) {
    if (events.size == 128) events.removeAt(0)
    events.add(event)
}

internal fun safeAttempt(outcome: String): String =
    when {
        outcome == "playable_response" || outcome == "no_playable_response" || outcome == "po_token_unavailable" -> outcome
        outcome.startsWith("selection:") -> "selection"
        outcome.startsWith("playability:") -> "playability"
        outcome.startsWith("request:") -> "request_failure"
        outcome.startsWith("transport:") -> "transport"
        else -> "other"
    }

private val ID = Regex("[A-Za-z0-9_-]{11}")
private val ALIAS = Regex("[a-z][a-z0-9_-]{0,39}")

@Serializable
internal data class Case(
    val alias: String,
    val videoId: String,
    val hint: String = "normal",
    val expectedOutcome: String? = null,
    val expectedItag: Int? = null,
    val minBitrate: Int? = null,
)

@Serializable
internal data class Manifest(
    val cases: List<Case>,
)

@Serializable
internal data class Attempt(
    val alias: String,
    val requested: String,
    val phase: String,
    val iteration: Int,
    val status: String,
    val reason: String? = null,
    val actual: String? = null,
    val profile: String? = null,
    val transport: String? = null,
    val itag: Int? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    val extractionMs: Long? = null,
    val startupMs: Long? = null,
    val downloadMs: Long? = null,
    val decodeMs: Long? = null,
    val firstMediaByteMs: Long? = null,
    val mediaBytes: Long? = null,
    val pcmSeconds: Double? = null,
    val attempts: List<String> = emptyList(),
    val http: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val httpDropped: Int = 0,
    val events: List<String> = emptyList(),
    val sabr: List<String> = emptyList(),
    val stages: List<Stage> = emptyList(),
    val hlsRendition: String? = null,
    val sabrLastModified: Long? = null,
)

private val HELP = """InnerTubeX playback harness (no network for help/list-clients)
./gradlew :harness:run --args='--cases /path/to/cases.json --client AUTO,WEB_REMIX_SABR'
Default: paced decoded PCM initial 0..70s, seek back 20s to 50..60s,
seek forward 30s from 60s to 90..100s; 90s total audio, 3 real transport stages.
--seconds N instead runs legacy offline decode/download smoke (no seeks, not benchmark).
Options: --id VIDEO_ID (repeatable), --cases FILE, --client AUTO|ALL|comma-separated IDs,
--quality AUTO|LOW|HIGH|MP4, --repetitions 1..10, --seconds 1..120 (smoke only),
--max-bytes 2..64 (MiB), --deadline 5..300 (default 240s per attempt), --pace-ms 0..10000,
--auth anonymous|cookie, --cookie-file FILE (only with cookie mode), --premium-confirmed (cookie mode only),
--tokens browser|off (default browser, lazy Linux Chromium minting), --chromium /absolute/executable,
--proxy http://host:port (or HTTPS_PROXY environment), --list-clients, --help.
Artifacts: build/harness/<unique timestamp>/; only sanitized data, no media retained.
"""

internal fun validate(cases: List<Case>) {
    require(
        cases.isNotEmpty() && cases.size <= 100 && cases.map { it.alias }.distinct().size == cases.size,
    ) { "Expected 1..100 unique cases" }
    cases.forEach {
        require(ALIAS.matches(it.alias) && ID.matches(it.videoId)) { "Invalid alias or video ID" }
        require(it.hint in setOf("normal", "kids", "explicit", "age", "live", "uploads")) { "Invalid hint" }
        require(it.expectedOutcome == null || it.expectedOutcome in setOf("pass", "unsupported", "fail")) { "Invalid expectedOutcome" }
        require(it.expectedItag == null || it.expectedItag in 1..9999) { "Invalid itag" }
        require(it.minBitrate == null || it.minBitrate in 1..10_000_000) { "Invalid bitrate" }
    }
}

private fun options(args: Array<String>): Map<String, List<String>> {
    val allowed =
        setOf(
            "id",
            "cases",
            "client",
            "quality",
            "repetitions",
            "seconds",
            "max-bytes",
            "deadline",
            "pace-ms",
            "auth",
            "cookie-file",
            "premium-confirmed",
            "proxy",
            "tokens",
            "chromium",
            "help",
            "list-clients",
        )
    val flags = setOf("premium-confirmed", "help", "list-clients")
    val result = mutableMapOf<String, MutableList<String>>()
    var i = 0
    while (i < args.size) {
        val name = args[i++].removePrefix("--")
        require(name in allowed && args[i - 1] == "--$name") { "Unknown option" }
        val value = if (name in flags) "true" else args.getOrNull(i++)?.takeIf { !it.startsWith("--") } ?: error("Missing option value")
        require(name == "id" || name !in result) { "Duplicate option" }
        result.getOrPut(name) { mutableListOf() } += value
    }
    return result
}

private fun number(
    opts: Map<String, List<String>>,
    name: String,
    default: Int,
    range: IntRange,
): Int =
    (
        opts[name]?.singleOrNull()?.toIntOrNull() ?: if (name in
            opts
        ) {
            error("Invalid $name")
        } else {
            default
        }
    ).also { require(it in range) { "Invalid $name" } }

internal fun hints(
    case: Case,
    client: String,
    premium: Boolean,
): ContentHints =
    ContentHints(
        isKidsContent = (case.hint == "kids").takeIf { it },
        isExplicit = (case.hint == "explicit").takeIf { it },
        isAgeRestricted = (case.hint == "age").takeIf { it },
        isLive = (case.hint == "live").takeIf { it },
        isUploaded = (case.hint == "uploads").takeIf { it },
        playbackClientOverrideId = client.takeUnless { it == "AUTO" },
    ).withPremium(premium)

private fun proxy(value: String?): Proxy? {
    if (value.isNullOrBlank()) return null
    val uri = URI(value)
    require(
        uri.scheme == "http" &&
            uri.userInfo == null &&
            uri.host != null &&
            uri.port in 1..65535 &&
            uri.path.isNullOrEmpty() &&
            uri.query == null &&
            uri.fragment == null,
    ) { "Proxy must be http://host:port without credentials" }
    return Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port))
}

private fun client(
    proxy: Proxy?,
    capture: Capture,
): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                if (proxy != null) proxy(proxy)
                followRedirects(false)
                followSslRedirects(false)
            }
            addInterceptor(capture)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        followRedirects = false
    }

internal fun writeReport(
    dir: File,
    results: List<Attempt>,
) {
    File(dir, "report.json").writeText(json.encodeToString(results))

    fun percentile(
        values: List<Long>,
        p: Double,
    ): String = values.sorted().getOrNull(ceil(values.size * p).toInt() - 1)?.toString() ?: "n/a"
    File(dir, "summary.md").writeText(
        buildString {
            appendLine("# Playback results")
            appendLine(
                "Passed ${results.count {
                    it.status == "pass"
                }}, failed ${results.count { it.status == "fail" }}, unsupported ${results.count { it.status == "unsupported" }}",
            )
            appendLine(
                "Timing percentiles use passed attempts only; cold and warm samples remain separate. " +
                    "No playback stats or watch history are sent.",
            )
            appendLine("| Requested | Phase | Pass/total | Extraction p50 ms | Startup p50 ms | Startup p95 ms |")
            appendLine("| --- | --- | ---: | ---: | ---: | ---: |")
            results.groupBy { it.requested to it.phase }.forEach { (key, group) ->
                val passed = group.filter { it.status == "pass" }
                val startup = passed.mapNotNull { it.startupMs }
                appendLine(
                    "| ${key.first} | ${key.second} | ${passed.size}/${group.size} | " +
                        "${percentile(
                            passed.mapNotNull { it.extractionMs },
                            .5,
                        )} | ${percentile(startup, .5)} | ${percentile(startup, .95)} |",
                )
            }
            appendLine()
            appendLine(
                "| Case | Requested | Actual | Itag | HLS rendition | SABR lastModified | Phase | Status | Reason | " +
                    "Extraction ms | Startup ms (PCM in benchmark) | Download ms | Decode ms |",
            )
            appendLine("| --- | --- | --- | ---: | --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |")
            results.forEach {
                appendLine(
                    "| ${it.alias} | ${it.requested} | ${it.actual ?: ""} | ${it.itag ?: ""} | " +
                        "${it.hlsRendition ?: ""} | ${it.sabrLastModified ?: ""} | " +
                        "${it.phase} | ${it.status} | ${it.reason ?: ""} | " +
                        "${it.extractionMs ?: ""} | ${it.startupMs ?: ""} | ${it.downloadMs ?: ""} | ${it.decodeMs ?: ""} |",
                )
            }
            appendLine()
            appendLine(
                "| Case | Client | Phase | Stage | Target ms | Observed ms | Audio ms | PCM bytes | " +
                    "Media bytes | Settle ms | Elapsed ms | Status | Reason |",
            )
            appendLine("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |")
            results.forEach { result ->
                result.stages.forEach { stage ->
                    appendLine(
                        "| ${result.alias} | ${result.requested} | ${result.phase} | ${stage.name} | " +
                            "${stage.targetMs} | ${stage.observedMs ?: ""} | ${stage.advancedMs} | ${stage.pcmBytes} | " +
                            "${stage.mediaBytes} | ${stage.settleMs ?: ""} | ${stage.elapsedMs} | " +
                            "${stage.status} | ${stage.reason ?: ""} |",
                    )
                }
            }
        },
    )
}

fun main(args: Array<String>) {
    try {
        val opts = options(args)
        if ("help" in opts) {
            print(HELP)
            return
        }
        if ("list-clients" in opts) {
            println("AUTO")
            PlaybackClientCatalog.benchmarkOptions.forEach { println("${it.id} ${it.manifest.transports} ${it.manifest.selectionMode}") }
            return
        }
        val cases =
            (
                opts["cases"]?.singleOrNull()?.let {
                    val file = File(it)
                    require(file.length() in 1..65536) { "Invalid case file size" }
                    json.decodeFromString<Manifest>(file.readText()).cases
                } ?: emptyList()
            ) +
                opts["id"].orEmpty().mapIndexed { index, id -> Case("smoke${index + 1}", id) }
        validate(cases)
        val clients =
            opts["client"]?.singleOrNull()?.let { value ->
                if (value == "ALL") listOf("AUTO") + PlaybackClientCatalog.benchmarkOptions.map { it.id } else value.split(',')
            } ?: listOf("AUTO")
        require(
            clients.isNotEmpty() &&
                clients.distinct().size == clients.size &&
                clients.all { it == "AUTO" || PlaybackClientCatalog.findBenchmark(it) != null },
        ) { "Unknown or duplicate client" }
        val quality = opts["quality"]?.singleOrNull()?.let(AudioQuality::valueOf) ?: AudioQuality.HIGH
        val reps = number(opts, "repetitions", 1, 1..10)
        require(cases.size * clients.size * reps * 2 <= 500) { "Matrix exceeds 500 attempts" }
        val seconds = opts["seconds"]?.let { number(opts, "seconds", 30, 1..120) }
        val maxBytes = number(opts, "max-bytes", 16, 2..64) * 1024L * 1024L
        val deadline = number(opts, "deadline", if (seconds == null) 240 else 90, 5..300)
        val pace = number(opts, "pace-ms", 500, 0..10000)
        val auth = opts["auth"]?.singleOrNull() ?: "anonymous"
        require(auth in setOf("anonymous", "cookie")) { "Invalid auth mode" }
        require((auth == "cookie") == ("cookie-file" in opts) && (auth == "cookie" || "premium-confirmed" !in opts)) {
            "Cookie file required only for cookie mode; premium requires cookie mode"
        }
        val cookie =
            opts["cookie-file"]?.singleOrNull()?.let {
                File(it)
                    .also { file ->
                        require(file.length() in 1..16384) { "Invalid cookie file size" }
                    }.readText()
                    .trim()
                    .also { content ->
                        require(
                            content.length in 1..16384 && !content.contains('\n'),
                        ) { "Invalid cookie file" }
                    }
            }
        val selectedProxy = proxy(opts["proxy"]?.singleOrNull() ?: System.getenv("HTTPS_PROXY")?.takeIf { it.isNotBlank() })
        val tokenMode = opts["tokens"]?.singleOrNull() ?: "browser"
        require(tokenMode in setOf("browser", "off")) { "Invalid token mode" }
        require(tokenMode == "browser" || "chromium" !in opts) { "Chromium requires browser token mode" }
        val browserExecutable = opts["chromium"]?.singleOrNull()
        browserExecutable?.let { require(File(it).isAbsolute) { "Chromium path must be absolute" } }
        val dir =
            Files.createDirectories(File("build/harness").toPath()).let {
                Files
                    .createTempDirectory(
                        it,
                        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-").withZone(java.time.ZoneOffset.UTC).format(Instant.now()),
                    ).toFile()
            }
        File(dir, "manifest.json").writeText(
            json.encodeToString(
                mapOf(
                    "cases" to cases.size.toString(),
                    "clients" to clients.joinToString(","),
                    "quality" to quality.name,
                    "auth" to auth,
                    "mode" to if (seconds == null) "paced-70-back20-play10-forward30-play10" else "decode-smoke",
                    "seconds" to (seconds?.toString() ?: "90"),
                    "repetitions" to reps.toString(),
                    "maxBytes" to maxBytes.toString(),
                    "deadlineSeconds" to deadline.toString(),
                    "paceMs" to pace.toString(),
                    "premiumConfirmed" to ("premium-confirmed" in opts).toString(),
                    "proxyConfigured" to (selectedProxy != null).toString(),
                    "tokens" to tokenMode,
                ),
            ),
        )
        println("Reports: ${dir.path}")
        val results = mutableListOf<Attempt>()
        runBlocking {
            for (case in cases) {
                for (requested in clients) {
                    for (iteration in 1..reps) {
                        val capture = Capture()
                        val events = mutableListOf<String>()
                        val logger =
                            InnerTubeLogger { event: InnerTubeLogEvent ->
                                // Never archive arbitrary message/details/mediaId from the library.
                                archiveEvent(events, safeEvent(event))
                            }
                        client(selectedProxy, capture).use { http ->
                            val tube = InnerTube(http, logger = logger)
                            val cipher = YouTubeCipherService(http, logger = logger)
                            val tokens =
                                if (tokenMode == "browser") {
                                    HarnessTokenProvider(http, selectedProxy, browserExecutable) { archiveEvent(events, it) }
                                } else {
                                    null
                                }
                            try {
                                tube.cookie = cookie
                                val extractor =
                                    InnerTubeExtractor(
                                        YtConfigParserImpl(http, tube, logger = logger),
                                        cipher,
                                        tube,
                                        tokenProvider = tokens,
                                        logger = logger,
                                    )
                                for (phase in listOf("cold", "warm")) {
                                    val from = capture.entries.size
                                    var mediaFrom = from
                                    synchronized(events) { events.clear() }
                                    var partial: Attempt? = null
                                    var lastStageState: Pair<String, String>? = null
                                    var lastProgressWrite = 0L
                                    val progress: (Attempt) -> Unit = {
                                        partial = it
                                        if (it.stages.isEmpty() && it.extractionMs != null) mediaFrom = capture.entries.size
                                        it.stages.lastOrNull()?.let { stage ->
                                            val state = stage.name to stage.status
                                            val now = System.nanoTime()
                                            if (state != lastStageState || now - lastProgressWrite >= 1_000_000_000L) {
                                                val event =
                                                    buildJsonObject {
                                                        put("case", case.alias)
                                                        put("requested", requested)
                                                        put("phase", phase)
                                                        put("iteration", iteration)
                                                        put("stage", json.encodeToJsonElement(stage))
                                                    }
                                                File(dir, "playback.jsonl").appendText("$event\n")
                                                lastStageState = state
                                                lastProgressWrite = now
                                            }
                                        }
                                    }
                                    val attemptStarted = System.nanoTime()
                                    val budget = MediaBudget(maxBytes)
                                    capture.sabrBudget = budget
                                    val tokenCapabilities = tokens?.capabilities ?: TokenProviderCapabilities()
                                    val result =
                                        try {
                                            withTimeout(deadline * 1000L) {
                                                if (seconds == null) {
                                                    playbackProbe(
                                                        case,
                                                        requested,
                                                        phase,
                                                        iteration,
                                                        extractor,
                                                        http,
                                                        quality,
                                                        maxBytes,
                                                        "premium-confirmed" in opts,
                                                        tube.hasSapCookieAuth(),
                                                        tokenCapabilities = tokenCapabilities,
                                                        budget = budget,
                                                        onProgress = progress,
                                                    )
                                                } else {
                                                    probe(
                                                        case,
                                                        requested,
                                                        phase,
                                                        iteration,
                                                        extractor,
                                                        http,
                                                        quality,
                                                        seconds,
                                                        maxBytes,
                                                        "premium-confirmed" in opts,
                                                        tube.hasSapCookieAuth(),
                                                        tokenCapabilities = tokenCapabilities,
                                                        onProgress = progress,
                                                    )
                                                }
                                            }
                                        } catch (error: Exception) {
                                            if (error is kotlinx.coroutines.CancellationException &&
                                                error !is kotlinx.coroutines.TimeoutCancellationException
                                            ) {
                                                throw error
                                            }
                                            partial?.let { snapshot ->
                                                snapshot.copy(
                                                    status = "fail",
                                                    reason = safeError(error),
                                                    stages =
                                                        snapshot.stages.mapIndexed { index, stage ->
                                                            if (index == snapshot.stages.lastIndex && stage.status == "pending") {
                                                                stage.copy(status = "fail", reason = safeError(error))
                                                            } else {
                                                                stage
                                                            }
                                                        },
                                                )
                                            } ?: Attempt(
                                                case.alias,
                                                requested,
                                                phase,
                                                iteration,
                                                if (error is java.io.IOException &&
                                                    error.message?.contains("ffmpeg") == true
                                                ) {
                                                    "unsupported"
                                                } else {
                                                    "fail"
                                                },
                                                if (error is StreamResolveException) error.reason.name else safeError(error),
                                                extractionMs = (System.nanoTime() - attemptStarted) / 1_000_000,
                                                attempts =
                                                    (error as? StreamResolveException)
                                                        ?.diagnostics
                                                        ?.attempts
                                                        ?.map {
                                                            "${it.profileId?.let(
                                                                PlaybackClientCatalog::manifestIdFromProfileId,
                                                            ) ?: "unknown"}:${safeAttempt(it.outcome)}"
                                                        }.orEmpty(),
                                            )
                                        }
                                    val httpEntries = synchronized(capture.entries) { capture.entries.drop(from) }
                                    val mediaFailed = terminalMedia4xx(httpEntries.drop(mediaFrom - from), result.transport, result.status)
                                    val observed =
                                        if (mediaFailed) {
                                            result.copy(
                                                status = "fail",
                                                reason = "media_http_status",
                                                stages =
                                                    result.stages.mapIndexed { index, stage ->
                                                        if (index == result.stages.lastIndex && stage.status == "pass") {
                                                            stage.copy(status = "fail", reason = "media_http_status")
                                                        } else {
                                                            stage
                                                        }
                                                    },
                                            )
                                        } else {
                                            result
                                        }
                                    val checked =
                                        if (case.expectedOutcome != null &&
                                            case.expectedOutcome != observed.status ||
                                            case.expectedItag != null &&
                                            observed.status == "pass" &&
                                            observed.itag != case.expectedItag ||
                                            case.minBitrate != null &&
                                            observed.status == "pass" &&
                                            (observed.bitrate ?: 0) < case.minBitrate
                                        ) {
                                            observed.copy(status = "fail", reason = "expectation")
                                        } else {
                                            observed
                                        }
                                    results +=
                                        checked.copy(
                                            http = httpEntries.takeLast(64),
                                            httpDropped = (httpEntries.size - 64).coerceAtLeast(0),
                                            events = synchronized(events) { events.toList() },
                                        )
                                    writeReport(dir, results)
                                    checked.stages.forEach { stage ->
                                        println(
                                            "${case.alias} $requested $phase ${stage.name} ${stage.status} " +
                                                "target=${stage.targetMs} observed=${stage.observedMs ?: "none"} " +
                                                "advanced=${stage.advancedMs} pcm=${stage.pcmBytes} media=${stage.mediaBytes} " +
                                                "settle=${stage.settleMs ?: "none"} elapsed=${stage.elapsedMs} " +
                                                "reason=${stage.reason ?: "none"}",
                                        )
                                    }
                                    println("${case.alias} $requested $phase ${checked.status} ${checked.reason ?: ""}")
                                    delay(pace.toLong())
                                }
                            } finally {
                                try {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                        try {
                                            tokens?.close()
                                        } finally {
                                            cipher.dispose()
                                        }
                                    }
                                } finally {
                                    tube.close()
                                }
                            }
                        }
                    }
                }
            }
        }
        println("Reports: ${dir.path}")
        if (shouldFail(results)) {
            kotlin.system.exitProcess(1)
        }
    } catch (error: Exception) {
        System.err.println("Harness setup failed: ${safeError(error)} (check options, files, proxy and Gradle configuration)")
        kotlin.system.exitProcess(2)
    }
}
