package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.d
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.InMemoryClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.i
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.isSabrAudioCandidate
import com.metrolist.innertubex.sabr.requireAllowedSabrUrl
import com.metrolist.innertubex.sabr.sabrRequestOrigin
import com.metrolist.innertubex.sabr.toSabrBootstrap
import com.metrolist.innertubex.utils.encodeQueryComponent
import com.metrolist.innertubex.w
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.util.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalSabrApi::class)
class InnerTubeExtractor internal constructor(
    private val configParser: YtConfigParser,
    private val clientDirector: PlayerClientDirector,
    private val cipherService: ExtractionCipherService,
    private val innerTube: InnerTube,
    private val tokenProvider: TokenProvider = UnavailableTokenProvider,
    private val logger: InnerTubeLogger = InnerTubeLogger.NONE,
    private val tvBearerProviderTimeoutMs: Long = TV_BEARER_PROVIDER_TIMEOUT_MS,
    private val now: () -> Instant = { Clock.System.now() },
    private val extractionTimeBudgetMs: Long = 30_000L,
    private val bootstrapWriteScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : StreamExtractor {
    constructor(
        configParser: YtConfigParser,
        /** Usually a [YouTubeCipherService]; hosts may isolate cipher solving behind their own implementation. */
        cipherService: ExtractionCipherService,
        innerTube: InnerTube,
        /** Defaults to a [ContentAwareFallbackStrategy] that scores clients with [clientHealthMonitor]. */
        fallbackStrategy: ClientFallbackStrategy? = null,
        tokenProvider: TokenProvider? = null,
        /** Defaults to process-local memory; pass [ClientHealthMonitor.NONE] for stateless selection. */
        clientHealthMonitor: ClientHealthMonitor =
            InMemoryClientHealthMonitor(sessionGeneration = { innerTube.sessionSnapshot().generation }),
        logger: InnerTubeLogger = InnerTubeLogger.NONE,
    ) : this(
        configParser = configParser,
        clientDirector =
            PlayerClientDirector(
                innerTube = innerTube,
                fallbackStrategy = fallbackStrategy ?: ContentAwareFallbackStrategy(clientHealthMonitor),
                tokenProvider = tokenProvider ?: UnavailableTokenProvider,
                clientHealthMonitor = clientHealthMonitor,
                logger = logger,
            ),
        cipherService = cipherService,
        innerTube = innerTube,
        tokenProvider = tokenProvider ?: UnavailableTokenProvider,
        logger = logger,
    )

    private companion object {
        private const val TAG = "InnerTubeExtractor"
        private const val DEFAULT_BOUNDED_RANGE_CHUNK_BYTES = 1_048_576L

        // An unusable cached config is refetched immediately, so the TTL only bounds idle staleness.
        private const val PLAYER_CONFIG_CACHE_TTL_MS = 3 * 60 * 60 * 1000L
        private const val PREWARM_VIDEO_ID = "dQw4w9WgXcQ"
        private const val WEB_EMBEDDED_PLAYER_ID = "WEB_EMBEDDED_PLAYER"
        private const val WEB_KIDS_ID = "WEB_KIDS"
        private val PO_TOKEN_PREFETCH_TIMEOUT = 18.seconds
        private const val TV_BEARER_PROVIDER_TIMEOUT_MS = 8_000L
        private val EXPIRE_REGEX = Regex("[?&]expire=([0-9]+)")
        private val N_PARAMETER_REGEX = Regex("(?:[?&]|%26)n(?:=|%3[dD])", RegexOption.IGNORE_CASE)
        private val CODECS_REGEX = Regex("codecs=\"([^\"]+)\"")

        // Allow a cached-config pass and a fresh-config pass, plus native probes.
        private val MAX_PLAYER_REQUESTS_PER_EXTRACTION = PlaybackClientCatalog.automaticManifests.size * 4 + 2
    }

    private data class CachedPlayerConfig(
        val config: PlayerConfig,
        val cachedAtMs: Long,
        val identity: ConfigIdentity,
        val usedLoginCookies: Boolean,
    )

    /** The only session fields a watch or embed page request depends on. */
    private data class ConfigIdentity(
        val locale: YouTubeLocale,
        val cookieFingerprint: String?,
    )

    private fun configIdentity(useLoginCookies: Boolean): ConfigIdentity =
        innerTube.sessionSnapshot().let {
            ConfigIdentity(
                it.locale,
                it.cookie?.takeIf { useLoginCookies }?.let { cookie ->
                    sha256(cookie.encodeToByteArray()).toHexString()
                },
            )
        }

    private val playerConfigCache = mutableMapOf<Boolean, CachedPlayerConfig>()

    private data class TerminalCacheKey(
        val videoId: String,
        val generation: Long,
        val hints: ContentHints,
        val audioQuality: AudioQuality,
        val excludedClients: Set<String>,
    ) {
        override fun toString(): String = "TerminalCacheKey(redacted)"
    }

    private data class TerminalVerdict(
        val reason: StreamResolveException.Reason,
        val expiresAt: Instant,
    )

    private val terminalCache = linkedMapOf<TerminalCacheKey, TerminalVerdict>()
    private val terminalCacheMutex = Mutex()

    private suspend fun cachedTerminalVerdict(key: TerminalCacheKey): TerminalVerdict? =
        terminalCacheMutex.withLock {
            val currentTime = now()
            terminalCache.entries.removeAll { it.key.generation != key.generation || it.value.expiresAt <= currentTime }
            terminalCache.remove(key)?.also { terminalCache[key] = it }
        }

    private suspend fun cacheTerminalVerdict(
        key: TerminalCacheKey,
        diagnostics: ExtractionDiagnostics,
        reason: StreamResolveException.Reason,
    ) {
        if (innerTube.sessionSnapshot().generation != key.generation || diagnostics.sawPlayableResponse ||
            (reason == StreamResolveException.Reason.UNAVAILABLE && !diagnostics.unavailable) ||
            diagnostics.requestFailures.isNotEmpty() || diagnostics.requestBudget.terminalFailureReason() != reason ||
            diagnostics.attempts.any { !it.outcome.startsWith("selection:") && !it.outcome.startsWith("playability:") }
        ) {
            return
        }
        terminalCacheMutex.withLock {
            terminalCache.remove(key)
            terminalCache[key] = TerminalVerdict(reason, now() + 60.seconds)
            if (terminalCache.size > 256) terminalCache.remove(terminalCache.keys.first())
        }
    }

    // Guarded by playerConfigCacheMutex.
    private var readPlayerBootstrap: (suspend () -> String?)? = null
    private var writePlayerBootstrap: (suspend (String) -> Unit)? = null
    private var playerBootstrapRestoreAttempted = false
    private val bootstrapRestoreMutex = Mutex()
    private var bootstrapStoreGeneration = 0L
    private var bootstrapWriterRunning = false
    private var pendingPlayerBootstrap: Pair<suspend (String) -> Unit, String>? = null
    private var writtenPlayerBootstrap: Pair<suspend (String) -> Unit, String>? = null

    /**
     * Installs optional host storage for the public player bootstrap: player URL, signature timestamp and client
     * version of the signed-out watch config. After a restart the first cipher-dependent extraction can then skip the
     * watch-page request. Nothing session- or account-bound is stored, and a stale or rejected value simply refetches.
     */
    suspend fun setPlayerBootstrapCache(
        read: suspend () -> String?,
        write: suspend (String) -> Unit,
    ) = playerConfigCacheMutex.withLock {
        readPlayerBootstrap = read
        writePlayerBootstrap = write
        playerBootstrapRestoreAttempted = false
        bootstrapStoreGeneration++
        pendingPlayerBootstrap = null
        writtenPlayerBootstrap = null
    }

    private suspend fun restorePlayerBootstrap(nowMs: Long): CachedPlayerConfig? {
        return bootstrapRestoreMutex.withLock {
            val (read, generation, identity) =
                playerConfigCacheMutex.withLock {
                    getCachedPlayerConfigLocked(false, nowMs)?.let { return it }
                    if (playerBootstrapRestoreAttempted) return null
                    val read = readPlayerBootstrap ?: return null
                    // The watch page must still supply visitor data when the session has none.
                    if (innerTube.sessionSnapshot().visitorData.isNullOrBlank()) return null
                    playerBootstrapRestoreAttempted = true
                    Triple(read, bootstrapStoreGeneration, configIdentity(useLoginCookies = false))
                }
            val stored =
                try {
                    withTimeoutOrNull(1.seconds) { read() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            val snapshot = PlayerBootstrapSnapshot.decode(stored) ?: return null
            val config = snapshot.toPlayerConfig(identity.locale, nowMs, PLAYER_CONFIG_CACHE_TTL_MS) ?: return null
            return playerConfigCacheMutex.withLock {
                if (generation != bootstrapStoreGeneration || identity != configIdentity(useLoginCookies = false)) return@withLock null
                getCachedPlayerConfigLocked(false, nowMs)
                    ?: CachedPlayerConfig(config, snapshot.savedAtMs, identity, usedLoginCookies = false)
                        .also { playerConfigCache[false] = it }
            }
        }
    }

    private fun persistPlayerBootstrapLocked(
        config: PlayerConfig,
        identity: ConfigIdentity,
    ) {
        val write = writePlayerBootstrap ?: return
        val encoded = PlayerBootstrapSnapshot.of(config, identity.locale, Clock.System.now().toEpochMilliseconds())?.encode() ?: return
        pendingPlayerBootstrap = write to encoded
        if (bootstrapWriterRunning) return
        bootstrapWriterRunning = true
        // One writer drains only the latest pending value, even when host storage stalls.
        bootstrapWriteScope.launch {
            while (true) {
                val pending =
                    playerConfigCacheMutex.withLock {
                        val pending = pendingPlayerBootstrap?.takeUnless { it == writtenPlayerBootstrap }
                        pendingPlayerBootstrap = null
                        if (pending == null) bootstrapWriterRunning = false
                        pending
                    } ?: return@launch
                try {
                    pending.first(pending.second)
                    playerConfigCacheMutex.withLock { writtenPlayerBootstrap = pending }
                } catch (_: Exception) {
                    // Best effort: the next successful fetch writes again.
                }
            }
        }
    }

    private val playerConfigCacheMutex = Mutex()
    private val playerConfigFetchMutexes = mapOf(false to Mutex(), true to Mutex())

    /**
     * Optionally compares a fresh, host-authorized TV player response with ordinary playback discovery.
     * The provider is called once per request; the host owns consent, secure storage, refresh, and revocation.
     * This is experimental, audio-only, and does not promise that Premium exposes a higher-quality stream.
     */
    public suspend fun extractWithAuthenticatedTvDiscovery(
        videoId: String,
        confirmedPremium: Boolean,
        credentialProvider: TvBearerCredentialProvider,
        hints: ContentHints = ContentHints(),
        excludedClients: Set<String> = emptySet(),
        audioQuality: AudioQuality = AudioQuality.HIGH,
        clientPlaybackNonce: String = generateClientPlaybackNonce(),
    ): ExtractedStream? {
        if (
            !confirmedPremium ||
            audioQuality != AudioQuality.HIGH ||
            hints.playbackClientOverrideId != null ||
            hints.wantVideo ||
            hints.isUploaded == true ||
            hints.isLive == true
        ) {
            return extract(videoId, hints, excludedClients, audioQuality, clientPlaybackNonce)
        }
        val baselineHints = hints.withPremium()
        val baselineGeneration = innerTube.sessionSnapshot().generation
        var baseline: ExtractedStream? = null
        var baselineFailure: Exception? = null
        try {
            baseline = extract(videoId, baselineHints, excludedClients, audioQuality, clientPlaybackNonce)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            baselineFailure = error
        }
        ensureSessionGeneration(baselineGeneration)

        val credential =
            withTvBearerProviderTimeout {
                credentialProvider.getCredential(videoId, baselineGeneration)
            }?.takeIf { it.isUsableFor(baselineGeneration, TV_BEARER_MINIMUM_LIFETIME, now()) }
        ensureSessionGeneration(baselineGeneration)
        if (credential == null) return baselineOrThrow(baseline, baselineFailure)
        if (!isCredentialCurrent(credentialProvider, credential)) {
            ensureSessionGeneration(baselineGeneration)
            return baselineOrThrow(baseline, baselineFailure)
        }
        ensureSessionGeneration(baselineGeneration)

        val candidateDiagnostics = ExtractionDiagnostics(maxPlayerRequests = 1)
        val candidate =
            try {
                candidateDiagnostics.requestBudget.withinTime {
                    extractWithCachedConfig(
                        videoId = videoId,
                        hints = baselineHints,
                        excludedClients = excludedClients,
                        clientPlaybackNonce = clientPlaybackNonce,
                        useLoginCookies = false,
                        totalStartMs = Clock.System.now().toEpochMilliseconds(),
                        audioQuality = audioQuality,
                        diagnostics = candidateDiagnostics,
                        tvBearerCredential = credential,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.w(TAG, "TV bearer discovery unavailable", details = mapOf("exceptionType" to (error::class.simpleName ?: "unknown")))
                null
            }
        ensureSessionGeneration(baselineGeneration)
        if (candidate != null) {
            if (!isCredentialCurrent(credentialProvider, credential)) {
                ensureSessionGeneration(baselineGeneration)
                return baselineOrThrow(baseline, baselineFailure)
            }
            ensureSessionGeneration(baselineGeneration)
            if (!credential.isUsableFor(baselineGeneration, TV_BEARER_MINIMUM_LIFETIME, now())) {
                return baselineOrThrow(baseline, baselineFailure)
            }
            if (candidate.isStrictlyHigherQualityThan(baseline)) return candidate
        }
        return baselineOrThrow(baseline, baselineFailure)
    }

    private fun baselineOrThrow(
        baseline: ExtractedStream?,
        failure: Exception?,
    ): ExtractedStream? {
        if (baseline != null) return baseline
        if (failure != null) throw failure
        return null
    }

    private suspend fun isCredentialCurrent(
        provider: TvBearerCredentialProvider,
        credential: TvBearerCredential,
    ): Boolean = withTvBearerProviderTimeout { provider.isCredentialCurrent(credential) } == true

    private suspend fun <T> withTvBearerProviderTimeout(block: suspend () -> T): T? =
        try {
            withTimeoutOrNull(tvBearerProviderTimeoutMs) { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun ensureSessionGeneration(generation: Long) {
        if (innerTube.sessionSnapshot().generation != generation) {
            throw CancellationException("InnerTube session changed during TV discovery")
        }
    }

    private fun currentExtractionSession(expected: InnerTube.SessionSnapshot): InnerTube.SessionSnapshot {
        val current = innerTube.sessionSnapshot()
        if (current.generation == expected.generation) return expected
        return current.visitorData
            ?.takeIf(String::isNotBlank)
            ?.let { innerTube.sessionSnapshotWithVisitorData(expected, it) }
            ?: throw CancellationException("InnerTube session changed")
    }

    private fun ExtractedStream.isStrictlyHigherQualityThan(baseline: ExtractedStream?): Boolean {
        baseline ?: return true
        val audioBitrate = bitrate?.takeIf { it > 0 } ?: return false
        val baselineBitrate = baseline.bitrate?.takeIf { it > 0 } ?: return false
        return audioBitrate > baselineBitrate
    }

    override suspend fun prewarm() {
        val startMs = Clock.System.now().toEpochMilliseconds()
        try {
            coroutineScope {
                val initialSession = innerTube.sessionSnapshot()
                val fetchedVisitorData =
                    if (tokenProvider.capabilities.providers.isNotEmpty()) {
                        initialSession.visitorData ?: innerTube.fetchFreshVisitorData(initialSession)
                    } else {
                        null
                    }
                val session = currentExtractionSession(initialSession)

                fun warmToken(visitorData: String?) =
                    visitorData?.takeIf { it.isNotBlank() && tokenProvider.capabilities.providers.isNotEmpty() }?.let {
                        async {
                            currentExtractionSession(session)
                            val token =
                                withTimeoutOrNull(PO_TOKEN_PREFETCH_TIMEOUT) {
                                    try {
                                        tokenProvider.getPoToken(PREWARM_VIDEO_ID, it, session.cookie)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            currentExtractionSession(session)
                            token
                        }
                    }
                val earlyTokenWarmup = warmToken(fetchedVisitorData)
                val defaultConfigFetch =
                    async {
                        (
                            getCachedPlayerConfig(false, Clock.System.now().toEpochMilliseconds())
                                ?: fetchPlayerConfig(PREWARM_VIDEO_ID, false, requestSession = session)
                        ).config
                    }
                val authenticatedConfigFetch =
                    if (!session.sapisid.isNullOrBlank()) {
                        async {
                            try {
                                fetchPlayerConfig(PREWARM_VIDEO_ID, true, requestSession = session).config
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.w(
                                    TAG,
                                    "authenticated player config prewarm failed",
                                    details =
                                        mapOf("exceptionType" to (e::class.simpleName ?: "unknown")),
                                )
                                null
                            }
                        }
                    } else {
                        null
                    }
                cipherService.initialize()
                val configs = listOfNotNull(defaultConfigFetch.await(), authenticatedConfigFetch?.await())
                currentExtractionSession(session)
                val tokenWarmup = earlyTokenWarmup ?: warmToken(configs.firstNotNullOfOrNull(PlayerConfig::visitorData))
                configs
                    .asReversed()
                    .firstOrNull { it.playerUrl.isNotBlank() }
                    ?.let { cipherService.preloadPlayerCode(it.playerUrl) }
                tokenWarmup?.await()
                currentExtractionSession(session)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "player config prewarm failed", details = mapOf("exceptionType" to (e::class.simpleName ?: "unknown")))
            cipherService.prewarmEjs()
        }
        logger.d(TAG, "prewarm completed", details = mapOf("elapsedMs" to (Clock.System.now().toEpochMilliseconds() - startMs).toString()))
    }

    override suspend fun extract(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        audioQuality: AudioQuality,
        clientPlaybackNonce: String,
    ): ExtractedStream? {
        val terminalKey =
            if (hints.playbackClientOverrideId == null) {
                TerminalCacheKey(videoId, innerTube.sessionSnapshot().generation, hints, audioQuality, excludedClients.toSet())
            } else {
                null
            }
        terminalKey?.let { key ->
            cachedTerminalVerdict(key)?.let { verdict ->
                ensureSessionGeneration(key.generation)
                throw StreamResolveException(
                    reason = verdict.reason,
                    message =
                        if (verdict.reason ==
                            StreamResolveException.Reason.AGE_RESTRICTED
                        ) {
                            "This track is age-restricted."
                        } else {
                            "This track is unavailable."
                        },
                )
            }
        }
        val totalStart = Clock.System.now().toEpochMilliseconds()
        val diagnostics =
            ExtractionDiagnostics(
                maxPlayerRequests = if (hints.playbackClientOverrideId != null) 1 else MAX_PLAYER_REQUESTS_PER_EXTRACTION,
            )
        return try {
            diagnostics.requestBudget.withinTime {
                diagnostics.requestBudget.withTokenScope {
                    extractWithDiagnostics(
                        videoId,
                        hints,
                        excludedClients,
                        audioQuality,
                        clientPlaybackNonce,
                        totalStart,
                        diagnostics,
                    ).also { diagnostics.ensureSessionCurrent() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlayerRequestBudgetExhaustedException) {
            throw StreamResolveException(
                reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                message = "Extraction time budget exhausted.",
                cause = e,
                diagnostics = diagnostics.snapshot(),
            )
        } catch (e: StreamResolveException) {
            terminalKey?.let { cacheTerminalVerdict(it, diagnostics, e.reason) }
            if (e.diagnostics != null) throw e
            throw StreamResolveException(
                reason = e.reason,
                message = "Unable to resolve stream data.",
                cause = e,
                diagnostics = diagnostics.snapshot(),
            )
        } catch (e: Exception) {
            throw StreamResolveException(
                reason = StreamResolveException.Reason.NETWORK,
                message = "Unable to fetch stream data from YouTube.",
                cause = e,
                diagnostics = diagnostics.snapshot(),
            )
        }
    }

    private suspend fun extractWithDiagnostics(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        audioQuality: AudioQuality,
        clientPlaybackNonce: String,
        totalStart: Long,
        diagnostics: ExtractionDiagnostics,
    ): ExtractedStream? {
        logger.d(
            TAG,
            "stream extraction started",
            details = mapOf("wantVideo" to hints.wantVideo.toString()),
        )

        val embeddedOverrideId =
            hints.playbackClientOverrideId?.takeIf { id ->
                PlaybackClientCatalog.findManifest(id)?.let { manifest ->
                    manifest.request.embedded
                } == true
            }
        val embeddedOverride = embeddedOverrideId != null
        if (embeddedOverride) {
            val embeddedStream =
                extractWithEmbeddedConfig(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    totalStartMs = totalStart,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                )
            if (embeddedStream != null) return embeddedStream
            throwExtractionFailure(diagnostics)
        }

        val authenticatedPremiumHighQuality =
            hints.premium && audioQuality == AudioQuality.HIGH && innerTube.hasSapCookieAuth()
        val configFreeEligible =
            hints.playbackClientOverrideId == null && !hints.wantVideo &&
                hints.isAgeRestricted != true &&
                hints.isUploaded != true && hints.isLive != true &&
                !authenticatedPremiumHighQuality
        val deferConfigFree =
            configFreeEligible && hints.isKidsContent != true && !hints.sabrFirst &&
                innerTube.sessionSnapshot().visitorData.isNullOrBlank() &&
                clientDirector.includesVisitorBackedLegacy(excludedClients)

        suspend fun extractConfigFree(): ExtractedStream? =
            extractWithConfig(
                videoId = videoId,
                hints = hints,
                excludedClients = excludedClients,
                clientPlaybackNonce = clientPlaybackNonce,
                playerConfig = PlayerConfig("", null, innerTube.sessionSnapshot().visitorData, null),
                totalStartMs = totalStart,
                allowCipherProcessing = false,
                audioQuality = audioQuality,
                diagnostics = diagnostics,
            )
        if (configFreeEligible && !deferConfigFree) extractConfigFree()?.let { return it }
        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)

        suspend fun extractWithWatchConfig(useLoginCookies: Boolean): ExtractedStream? =
            try {
                extractWithCachedConfig(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    useLoginCookies = useLoginCookies,
                    totalStartMs = totalStart,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.requestFailures += error
                if (useLoginCookies) {
                    logger.w(
                        TAG,
                        "authenticated watch config unavailable",
                        details = mapOf("exceptionType" to (error::class.simpleName ?: "unknown")),
                    )
                }
                null
            }

        val cookieFirst =
            hints.playbackClientOverrideId == null &&
                innerTube.hasSapCookieAuth() &&
                (
                    authenticatedPremiumHighQuality ||
                        hints.isExplicit == true ||
                        hints.isAgeRestricted == true ||
                        hints.isUploaded == true ||
                        hints.wantVideo
                )
        val stream = extractWithWatchConfig(useLoginCookies = cookieFirst)
        if (stream != null) return stream
        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)
        if (deferConfigFree) extractConfigFree()?.let { return it }
        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)

        if (cookieFirst && !diagnostics.triedPublicConfigBeforeLogin) {
            logger.w(TAG, "signed-out watch config fallback")
            val signedOutConfigStream = extractWithWatchConfig(useLoginCookies = false)
            if (signedOutConfigStream != null) return signedOutConfigStream
        } else if (!cookieFirst && innerTube.hasSapCookieAuth()) {
            logger.w(TAG, "authenticated watch page retry", details = mapOf("authenticated" to "true"))
            val authenticatedStream = extractWithWatchConfig(useLoginCookies = true)
            if (authenticatedStream != null) return authenticatedStream
        }

        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)

        // Embedded player context fallback when the regular watch-page clients fail.
        // Uploaded songs intentionally do not use this path because embedded
        // playback does not expose the user's private upload library.
        if (hints.playbackClientOverrideId == null && hints.isUploaded != true) {
            val embeddedStream =
                extractWithEmbeddedConfig(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    totalStartMs = totalStart,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                )
            if (embeddedStream != null) return embeddedStream
        }

        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)
        val classifiedFailure = classifyPlayabilityFailures(diagnostics.failures)
        if (shouldTryUnknownKidsFallback(diagnostics.requestBudget.withKnownAge(hints), classifiedFailure)) {
            val kidsStream =
                extractWithWebKidsFallback(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    totalStartMs = totalStart,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                )
            if (kidsStream != null) return kidsStream
        }

        throwExtractionFailure(diagnostics)
    }

    private fun shouldTryUnknownKidsFallback(
        hints: ContentHints,
        classifiedFailure: StreamResolveException.Reason?,
    ): Boolean =
        hints.playbackClientOverrideId == null &&
            hints.isKidsContent == null &&
            hints.isExplicit != true &&
            hints.isAgeRestricted != true &&
            hints.isLive != true &&
            hints.isUploaded != true &&
            !hints.wantVideo &&
            classifiedFailure != StreamResolveException.Reason.AGE_RESTRICTED

    private suspend fun extractWithWebKidsFallback(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        clientPlaybackNonce: String,
        totalStartMs: Long,
        audioQuality: AudioQuality,
        diagnostics: ExtractionDiagnostics,
    ): ExtractedStream? {
        val config =
            getCachedPlayerConfig(
                useLoginCookies = false,
                nowMs = Clock.System.now().toEpochMilliseconds(),
            )?.config ?: return null
        logger.d(TAG, "kids fallback attempted", details = mapOf("fallback" to "kids"))
        val fallbackHints =
            hints.copy(
                isKidsContent = true,
                playbackClientOverrideId = WEB_KIDS_ID,
            )
        return extractWithConfig(
            videoId = videoId,
            hints = fallbackHints,
            excludedClients = excludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = config,
            totalStartMs = totalStartMs,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
        )
    }

    private suspend fun extractWithEmbeddedConfig(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        clientPlaybackNonce: String,
        totalStartMs: Long,
        audioQuality: AudioQuality,
        diagnostics: ExtractionDiagnostics,
    ): ExtractedStream? {
        diagnostics.ensureSessionCurrent()
        if (diagnostics.requestBudget.remaining <= 0) return null
        if (hints.playbackClientOverrideId == null &&
            (hints.isAgeRestricted == true || diagnostics.requestBudget.ageRestricted)
        ) {
            return null
        }
        val configStart = Clock.System.now().toEpochMilliseconds()
        val config =
            try {
                configParser.fetchEmbeddedConfig(videoId, useLoginCookies = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.requestFailures += error
                logger.w(TAG, "embedded config unavailable", details = mapOf("exceptionType" to (error::class.simpleName ?: "unknown")))
                return null
            }
        diagnostics.ensureSessionCurrent()
        if (config.encryptedHostFlags.isNullOrBlank()) {
            logger.w(TAG, "embedded config missing encrypted host flags")
            return null
        }
        if (config.signatureTimestamp == null) {
            logger.w(TAG, "embedded config missing signature timestamp")
            return null
        }
        logger.d(
            TAG,
            "embedded config ready",
            details =
                mapOf("elapsedMs" to (Clock.System.now().toEpochMilliseconds() - configStart).toString()),
        )
        val fallbackHints =
            hints.copy(
                playbackClientOverrideId =
                    hints.playbackClientOverrideId?.takeIf { id ->
                        PlaybackClientCatalog.findManifest(id)?.request?.embedded == true
                    } ?: WEB_EMBEDDED_PLAYER_ID,
            )
        return extractWithConfig(
            videoId = videoId,
            hints = fallbackHints,
            excludedClients = excludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = config,
            totalStartMs = totalStartMs,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
        )
    }

    private fun throwExtractionFailure(diagnostics: ExtractionDiagnostics): Nothing {
        diagnostics.requestBudget.timeLeft(Duration.INFINITE)
        if (!diagnostics.sawPlayableResponse) {
            classifyPlayabilityFailures(diagnostics.failures)?.let { reason ->
                throw StreamResolveException(
                    reason = reason,
                    message =
                        when (reason) {
                            StreamResolveException.Reason.AGE_RESTRICTED -> "This track is age-restricted."
                            else -> "This track is unavailable."
                        },
                    diagnostics = diagnostics.snapshot(),
                )
            }
            diagnostics.requestFailures.lastOrNull()?.let { failure ->
                throw StreamResolveException(
                    reason = StreamResolveException.Reason.NETWORK,
                    message = "Unable to fetch stream data from YouTube.",
                    cause = failure,
                    diagnostics = diagnostics.snapshot(),
                )
            }
        }
        throw StreamResolveException(
            reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
            message = "No playable stream found for this track.",
            diagnostics = diagnostics.snapshot(),
        )
    }

    private suspend fun extractWithCachedConfig(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        clientPlaybackNonce: String,
        useLoginCookies: Boolean,
        totalStartMs: Long,
        audioQuality: AudioQuality = AudioQuality.AUTO,
        diagnostics: ExtractionDiagnostics,
        tvBearerCredential: TvBearerCredential? = null,
    ): ExtractedStream? {
        diagnostics.ensureSessionCurrent()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val cachedConfig =
            getCachedPlayerConfig(useLoginCookies, nowMs)
                ?: if (useLoginCookies && !diagnostics.session.visitorData.isNullOrBlank()) {
                    getCachedPlayerConfig(false, nowMs)?.also { diagnostics.triedPublicConfigBeforeLogin = true }
                } else {
                    null
                }
        if (cachedConfig != null) {
            diagnostics.usedAuthenticatedWatchPage =
                diagnostics.usedAuthenticatedWatchPage || cachedConfig.usedLoginCookies
            val cacheAgeMs = nowMs - cachedConfig.cachedAtMs
            logger.d(
                TAG,
                "watch page cache hit",
                details =
                    mapOf(
                        "authenticated" to cachedConfig.usedLoginCookies.toString(),
                        "ageMs" to cacheAgeMs.toString(),
                    ),
            )
            val nonGatePassesBefore = diagnostics.nonGatePasses
            val failuresBefore = diagnostics.failures.size
            val cachedStream =
                try {
                    extractWithConfig(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = excludedClients,
                        clientPlaybackNonce = clientPlaybackNonce,
                        playerConfig = cachedConfig.config,
                        totalStartMs = totalStartMs,
                        audioQuality = audioQuality,
                        diagnostics = diagnostics,
                        tvBearerCredential = tvBearerCredential,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!useLoginCookies || cachedConfig.usedLoginCookies || error is PlayerRequestBudgetExhaustedException) throw error
                    diagnostics.requestFailures += error
                    null
                }
            if (cachedStream != null) return cachedStream
            if (diagnostics.failures.size > failuresBefore && diagnostics.nonGatePasses == nonGatePassesBefore) return null
            logger.w(TAG, "watch page cache unusable", details = mapOf("authenticated" to useLoginCookies.toString()))
        }

        val configStart = Clock.System.now().toEpochMilliseconds()
        if (diagnostics.requestBudget.remaining <= 0) return null
        diagnostics.ensureSessionCurrent()
        val freshCachedConfig = fetchPlayerConfig(videoId, useLoginCookies, cachedConfig, diagnostics.session)
        diagnostics.ensureSessionCurrent()
        diagnostics.usedAuthenticatedWatchPage =
            diagnostics.usedAuthenticatedWatchPage || freshCachedConfig.usedLoginCookies
        logger.d(
            TAG,
            "watch page config ready",
            details =
                mapOf(
                    "fetched" to (freshCachedConfig !== cachedConfig).toString(),
                    "authenticated" to freshCachedConfig.usedLoginCookies.toString(),
                    "elapsedMs" to (Clock.System.now().toEpochMilliseconds() - configStart).toString(),
                ),
        )
        return extractWithConfig(
            videoId = videoId,
            hints = hints,
            excludedClients = excludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = freshCachedConfig.config,
            totalStartMs = totalStartMs,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
            tvBearerCredential = tvBearerCredential,
        )
    }

    private suspend fun fetchPlayerConfig(
        videoId: String,
        useLoginCookies: Boolean,
        unusableConfig: CachedPlayerConfig? = null,
        requestSession: InnerTube.SessionSnapshot,
    ): CachedPlayerConfig =
        playerConfigFetchMutexes.getValue(useLoginCookies).withLock {
            currentExtractionSession(requestSession)
            playerConfigCacheMutex
                .withLock {
                    if (unusableConfig != null && playerConfigCache[useLoginCookies] === unusableConfig) {
                        playerConfigCache.remove(useLoginCookies)
                    }
                    getCachedPlayerConfigLocked(useLoginCookies, Clock.System.now().toEpochMilliseconds())
                }?.let { return@withLock it }
            val expectedIdentity = configIdentity(useLoginCookies)
            val config = configParser.fetchConfig(videoId, useLoginCookies)
            currentExtractionSession(requestSession)
            playerConfigCacheMutex.withLock {
                if (configIdentity(useLoginCookies) != expectedIdentity) {
                    throw CancellationException("InnerTube session changed")
                }
                if (!useLoginCookies) persistPlayerBootstrapLocked(config, expectedIdentity)
                cachePlayerConfig(useLoginCookies, config, expectedIdentity)
            }
        }

    private suspend fun getCachedPlayerConfig(
        useLoginCookies: Boolean,
        nowMs: Long,
    ): CachedPlayerConfig? =
        playerConfigCacheMutex.withLock { getCachedPlayerConfigLocked(useLoginCookies, nowMs) }
            ?: if (!useLoginCookies) restorePlayerBootstrap(nowMs) else null

    private fun getCachedPlayerConfigLocked(
        useLoginCookies: Boolean,
        nowMs: Long,
    ): CachedPlayerConfig? {
        val cached = playerConfigCache[useLoginCookies] ?: return null
        if (cached.identity != configIdentity(useLoginCookies)) {
            playerConfigCache.remove(useLoginCookies)
            return null
        }
        if (nowMs - cached.cachedAtMs <= PLAYER_CONFIG_CACHE_TTL_MS) return cached

        playerConfigCache.remove(useLoginCookies)
        return null
    }

    private fun cachePlayerConfig(
        useLoginCookies: Boolean,
        config: PlayerConfig,
        identity: ConfigIdentity,
    ): CachedPlayerConfig =
        CachedPlayerConfig(
            config = config,
            cachedAtMs = Clock.System.now().toEpochMilliseconds(),
            identity = identity,
            usedLoginCookies = useLoginCookies,
        ).also { playerConfigCache[useLoginCookies] = it }

    private suspend fun extractWithConfig(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        clientPlaybackNonce: String,
        playerConfig: PlayerConfig,
        totalStartMs: Long,
        allowCipherProcessing: Boolean = true,
        audioQuality: AudioQuality = AudioQuality.AUTO,
        diagnostics: ExtractionDiagnostics,
        tvBearerCredential: TvBearerCredential? = null,
    ): ExtractedStream? {
        diagnostics.ensureSessionCurrent()
        if (diagnostics.requestBudget.remaining <= 0) return null
        logger.d(
            TAG,
            "player extraction pass",
            details =
                mapOf(
                    "signatureTimestamp" to (playerConfig.signatureTimestamp != null).toString(),
                    "visitorDataPresent" to (!playerConfig.visitorData.isNullOrBlank()).toString(),
                ),
        )

        val playerStart = Clock.System.now().toEpochMilliseconds()
        val batch =
            clientDirector.fetchPlayerResponses(
                videoId = videoId,
                playerConfig = playerConfig,
                hints = hints,
                excludedClients = excludedClients,
                acceptCipherOnlyResponse = allowCipherProcessing,
                directAudioOnlyClients = !allowCipherProcessing && !hints.wantVideo,
                wantVideo = hints.wantVideo,
                premiumHighQuality = hints.premium && audioQuality == AudioQuality.HIGH,
                requestBudget = diagnostics.requestBudget,
                tvBearerCredential = tvBearerCredential,
            )
        diagnostics.ensureSessionCurrent()
        if (!batch.onlyBootstrapIndependentFailures) diagnostics.nonGatePasses++
        if (batch.onlyUnavailableFailures) diagnostics.unavailable = true
        diagnostics.failures += batch.failures
        diagnostics.requestFailures += batch.requestFailures
        diagnostics.attempts += batch.attempts
        val results = batch.playableResponses
        if (results.isNotEmpty()) diagnostics.sawPlayableResponse = true
        logger.d(
            TAG,
            "player responses received",
            details =
                mapOf(
                    "count" to results.size.toString(),
                    "elapsedMs" to (Clock.System.now().toEpochMilliseconds() - playerStart).toString(),
                ),
        )
        if (results.isEmpty()) {
            logger.w(TAG, "no playable clients")
            return null
        }

        for (result in results) {
            val response = result.response
            val requireBoundedRange = requiresBoundedMediaRange(result.clientName)
            val streamingData = response.streamingData
            if (streamingData == null) {
                logger.w(TAG, "response missing streaming data", details = mapOf("client" to result.clientName))
                continue
            }
            val playbackTracking =
                response.playbackTracking
                    .toPlaybackTrackingData(clientPlaybackNonce)
                    .takeUnless { result.bearerAuthenticated }

            val allFormats =
                (streamingData.formats ?: emptyList()) +
                    streamingData.adaptiveFormats
            val availableVideoHeights =
                allFormats
                    .mapNotNull { format -> format.height?.takeIf { format.width != null && it > 0 } }
                    .distinct()
                    .sorted()

            val isLive = response.videoDetails?.isLiveContent == true

            if (result.useSabr) {
                if (!hints.allowSabr) {
                    logger.d(TAG, "SABR client skipped by request", details = mapOf("client" to result.clientName))
                    continue
                }
                val audioFormat =
                    selectBestAudioFormat(
                        formats = allFormats.filter { response.isSabrAudioCandidate(it) },
                        audioQuality = audioQuality,
                        requireUrl = false,
                    )
                if (audioFormat == null) {
                    logger.d(TAG, "SABR response missing audio format", details = mapOf("client" to result.clientName))
                    continue
                }
                val videoFormat =
                    if (hints.wantVideo) {
                        selectBestVideoFormat(
                            formats = preferredVideoFormats(streamingData, requireUrl = false),
                            requireUrl = false,
                            maxHeight = hints.maxVideoHeight ?: 2160,
                        )
                    } else {
                        null
                    }
                if (hints.wantVideo && videoFormat == null) {
                    logger.d(TAG, "SABR response missing video format", details = mapOf("client" to result.clientName))
                    continue
                }
                val rawSabrUrl = streamingData.serverAbrStreamingUrl
                // Validate the bootstrap (duration, format identities, bounded config) before paying for any n transform.
                val bootstrapResult =
                    runCatching {
                        response.toSabrBootstrap(
                            clientId = result.clientId,
                            clientVersion = result.clientVersion,
                            audioFormat = audioFormat,
                            poToken = result.streamingDataPoToken,
                            requestUserAgent = result.userAgent,
                            requestOrigin = sabrRequestOrigin(result.clientName),
                            videoFormat = videoFormat,
                        )
                    }
                val untransformedBootstrap = bootstrapResult.getOrNull()
                if (untransformedBootstrap == null) {
                    logger.w(
                        TAG,
                        "SABR bootstrap rejected",
                        details =
                            mapOf(
                                "client" to result.clientName,
                                "exceptionType" to (bootstrapResult.exceptionOrNull()?.let { it::class.simpleName } ?: "unknown"),
                            ),
                    )
                    continue
                }
                if (rawSabrUrl?.hasNParameter() == true && !allowCipherProcessing) {
                    logger.d(TAG, "SABR transform deferred", details = mapOf("client" to result.clientName))
                    continue
                }
                val processedSabrUrl =
                    if (rawSabrUrl?.hasNParameter() == true) {
                        cipherService
                            .processFormats(
                                playerUrl = playerConfig.playerUrl,
                                formats =
                                    listOf(
                                        audioFormat.copy(
                                            url = rawSabrUrl,
                                            signatureCipher = null,
                                            cipher = null,
                                        ),
                                    ),
                            ).also { diagnostics.ensureSessionCurrent() }
                            .firstOrNull()
                            ?.url
                    } else {
                        rawSabrUrl
                    }
                if (rawSabrUrl?.hasNParameter() == true && processedSabrUrl == null) {
                    logger.w(TAG, "SABR endpoint rejected", details = mapOf("client" to result.clientName))
                    continue
                }
                // The transformed URL is validated again: checking only the original would not cover the rewrite.
                val trackedSabrUrl =
                    processedSabrUrl?.let { candidate ->
                        runCatching {
                            requireAllowedSabrUrl(appendClientPlaybackNonce(candidate, clientPlaybackNonce))
                        }.getOrNull()
                    } ?: continue
                val bootstrap = untransformedBootstrap.copy(serverAbrStreamingUrl = trackedSabrUrl)
                val expiresAt =
                    streamingData.expiresInSeconds
                        ?.takeIf { it > 0 }
                        ?.let { Clock.System.now() + it.seconds }
                logger.i(TAG, "SABR stream selected", details = mapOf("client" to result.clientName, "profile" to result.profileId))
                return ExtractedStream(
                    videoId = videoId,
                    audioUrl = "sabr://$videoId",
                    headers = emptyMap(),
                    loudnessDb = response.playerConfig?.audioConfig?.loudnessDb ?: audioFormat.loudnessDb,
                    expiresAt = expiresAt,
                    contentLengthBytes = audioFormat.contentLength,
                    itag = audioFormat.itag,
                    mimeType = audioFormat.mimeType.substringBefore(';').trim(),
                    codecs = audioFormat.mimeType.extractCodecs(),
                    bitrate = audioFormat.bitrate,
                    sampleRate = audioFormat.audioSampleRate,
                    clientName = result.clientName,
                    profileId = result.profileId,
                    requireBoundedRange = false,
                    rangeChunkSizeBytes = DEFAULT_BOUNDED_RANGE_CHUNK_BYTES,
                    playbackTracking = playbackTracking,
                    streamDiagnostics = diagnostics.snapshot(),
                    videoUrl = videoFormat?.let { "sabr-video://$videoId" },
                    videoWidth = videoFormat?.width,
                    videoHeight = videoFormat?.height,
                    videoMimeType = videoFormat?.mimeType?.substringBefore(';')?.trim(),
                    videoCodecs = videoFormat?.mimeType?.extractCodecs(),
                    videoBitrate = videoFormat?.bitrate,
                    videoItag = videoFormat?.itag,
                    videoContentLengthBytes = videoFormat?.contentLength,
                    sabrBootstrap = bootstrap,
                    sabrVideoBootstrap = bootstrap.takeIf { videoFormat != null },
                    availableVideoHeights = availableVideoHeights,
                ).withResponseMetadata(response).also { diagnostics.ensureSessionCurrent() }
            }

            val hlsManifestUrl =
                streamingData.hlsManifestUrl
                    ?.withPoToken(result.streamingDataPoToken)
                    ?.let { appendClientPlaybackNonce(it, clientPlaybackNonce) }
            val hasAnyVideoFormat = allFormats.any { it.width != null }
            val needsVideoButNoVideoFormats = hints.wantVideo && !hasAnyVideoFormat

            if (
                !hlsManifestUrl.isNullOrBlank() &&
                hints.allowHls &&
                isAllowedHlsUrl(hlsManifestUrl) &&
                (
                    isLive || allFormats.isEmpty() || needsVideoButNoVideoFormats || result.clientName == "TVHTML5_SIMPLY" ||
                        (requireBoundedRange && !hints.allowBoundedRange)
                )
            ) {
                logger.i(TAG, "HLS stream selected", details = mapOf("client" to result.clientName, "profile" to result.profileId))
                return ExtractedStream(
                    videoId = videoId,
                    audioUrl = hlsManifestUrl,
                    videoUrl = hlsManifestUrl,
                    headers =
                        buildHeaders(
                            diagnostics,
                            result.clientName,
                            result.userAgent,
                            hlsManifestUrl,
                            hints.isUploaded == true && !result.bearerAuthenticated,
                        ),
                    loudnessDb = response.playerConfig?.audioConfig?.loudnessDb,
                    expiresAt = hlsExpiry(streamingData, hlsManifestUrl),
                    contentLengthBytes = null,
                    itag = 96,
                    mimeType = "application/x-mpegURL",
                    codecs = null,
                    bitrate = null,
                    sampleRate = null,
                    clientName = result.clientName,
                    profileId = result.profileId,
                    requireBoundedRange = false,
                    useRangeChunks = false,
                    rangeChunkSizeBytes = mediaRangeChunkSize(result.clientName),
                    playbackTracking = playbackTracking,
                    streamDiagnostics = diagnostics.snapshot(),
                ).withResponseMetadata(response).also { diagnostics.ensureSessionCurrent() }
            }

            if (requireBoundedRange && !hints.allowBoundedRange) {
                logger.d(TAG, "bounded-range client skipped by request", details = mapOf("client" to result.clientName))
                continue
            }

            val directAudioItags =
                allFormats
                    .asSequence()
                    .filter { it.width == null && !it.url.isNullOrBlank() }
                    .map { it.itag }
                    .toSet()

            logger.d(TAG, "format inventory", details = mapOf("client" to result.clientName, "formatCount" to allFormats.size.toString()))

            val directAudioFormats =
                allFormats.filter {
                    it.width == null &&
                        !it.url.isNullOrBlank() &&
                        (it.itag in directAudioItags)
                }
            val directFastPathCandidate = selectBestAudioFormat(directAudioFormats, audioQuality)
            val bestAvailableAudioFormat =
                selectBestAudioFormat(
                    allFormats.filter { format ->
                        format.isAudio &&
                            (
                                !format.url.isNullOrBlank() ||
                                    !format.signatureCipher.isNullOrBlank() ||
                                    !format.cipher.isNullOrBlank()
                            )
                    },
                    audioQuality,
                    requireUrl = false,
                )
            val directAudioIsBest = directFastPathCandidate === bestAvailableAudioFormat
            val wantVideo = hints.wantVideo
            val directVideoFormats =
                if (wantVideo) preferredVideoFormats(streamingData, requireUrl = true) else emptyList()
            val preferredDirectVideo =
                selectBestVideoFormat(directVideoFormats, maxHeight = hints.maxVideoHeight ?: 2160)
            val directFastPathVideo =
                preferredDirectVideo?.takeIf { it.hasReadyVideoUrl() }
                    ?: selectBestVideoFormat(
                        directVideoFormats.filter {
                            it.height == preferredDirectVideo?.height && it.hasReadyVideoUrl()
                        },
                        maxHeight = hints.maxVideoHeight ?: 2160,
                    )
                    ?: preferredDirectVideo
            if (directFastPathCandidate != null && directAudioIsBest && (!wantVideo || directFastPathVideo != null)) {
                val directUrl =
                    appendClientPlaybackNonce(
                        directFastPathCandidate.url.orEmpty().withPoToken(result.streamingDataPoToken),
                        clientPlaybackNonce,
                    )
                if (!isAllowedMediaUrl(directUrl)) continue
                val directVideoUrl =
                    directFastPathVideo
                        ?.url
                        ?.withPoToken(result.streamingDataPoToken)
                        ?.let { appendClientPlaybackNonce(it, clientPlaybackNonce) }
                if (wantVideo && (directVideoUrl.isNullOrBlank() || !isAllowedMediaUrl(directVideoUrl))) continue
                // Fast path: if the selected URLs are already playback-ready, skip cipher/ejs work.
                if (!directUrl.hasNParameter() && directVideoUrl?.hasNParameter() != true) {
                    val expireSeconds = extractExpire(directUrl)
                    val expiresAt = expireSeconds?.let { Instant.fromEpochSeconds(it) }
                    val directHeaders =
                        buildHeaders(
                            diagnostics,
                            result.clientName,
                            result.userAgent,
                            directUrl,
                            hints.isUploaded == true && !result.bearerAuthenticated,
                        )
                    val contentLength =
                        resolveBoundedContentLength(
                            directFastPathCandidate.contentLength,
                            directUrl,
                            directHeaders,
                            requireBoundedRange,
                            diagnostics.requestBudget,
                        )
                    if (requireBoundedRange && contentLength == null) {
                        logger.d(TAG, "bounded media skipped", details = mapOf("client" to result.clientName))
                        continue
                    }
                    val videoContentLength =
                        if (wantVideo) {
                            resolveBoundedContentLength(
                                directFastPathVideo?.contentLength,
                                checkNotNull(directVideoUrl),
                                directHeaders,
                                requireBoundedRange,
                                diagnostics.requestBudget,
                            )
                        } else {
                            null
                        }
                    if (requireBoundedRange && wantVideo && videoContentLength == null) {
                        logger.d(TAG, "bounded video skipped", details = mapOf("client" to result.clientName))
                        continue
                    }
                    val totalElapsed = Clock.System.now().toEpochMilliseconds() - totalStartMs
                    logger.i(
                        TAG,
                        "direct stream selected",
                        details =
                            mapOf(
                                "client" to result.clientName,
                                "profile" to result.profileId,
                                "elapsedMs" to totalElapsed.toString(),
                                "boundedRange" to requireBoundedRange.toString(),
                            ),
                    )

                    return ExtractedStream(
                        videoId = videoId,
                        audioUrl = directUrl,
                        headers = directHeaders,
                        loudnessDb = response.playerConfig?.audioConfig?.loudnessDb ?: directFastPathCandidate.loudnessDb,
                        expiresAt = expiresAt,
                        contentLengthBytes = contentLength,
                        itag = directFastPathCandidate.itag,
                        mimeType = directFastPathCandidate.mimeType.substringBefore(";").trim(),
                        codecs = directFastPathCandidate.mimeType.extractCodecs(),
                        bitrate = directFastPathCandidate.bitrate,
                        sampleRate = directFastPathCandidate.audioSampleRate,
                        clientName = result.clientName,
                        profileId = result.profileId,
                        requireBoundedRange = requireBoundedRange,
                        useRangeChunks = usesChunkedMediaRanges(result.clientName),
                        rangeChunkSizeBytes = mediaRangeChunkSize(result.clientName),
                        playbackTracking = playbackTracking,
                        streamDiagnostics = diagnostics.snapshot(),
                        videoUrl = directVideoUrl,
                        videoWidth = directFastPathVideo?.width,
                        videoHeight = directFastPathVideo?.height,
                        videoMimeType = directFastPathVideo?.mimeType?.substringBefore(";")?.trim(),
                        videoCodecs = directFastPathVideo?.mimeType?.extractCodecs(),
                        videoBitrate = directFastPathVideo?.bitrate,
                        videoItag = directFastPathVideo?.itag,
                        videoContentLengthBytes = videoContentLength,
                        availableVideoHeights = availableVideoHeights,
                    ).withResponseMetadata(response).also { diagnostics.ensureSessionCurrent() }
                }
            }

            if (!allowCipherProcessing) {
                logger.d(TAG, "cipher pass deferred", details = mapOf("client" to result.clientName))
                continue
            }

            val cipherStart = Clock.System.now().toEpochMilliseconds()
            val rawAudioFormats = allFormats.filter { it.width == null }
            val preferredRawAudioFormat = selectBestAudioFormat(rawAudioFormats, audioQuality, requireUrl = false)
            val audioFormatsForCipher = preferredRawAudioFormat?.let { listOf(it) } ?: rawAudioFormats
            val rawVideoFormats = if (hints.wantVideo) preferredVideoFormats(streamingData, requireUrl = false) else emptyList()
            val preferredRawVideoFormat =
                selectBestVideoFormat(
                    rawVideoFormats,
                    requireUrl = false,
                    maxHeight = hints.maxVideoHeight ?: 2160,
                )
            val rawVideoFallback =
                if (preferredRawVideoFormat?.hasReadyVideoUrl() == true) {
                    null
                } else {
                    selectBestVideoFormat(
                        rawVideoFormats.filter {
                            it.itag != preferredRawVideoFormat?.itag &&
                                it.height == preferredRawVideoFormat?.height &&
                                it.hasReadyVideoUrl()
                        },
                        maxHeight = hints.maxVideoHeight ?: 2160,
                    )
                }
            val rawVideoCandidates = listOfNotNull(preferredRawVideoFormat, rawVideoFallback)
            val formatsForCipher = audioFormatsForCipher + rawVideoCandidates
            var processedFormats =
                cipherService
                    .processFormats(
                        playerUrl = playerConfig.playerUrl,
                        formats = formatsForCipher,
                    ).also { diagnostics.ensureSessionCurrent() }
            val processedVideoFormat =
                rawVideoCandidates.firstNotNullOfOrNull { candidate ->
                    processedFormats.firstOrNull {
                        // processFormats keeps the solved n and nulls unresolved ones, so any URL left is ready.
                        it.itag == candidate.itag && it.width != null && it.url?.let(::isAllowedMediaUrl) == true
                    }
                }
            logger.d(
                TAG,
                "cipher processing completed",
                details =
                    mapOf(
                        "client" to result.clientName,
                        "requestedCount" to formatsForCipher.size.toString(),
                        "processedCount" to processedFormats.size.toString(),
                        "elapsedMs" to (Clock.System.now().toEpochMilliseconds() - cipherStart).toString(),
                    ),
            )

            var audioFormats = processedFormats.filter(PlayerResponse.StreamingData.Format::isAudio)
            var usableAudioFormats = audioFormats.filter { !it.url.isNullOrBlank() }
            var directUrlAudioFormats = usableAudioFormats.filter { it.itag in directAudioItags }
            var selectionPool =
                directUrlAudioFormats.ifEmpty { usableAudioFormats }
            var audioFormat = selectBestAudioFormat(selectionPool, audioQuality)

            if (audioFormat == null && audioFormatsForCipher.size != rawAudioFormats.size) {
                val fallbackCipherStart = Clock.System.now().toEpochMilliseconds()
                processedFormats =
                    cipherService
                        .processFormats(
                            playerUrl = playerConfig.playerUrl,
                            formats = rawAudioFormats,
                        ).also { diagnostics.ensureSessionCurrent() }
                logger.d(
                    TAG,
                    "cipher fallback completed",
                    details =
                        mapOf(
                            "client" to result.clientName,
                            "processedCount" to processedFormats.size.toString(),
                            "elapsedMs" to (Clock.System.now().toEpochMilliseconds() - fallbackCipherStart).toString(),
                        ),
                )
                audioFormats = processedFormats
                usableAudioFormats = audioFormats.filter { !it.url.isNullOrBlank() }
                directUrlAudioFormats = usableAudioFormats.filter { it.itag in directAudioItags }
                selectionPool =
                    directUrlAudioFormats.ifEmpty { usableAudioFormats }
                audioFormat = selectBestAudioFormat(selectionPool, audioQuality)
            }

            if (audioFormat == null) {
                val totalAudio = audioFormats.size
                val audioWithUrl = usableAudioFormats.size
                val directAudioWithUrl = directUrlAudioFormats.size
                logger.d(
                    TAG,
                    "audio candidate unavailable",
                    details =
                        mapOf(
                            "client" to result.clientName,
                            "totalCount" to totalAudio.toString(),
                            "urlCount" to audioWithUrl.toString(),
                            "directCount" to directAudioWithUrl.toString(),
                        ),
                )
                continue
            }

            val url =
                audioFormat.url
                    ?.withPoToken(result.streamingDataPoToken)
                    ?.let { appendClientPlaybackNonce(it, clientPlaybackNonce) }
            if (url.isNullOrBlank()) {
                logger.d(TAG, "selected audio candidate unavailable", details = mapOf("client" to result.clientName))
                continue
            }
            if (!isAllowedMediaUrl(url)) continue
            val videoUrl =
                processedVideoFormat
                    ?.url
                    ?.withPoToken(result.streamingDataPoToken)
                    ?.let { appendClientPlaybackNonce(it, clientPlaybackNonce) }
            if (hints.wantVideo && (videoUrl.isNullOrBlank() || !isAllowedMediaUrl(videoUrl))) {
                logger.d(TAG, "video candidate unavailable", details = mapOf("client" to result.clientName))
                continue
            }
            val expireSeconds = extractExpire(url)
            val expiresAt = expireSeconds?.let { Instant.fromEpochSeconds(it) }
            val directHeaders =
                buildHeaders(
                    diagnostics,
                    result.clientName,
                    result.userAgent,
                    url,
                    hints.isUploaded == true && !result.bearerAuthenticated,
                )
            val contentLength =
                resolveBoundedContentLength(
                    audioFormat.contentLength,
                    url,
                    directHeaders,
                    requireBoundedRange,
                    diagnostics.requestBudget,
                )
            if (requireBoundedRange && contentLength == null) {
                logger.d(TAG, "bounded media skipped", details = mapOf("client" to result.clientName))
                continue
            }
            val videoContentLength =
                if (hints.wantVideo) {
                    resolveBoundedContentLength(
                        processedVideoFormat?.contentLength,
                        checkNotNull(videoUrl),
                        directHeaders,
                        requireBoundedRange,
                        diagnostics.requestBudget,
                    )
                } else {
                    null
                }
            if (requireBoundedRange && hints.wantVideo && videoContentLength == null) {
                logger.d(TAG, "bounded video skipped", details = mapOf("client" to result.clientName))
                continue
            }
            val totalElapsed = Clock.System.now().toEpochMilliseconds() - totalStartMs
            val selectedDirectUrl = audioFormat.itag in directAudioItags
            logger.i(
                TAG,
                "stream selected",
                details =
                    mapOf(
                        "client" to result.clientName,
                        "profile" to result.profileId,
                        "elapsedMs" to totalElapsed.toString(),
                        "direct" to selectedDirectUrl.toString(),
                        "boundedRange" to requireBoundedRange.toString(),
                    ),
            )

            return ExtractedStream(
                videoId = videoId,
                audioUrl = url,
                headers = directHeaders,
                loudnessDb = response.playerConfig?.audioConfig?.loudnessDb ?: audioFormat.loudnessDb,
                expiresAt = expiresAt,
                contentLengthBytes = contentLength,
                itag = audioFormat.itag,
                mimeType = audioFormat.mimeType.substringBefore(";").trim(),
                codecs = audioFormat.mimeType.extractCodecs(),
                bitrate = audioFormat.bitrate,
                sampleRate = audioFormat.audioSampleRate,
                clientName = result.clientName,
                profileId = result.profileId,
                requireBoundedRange = requireBoundedRange,
                useRangeChunks = usesChunkedMediaRanges(result.clientName),
                rangeChunkSizeBytes = mediaRangeChunkSize(result.clientName),
                playbackTracking = playbackTracking,
                streamDiagnostics = diagnostics.snapshot(),
                videoUrl = videoUrl,
                videoWidth = processedVideoFormat?.width,
                videoHeight = processedVideoFormat?.height,
                videoMimeType = processedVideoFormat?.mimeType?.substringBefore(";")?.trim(),
                videoCodecs = processedVideoFormat?.mimeType?.extractCodecs(),
                videoBitrate = processedVideoFormat?.bitrate,
                videoItag = processedVideoFormat?.itag,
                videoContentLengthBytes = videoContentLength,
                availableVideoHeights = availableVideoHeights,
            ).withResponseMetadata(response).also { diagnostics.ensureSessionCurrent() }
        }

        // Fallback to HLS if no playable direct URLs were found across all clients
        val fallbackResult = results.firstOrNull { it.response.streamingData?.hlsManifestUrl != null }
        if (hints.allowHls && fallbackResult != null) {
            val hlsManifestUrl =
                fallbackResult.response.streamingData
                    ?.hlsManifestUrl
                    ?.withPoToken(fallbackResult.streamingDataPoToken)
                    ?.let { appendClientPlaybackNonce(it, clientPlaybackNonce) }
            if (!hlsManifestUrl.isNullOrBlank() && isAllowedHlsUrl(hlsManifestUrl)) {
                logger.i(
                    TAG,
                    "HLS fallback selected",
                    details =
                        mapOf(
                            "client" to fallbackResult.clientName,
                            "profile" to fallbackResult.profileId,
                        ),
                )
                return ExtractedStream(
                    videoId = videoId,
                    audioUrl = hlsManifestUrl,
                    videoUrl = hlsManifestUrl,
                    headers = buildHeaders(diagnostics, fallbackResult.clientName, fallbackResult.userAgent),
                    loudnessDb = null,
                    expiresAt = hlsExpiry(checkNotNull(fallbackResult.response.streamingData), hlsManifestUrl),
                    contentLengthBytes = null,
                    itag = 96,
                    mimeType = "application/x-mpegURL",
                    codecs = null,
                    bitrate = null,
                    sampleRate = null,
                    clientName = fallbackResult.clientName,
                    profileId = fallbackResult.profileId,
                    requireBoundedRange = false,
                    useRangeChunks = false,
                    rangeChunkSizeBytes = mediaRangeChunkSize(fallbackResult.clientName),
                    playbackTracking =
                        fallbackResult.response.playbackTracking
                            .toPlaybackTrackingData(clientPlaybackNonce)
                            .takeUnless { fallbackResult.bearerAuthenticated },
                    streamDiagnostics = diagnostics.snapshot(),
                ).withResponseMetadata(fallbackResult.response).also { diagnostics.ensureSessionCurrent() }
            }
        }

        logger.d(TAG, "playable clients produced no usable audio")
        val failedProfiles =
            results
                .map { result -> result.profileId }
                .filter(String::isNotBlank)
                .toSet()
        val nextExcludedClients = excludedClients + failedProfiles
        if (nextExcludedClients.size == excludedClients.size) return null
        logger.d(TAG, "retrying after unusable response", details = mapOf("excludedCount" to failedProfiles.size.toString()))
        return extractWithConfig(
            videoId = videoId,
            hints = hints,
            excludedClients = nextExcludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = playerConfig,
            totalStartMs = totalStartMs,
            allowCipherProcessing = allowCipherProcessing,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
            tvBearerCredential = tvBearerCredential,
        )
    }

    private inner class ExtractionDiagnostics(
        maxPlayerRequests: Int,
    ) {
        var session = innerTube.sessionSnapshot()
            private set

        fun ensureSessionCurrent() {
            session = currentExtractionSession(session)
        }

        val requestBudget = PlayerRequestBudget(maxPlayerRequests, extractionTimeBudgetMs.milliseconds)
        val failures = mutableListOf<PlayabilityFailure>()
        val requestFailures = mutableListOf<Throwable>()
        val attempts = mutableListOf<StreamAttemptDiagnostic>()
        var nonGatePasses = 0
        var unavailable = false
        var sawPlayableResponse = false
        var usedAuthenticatedWatchPage = false
        var triedPublicConfigBeforeLogin = false

        fun snapshot() =
            StreamDiagnostics(
                attempts = attempts.takeLast(32),
                usedAuthenticatedWatchPage = usedAuthenticatedWatchPage,
            )
    }

    private fun hlsExpiry(
        streamingData: StreamingData,
        url: String,
    ): Instant? {
        val parsed = Url(url)
        val pathSegments = parsed.encodedPath.split('/')
        val pathExpiry = pathSegments.zipWithNext().firstOrNull { it.first == "expire" }?.second
        val urlDeadlines =
            listOfNotNull(parsed.parameters["expire"], pathExpiry)
                .mapNotNull { value -> value.toLongOrNull()?.takeIf { it > 0 } }
                .mapNotNull { epochSeconds -> runCatching { Instant.fromEpochSeconds(epochSeconds) }.getOrNull() }
        val deadlines = urlDeadlines + listOfNotNull(streamingData.expiresInSeconds?.takeIf { it > 0 }?.let { now() + it.seconds })
        return deadlines.minOrNull()
    }

    private fun extractExpire(url: String): Long? {
        val match = EXPIRE_REGEX.find(url)
        return match?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun String.hasNParameter(): Boolean = N_PARAMETER_REGEX.containsMatchIn(this)

    private suspend fun resolveBoundedContentLength(
        contentLength: Long?,
        url: String,
        headers: Map<String, String>,
        requireBoundedRange: Boolean,
        requestBudget: PlayerRequestBudget,
    ): Long? {
        contentLength?.takeIf { it > 0L }?.let { return it }
        if (!requireBoundedRange) return null
        val timeout = requestBudget.timeLeftOrNull(8.seconds) ?: return null
        return withTimeoutOrNull(timeout) {
            innerTube.mediaContentLength(url, headers)
        }
    }

    private fun buildHeaders(
        diagnostics: ExtractionDiagnostics,
        clientName: String,
        userAgent: String,
        mediaUrl: String? = null,
        includeLoginCookies: Boolean = false,
    ): Map<String, String> {
        diagnostics.ensureSessionCurrent()
        val session = diagnostics.session
        val headers = linkedMapOf<String, String>()
        if (clientName != "ANDROID_VR" && clientName != "VISIONOS" && clientName != "TVHTML5_SIMPLY") {
            headers["User-Agent"] = userAgent
            headers["Accept"] = "*/*"
            headers["Accept-Language"] = session.locale.acceptLanguageHeader()

            when (clientName) {
                "WEB_REMIX" -> {
                    headers["Referer"] = "https://music.youtube.com/"
                    headers["Origin"] = "https://music.youtube.com"
                }

                "MWEB" -> {
                    headers["Referer"] = "https://m.youtube.com/"
                    headers["Origin"] = "https://m.youtube.com"
                }

                "WEB_CREATOR" -> {
                    headers["Referer"] = "https://studio.youtube.com/"
                    headers["Origin"] = "https://studio.youtube.com"
                }

                "WEB",
                "WEB_EMBEDDED_PLAYER",
                -> {
                    headers["Referer"] = "https://www.youtube.com/"
                    headers["Origin"] = "https://www.youtube.com"
                }
            }
        }
        if (includeLoginCookies && mediaUrl?.let(::isAllowedMediaUrl) == true) {
            session.cookie
                ?.takeIf(String::isNotBlank)
                ?.let { headers["Cookie"] = it }
        }
        return headers
    }

    private fun String.withPoToken(poToken: String?): String {
        if (poToken.isNullOrBlank() || contains("&pot=") || contains("?pot=")) return this
        val fragmentStart = indexOf('#').takeIf { it >= 0 } ?: length
        val separator = if (indexOf('?').let { it >= 0 && it < fragmentStart }) "&" else "?"
        return buildString(length + poToken.length + 6) {
            append(this@withPoToken, 0, fragmentStart)
            append(separator)
            append("pot=")
            append(encodeQueryComponent(poToken))
            append(this@withPoToken, fragmentStart, this@withPoToken.length)
        }
    }

    private fun PlayerResponse.PlaybackTracking?.toPlaybackTrackingData(clientPlaybackNonce: String) =
        PlaybackTrackingData(
            clientPlaybackNonce = clientPlaybackNonce,
            playbackUrl = this?.videostatsPlaybackUrl?.baseUrl,
            watchtimeUrl = this?.videostatsWatchtimeUrl?.baseUrl,
            scheduledFlushWalltimeSeconds = this?.videostatsScheduledFlushWalltimeSeconds,
            defaultFlushIntervalSeconds = this?.videostatsDefaultFlushIntervalSeconds,
            resolvedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
        )

    private fun ExtractedStream.withResponseMetadata(response: PlayerResponse): ExtractedStream =
        apply {
            perceptualLoudnessDb = response.playerConfig?.audioConfig?.perceptualLoudnessDb
            mediaMetadata = response.toExtractedMediaMetadata()
        }

    private fun PlayerResponse.toExtractedMediaMetadata(): ExtractedMediaMetadata? =
        videoDetails?.let { details ->
            ExtractedMediaMetadata(
                title = details.title,
                author = details.author,
                channelId = details.channelId,
                durationSeconds = details.lengthSeconds?.toLongOrNull(),
                musicVideoType = details.musicVideoType,
                viewCount = details.viewCount,
                thumbnails = details.thumbnail?.thumbnails.orEmpty(),
                isLive = details.isLiveContent == true,
            )
        }

    private fun preferredVideoFormats(
        streamingData: StreamingData,
        requireUrl: Boolean,
    ): List<Format> {
        val adaptive = streamingData.adaptiveFormats.filter { it.width != null && (!requireUrl || !it.url.isNullOrBlank()) }
        val adaptiveHeights = adaptive.filter { it.hasReadyVideoUrl() }.mapTo(mutableSetOf()) { it.height }
        return adaptive +
            streamingData.formats.orEmpty().filter {
                it.width != null && it.height !in adaptiveHeights && (!requireUrl || !it.url.isNullOrBlank())
            }
    }

    private fun String.extractCodecs(): String? = CODECS_REGEX.find(this)?.groupValues?.getOrNull(1)

    private fun Format.hasReadyVideoUrl(): Boolean = url?.let { !it.hasNParameter() && isAllowedMediaUrl(it) } == true

    private fun isAllowedMediaUrl(value: String): Boolean =
        runCatching { Url(value) }.getOrNull()?.let {
            it.protocol == URLProtocol.HTTPS &&
                it.port == 443 &&
                (
                    it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com") ||
                        it.host == "youtube.com" || it.host.endsWith(".youtube.com")
                ) &&
                it.encodedPath == "/videoplayback" && it.user == null && it.password == null
        } == true

    private fun isAllowedHlsUrl(value: String): Boolean =
        runCatching { Url(value) }.getOrNull()?.let {
            it.protocol == URLProtocol.HTTPS && it.port == 443 && it.user == null && it.password == null &&
                (
                    it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com") ||
                        it.host == "youtube.com" || it.host.endsWith(".youtube.com")
                ) &&
                (it.encodedPath.startsWith("/manifest/") || it.encodedPath.startsWith("/api/manifest/"))
        } == true
}

internal fun requiresBoundedMediaRange(clientName: String): Boolean =
    clientName == "ANDROID_VR" || clientName == "IOS" || clientName == "TVHTML5_SIMPLY"

internal fun usesChunkedMediaRanges(clientName: String): Boolean = clientName == "ANDROID_VR" || clientName == "TVHTML5_SIMPLY"

internal fun mediaRangeChunkSize(clientName: String): Long = if (usesChunkedMediaRanges(clientName)) 512L * 1_024L else 1_024L * 1_024L
