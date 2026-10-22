package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.ResponseTooLargeException
import com.metrolist.innertubex.extraction.ExtractionCipherService
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import com.metrolist.innertubex.utils.decodeQueryComponent
import com.metrolist.innertubex.utils.encodeQueryComponent
import com.metrolist.innertubex.w
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLDecodeException
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram
import meow.nyx.quackjs.UnsupportedPlayerException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Bounded Kotlin player signature/n deobfuscation. Faraday supplies optional landmarks;
 * unknown players use PlayerProgram discovery. Unsupported players fail closed, without a JS fallback.
 */
class YouTubeCipherService(
    private val httpClient: HttpClient,
    private val remotePlayerConfigStore: RemotePlayerConfigStore? = null,
    private val logger: InnerTubeLogger = InnerTubeLogger.NONE,
) : ExtractionCipherService {
    private class PlayerScriptHttpException(
        status: Int,
        val retryable: Boolean,
    ) : IllegalStateException("Player script request failed with HTTP $status")

    private companion object {
        private const val TAG = "YouTubeCipherService"
        private const val OKHTTP_USER_AGENT = "okhttp/5.4.0"
        private const val MAX_PLAYER_CACHE_ENTRIES = 4
        private val COMPILE_FAILURE_COOLDOWN = 60.seconds
        private val PLAYER_CODE_FAILURE_COOLDOWN = 15.seconds
        private const val MAX_PLAYER_SCRIPT_BYTES = 8 * 1024 * 1024
        private const val MAX_MEDIA_URL_LENGTH = 16 * 1024
        private const val MAX_CHALLENGE_LENGTH = 16 * 1024
        private const val MAX_SOLVER_OUTPUT_LENGTH = 65_536
        private val N_PARAMETER_REGEX = Regex("[&?]n=([^&#]+)")
        private val QUERY_PARAMETER_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9._~-]*")
        private val LOG_URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    }

    private data class ProgramKey(
        val playerUrl: String,
        val hints: PlayerHints?,
    )

    private data class FailedProgram(
        val configEpoch: Long,
        val failedAt: TimeSource.Monotonic.ValueTimeMark,
        val error: UnsupportedPlayerException,
    )

    private val programs = LinkedHashMap<ProgramKey, PlayerProgram>()
    private val compileFailures = LinkedHashMap<ProgramKey, FailedProgram>()
    private val playerCodeCache = LinkedHashMap<String, String>()
    private val playerCodeDownloads = mutableMapOf<String, CompletableDeferred<String>>()
    private val playerCodeFailures = LinkedHashMap<String, TimeSource.Monotonic.ValueTimeMark>()
    private val cacheMutex = Mutex()
    private val operationMutex = Mutex()

    // Guarded by cacheMutex. In-flight preloads cannot repopulate caches after dispose().
    private var disposeGeneration = 0L

    override suspend fun initialize() = Unit

    /** Binary-compatible no-op: Kotlin programs do not use preprocessed EJS scripts or this storage. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun setPreprocessedPlayerCache(
        read: suspend (String) -> String?,
        write: suspend (String, String?) -> Unit,
    ) = Unit

    /** Binary-compatible no-op. Use [preloadPlayerCode] to compile a particular player. */
    override suspend fun prewarmEjs() = Unit

    /** Refreshes the remote landmarks after a deciphered stream is rejected by the CDN. */
    suspend fun refreshAfterStreamRejection(): Boolean = remotePlayerConfigStore?.refreshAfterStreamRejection() ?: false

    suspend fun refreshForUnknownPlayer(playerUrl: String): Boolean {
        val hash = RemotePlayerConfigParser.extractPlayerHash(playerUrl) ?: return false
        return remotePlayerConfigStore?.forceRefresh(missingHash = hash) ?: false
    }

    /** Shares the bounded, single-flight player download with timestamp extraction. */
    internal suspend fun playerCode(playerUrl: String): String = getOrDownloadPlayerCode(canonicalPlayerUrl(playerUrl))

    override suspend fun preloadPlayerCode(playerUrl: String) {
        availableProgram(playerUrl)
    }

    override suspend fun processFormats(
        playerUrl: String,
        formats: List<Format>,
    ): List<Format> {
        val approvedUrls = mutableMapOf<String, Boolean>()

        fun String.approved(): Boolean = approvedUrls.getOrPut(this) { isApprovedMediaUrl() }

        fun String.nMatch(): MatchResult? = if (approved()) findNParameterInQuery() else null
        if (formats.none { !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank() || it.url?.nMatch() != null }) {
            return formats
        }
        val program = availableProgram(playerUrl)
        return withContext(Dispatchers.Default) {
            val signatures = mutableMapOf<String, String?>()
            val nValues = mutableMapOf<String, String?>()
            formats.map { format ->
                currentCoroutineContext().ensureActive()
                val signatureField = !format.signatureCipher.isNullOrBlank()
                val cipher = if (signatureField) format.signatureCipher else format.cipher
                var resolved = format
                if (!cipher.isNullOrBlank()) {
                    val params = parseCipherParams(cipher)
                    val url = params?.get("url")
                    val signature = params?.get("s")
                    if (url == null || !url.approved() || signature == null || !params.hasValidSignatureParameter()) {
                        return@map format.copy(url = null)
                    }
                    // Presence, rather than getOrPut, also memoizes blank, unchanged and thrown failures.
                    if (!signatures.containsKey(signature)) signatures[signature] = transform(program, signature, n = false)
                    val newUrl =
                        signatures[signature]?.let { appendQueryParameterToApprovedUrl(url, params["sp"] ?: "signature", it) }
                            ?: return@map format.copy(url = null)
                    resolved =
                        if (signatureField) format.copy(url = newUrl, signatureCipher = null) else format.copy(url = newUrl, cipher = null)
                }
                val url = resolved.url ?: return@map resolved
                val match = url.nMatch() ?: return@map resolved
                val challenge = decodeChallenge(match.groupValues[1]) ?: return@map resolved.copy(url = null)
                if (!nValues.containsKey(challenge)) nValues[challenge] = transform(program, challenge, n = true)
                resolved.copy(url = nValues[challenge]?.let { replaceQueryParameterInApprovedUrl(url, match, it) })
            }
        }
    }

    /** Returns null on an unsupported player, invalid media URL, or unsolved signature. */
    suspend fun deobfuscateSignatureCipher(
        playerUrl: String,
        cipher: String,
    ): String? {
        val params = parseCipherParams(cipher) ?: return null
        val url = params["url"]?.takeIf { it.isApprovedMediaUrl() } ?: return null
        val signature = params["s"] ?: return null
        if (!params.hasValidSignatureParameter()) return null
        val program = availableProgram(playerUrl)
        return withContext(Dispatchers.Default) {
            transform(program, signature, n = false)?.let { appendQueryParameterToApprovedUrl(url, params["sp"] ?: "signature", it) }
        }
    }

    /** Returns null on an unsupported player, invalid media URL, or unchanged/unsolved n. */
    suspend fun processNParameter(
        playerUrl: String,
        url: String,
    ): String? {
        val match = url.findNParameter() ?: return null
        val challenge = decodeChallenge(match.groupValues[1]) ?: return null
        val program = availableProgram(playerUrl)
        return withContext(Dispatchers.Default) {
            transform(program, challenge, n = true)?.let { replaceQueryParameterInApprovedUrl(url, match, it) }
        }
    }

    private suspend fun transform(
        program: PlayerProgram?,
        input: String,
        n: Boolean,
    ): String? {
        currentCoroutineContext().ensureActive()
        if (program == null || input.length > MAX_CHALLENGE_LENGTH) return null
        return try {
            val output = if (n) program.transformN(input) else program.decipherSignature(input)
            currentCoroutineContext().ensureActive()
            output.takeUnless {
                it.isBlank() || it.length > MAX_SOLVER_OUTPUT_LENGTH ||
                    (n && (it == input || it.startsWith("enhanced_except_")))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeChallenge(value: String): String? =
        try {
            decodeQueryComponent(value)
        } catch (_: URLDecodeException) {
            null
        }

    private suspend fun availableProgram(playerUrl: String): PlayerProgram? =
        try {
            programFor(playerUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(playerUrl, e)
            null
        }

    private fun logFailure(
        playerUrl: String,
        error: Exception,
    ) {
        val reason =
            if (error is UnsupportedPlayerException) {
                error.message
                    ?.replace(LOG_URL_REGEX, "<redacted>")
                    ?.replace('\n', ' ')
                    ?.replace('\r', ' ')
                    ?.take(240)
            } else {
                null
            }
        logger.w(
            TAG,
            "Kotlin player unavailable player=${RemotePlayerConfigParser.extractPlayerHash(
                playerUrl,
            ) ?: "unknown"} type=${error::class.simpleName}${reason?.let { " reason=$it" }.orEmpty()}",
        )
    }

    private suspend fun programFor(playerUrl: String): PlayerProgram? {
        validatedPlayerScriptUrl(playerUrl)
        val generation = cacheMutex.withLock { disposeGeneration }
        val store = remotePlayerConfigStore
        val config =
            store?.let {
                it.getConfig(playerUrl) ?: run {
                    it.forceRefresh(missingHash = RemotePlayerConfigParser.extractPlayerHash(playerUrl))
                    it.getConfig(playerUrl)
                }
            }
        val hints = config?.let { PlayerHints(it.signatureExpression, it.nClass, it.signatureTimestamp) }
        val epoch = store?.configEpoch ?: 0L
        val preferredUrl =
            (if (hints != null || "/player_es6.vflset/" in playerUrl) faradayCipherPlayerUrl(playerUrl) else null)
                ?: canonicalPlayerUrl(playerUrl)
        try {
            programAt(ProgramKey(preferredUrl, hints), epoch, generation)?.let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (preferredUrl == playerUrl) throw e
            logFailure(preferredUrl, e)
        }
        // Keep the exact supplied variant for discovery when the preferred source disappears or is unsupported.
        return if (preferredUrl == playerUrl) null else programAt(ProgramKey(playerUrl, null), epoch, generation)
    }

    private suspend fun programAt(
        key: ProgramKey,
        epoch: Long,
        generation: Long,
    ): PlayerProgram? {
        cacheMutex.withLock {
            if (generation != disposeGeneration) return null
            programs.remove(key)?.let {
                programs[key] = it
                return it
            }
            cachedCompilationFailureLocked(key, epoch)?.let { throw it }
        }
        // Never hold the CPU-operation lock during network IO: speculative preload must not block playback.
        val code = getOrDownloadPlayerCode(key.playerUrl, generation)
        return withContext(Dispatchers.Default) {
            operationMutex.withLock {
                currentCoroutineContext().ensureActive()
                cacheMutex.withLock {
                    if (generation != disposeGeneration) return@withContext null
                    programs.remove(key)?.let {
                        programs[key] = it
                        return@withContext it
                    }
                    cachedCompilationFailureLocked(key, epoch)?.let { throw it }
                }
                val program =
                    try {
                        try {
                            PlayerProgram.compile(code, key.hints)
                        } catch (e: UnsupportedPlayerException) {
                            if (key.hints == null) throw e
                            PlayerProgram.compile(code)
                        }
                    } catch (e: UnsupportedPlayerException) {
                        currentCoroutineContext().ensureActive()
                        cacheMutex.withLock {
                            compileFailures.remove(key)
                            compileFailures[key] = FailedProgram(epoch, TimeSource.Monotonic.markNow(), e)
                            while (compileFailures.size > MAX_PLAYER_CACHE_ENTRIES) compileFailures.remove(compileFailures.keys.first())
                        }
                        throw e
                    }
                currentCoroutineContext().ensureActive()
                cacheMutex.withLock {
                    programs.remove(key)
                    programs[key] = program
                    while (programs.size > MAX_PLAYER_CACHE_ENTRIES) programs.remove(programs.keys.first())
                    compileFailures.remove(key)
                }
                program
            }
        }
    }

    private fun cachedCompilationFailureLocked(
        key: ProgramKey,
        epoch: Long,
    ): UnsupportedPlayerException? =
        compileFailures[key]?.takeIf { it.configEpoch == epoch && it.failedAt.elapsedNow() < COMPILE_FAILURE_COOLDOWN }?.error

    private suspend fun getOrDownloadPlayerCode(
        playerUrl: String,
        expectedGeneration: Long? = null,
    ): String {
        while (true) {
            var cachedCode: String? = null
            var download: CompletableDeferred<String>? = null
            var ownsDownload = false
            var generation = 0L
            cacheMutex.withLock {
                generation = disposeGeneration
                check(expectedGeneration == null || expectedGeneration == generation) { "Player operation disposed" }
                cachedCode = playerCodeCache.remove(playerUrl)?.also { playerCodeCache[playerUrl] = it }
                if (cachedCode == null) {
                    playerCodeFailures[playerUrl]
                        ?.takeIf { it.elapsedNow() < PLAYER_CODE_FAILURE_COOLDOWN }
                        ?.let { throw IllegalStateException("Player script download recently failed") }
                    download = playerCodeDownloads[playerUrl]
                    if (download == null) {
                        download = CompletableDeferred()
                        playerCodeDownloads[playerUrl] = download
                        ownsDownload = true
                    }
                }
            }
            cachedCode?.let { return it }
            val activeDownload = checkNotNull(download)
            if (!ownsDownload) {
                try {
                    return activeDownload.await()
                } catch (e: CancellationException) {
                    // A cancelled owner does not cancel a live waiter; it may reserve its own download.
                    currentCoroutineContext().ensureActive()
                    cacheMutex.withLock {
                        if (playerCodeDownloads[playerUrl] === activeDownload) playerCodeDownloads.remove(playerUrl)
                    }
                    continue
                }
            }
            try {
                val playerCode = downloadPlayerScript(playerUrl)
                currentCoroutineContext().ensureActive()
                cacheMutex.withLock {
                    // An old owner must neither refill the cache nor remove a post-dispose reservation.
                    if (generation == disposeGeneration && playerCodeDownloads[playerUrl] === activeDownload) {
                        playerCodeCache.clear()
                        playerCodeCache[playerUrl] = playerCode
                        playerCodeFailures.remove(playerUrl)
                        playerCodeDownloads.remove(playerUrl)
                    }
                }
                activeDownload.complete(playerCode)
                return playerCode
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    // Wake waiters before contending on the cache lock during cancellation cleanup.
                    activeDownload.completeExceptionally(e)
                    cacheMutex.withLock {
                        if (generation == disposeGeneration && playerCodeDownloads[playerUrl] === activeDownload) {
                            playerCodeDownloads.remove(playerUrl)
                            if (e !is CancellationException) {
                                playerCodeFailures.remove(playerUrl)
                                playerCodeFailures[playerUrl] = TimeSource.Monotonic.markNow()
                                while (playerCodeFailures.size >
                                    MAX_PLAYER_CACHE_ENTRIES
                                ) {
                                    playerCodeFailures.remove(playerCodeFailures.keys.first())
                                }
                            }
                        }
                    }
                }
                throw e
            }
        }
    }

    private suspend fun downloadPlayerScript(playerUrl: String): String {
        val validatedUrl = validatedPlayerScriptUrl(playerUrl)
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            try {
                val response =
                    httpClient.getTextWithoutRedirects(validatedUrl, MAX_PLAYER_SCRIPT_BYTES) {
                        header(HttpHeaders.UserAgent, OKHTTP_USER_AGENT)
                        header(HttpHeaders.Accept, "*/*")
                        header("Referer", "https://www.youtube.com/")
                    }
                response.body?.let { return it }
                val failure =
                    PlayerScriptHttpException(
                        status = response.status.value,
                        retryable = response.status.value in setOf(408, 425, 429, 500, 502, 503, 504),
                    )
                lastFailure = failure
                if (!failure.retryable || attempt == 2) throw failure
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
                if ((e is PlayerScriptHttpException && !e.retryable) || e is ResponseTooLargeException || attempt == 2) throw e
            }
            delay(250L shl attempt)
        }
        throw lastFailure ?: IllegalStateException("Player script request failed")
    }

    private fun parseCipherParams(cipher: String): Map<String, String>? {
        if (cipher.length > MAX_MEDIA_URL_LENGTH * 4) return null
        val params = mutableMapOf<String, String>()
        for (pair in cipher.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                params[parts[0]] =
                    try {
                        decodeQueryComponent(parts[1])
                    } catch (_: URLDecodeException) {
                        return null
                    }
            }
        }
        return params
    }

    internal fun appendQueryParameter(
        url: String,
        name: String,
        value: String,
    ): String? = if (url.isApprovedMediaUrl()) appendQueryParameterToApprovedUrl(url, name, value) else null

    private fun appendQueryParameterToApprovedUrl(
        url: String,
        name: String,
        value: String,
    ): String? {
        if (!QUERY_PARAMETER_NAME_REGEX.matches(name) || value.isBlank() || value.length > MAX_SOLVER_OUTPUT_LENGTH) return null
        val fragmentStart = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val hasQuery = url.indexOf('?').let { it >= 0 && it < fragmentStart }
        val separator =
            when {
                !hasQuery -> "?"
                fragmentStart > 0 && url[fragmentStart - 1] in "?&" -> ""
                else -> "&"
            }
        return buildString(url.length + name.length + value.length + 2) {
            append(url, 0, fragmentStart)
            append(separator)
            append(name)
            append('=')
            append(encodeQueryComponent(value))
            append(url, fragmentStart, url.length)
        }.takeIf { it.length <= MAX_MEDIA_URL_LENGTH }
    }

    internal fun replaceQueryParameter(
        url: String,
        match: MatchResult,
        value: String,
    ): String? = if (url.isApprovedMediaUrl()) replaceQueryParameterInApprovedUrl(url, match, value) else null

    private fun replaceQueryParameterInApprovedUrl(
        url: String,
        match: MatchResult,
        value: String,
    ): String? {
        val fragmentStart = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        if (match.range.first >= fragmentStart || value.isBlank() || value.length > MAX_SOLVER_OUTPUT_LENGTH) return null
        val start = match.range.last + 1 - match.groupValues[1].length
        return url.replaceRange(start, match.range.last + 1, encodeQueryComponent(value)).takeIf { it.length <= MAX_MEDIA_URL_LENGTH }
    }

    private fun String.findNParameter(): MatchResult? = if (isApprovedMediaUrl()) findNParameterInQuery() else null

    private fun String.findNParameterInQuery(): MatchResult? {
        val fragmentStart = indexOf('#').takeIf { it >= 0 } ?: length
        return N_PARAMETER_REGEX.find(this)?.takeIf { it.range.first < fragmentStart }
    }

    private fun String.isApprovedMediaUrl(): Boolean {
        if (length !in 1..MAX_MEDIA_URL_LENGTH) return false
        val url = runCatching { Url(this) }.getOrNull() ?: return false
        val isMediaHost =
            url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com") ||
                url.host == "youtube.com" || url.host.endsWith(".youtube.com")
        return url.protocol.name == "https" && url.port == 443 && isMediaHost && url.encodedPath == "/videoplayback" &&
            url.user == null && url.password == null
    }

    private fun Map<String, String>.hasValidSignatureParameter(): Boolean = get("sp")?.let(QUERY_PARAMETER_NAME_REGEX::matches) != false

    suspend fun dispose() =
        operationMutex.withLock {
            cacheMutex.withLock {
                disposeGeneration++
                programs.clear()
                compileFailures.clear()
                playerCodeCache.clear()
                playerCodeFailures.clear()
                playerCodeDownloads.clear()
            }
        }
}

internal fun validatedPlayerScriptUrl(value: String): Url {
    val url = Url(value)
    val isYouTubeHost =
        url.host == "youtube.com" || url.host.endsWith(".youtube.com") ||
            url.host == "youtube-nocookie.com" || url.host.endsWith(".youtube-nocookie.com")
    require(
        url.protocol.name == "https" && url.port == 443 && url.user == null && url.password == null && isYouTubeHost &&
            url.encodedPath.startsWith("/s/player/") && url.encodedPath.endsWith(".js"),
    ) { "Player script URL must use an approved YouTube HTTPS endpoint" }
    return url
}
