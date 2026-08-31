package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.d
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.i
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.requireAllowedSabrUrl
import com.metrolist.innertubex.sabr.sabrRequestOrigin
import com.metrolist.innertubex.sabr.toSabrBootstrap
import com.metrolist.innertubex.w
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
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
) : StreamExtractor {
    constructor(
        configParser: YtConfigParser,
        cipherService: YouTubeCipherService,
        innerTube: InnerTube,
        fallbackStrategy: ClientFallbackStrategy = ContentAwareFallbackStrategy(),
        tokenProvider: TokenProvider? = null,
        clientHealthMonitor: ClientHealthMonitor = ClientHealthMonitor.NONE,
        logger: InnerTubeLogger = InnerTubeLogger.NONE,
    ) : this(
        configParser = configParser,
        clientDirector =
            PlayerClientDirector(
                innerTube = innerTube,
                fallbackStrategy = fallbackStrategy,
                tokenProvider = tokenProvider ?: UnavailableTokenProvider,
                clientHealthMonitor = clientHealthMonitor,
                logger = logger,
            ),
        cipherService = DefaultExtractionCipherService(cipherService),
        innerTube = innerTube,
        tokenProvider = tokenProvider ?: UnavailableTokenProvider,
        logger = logger,
    )

    private companion object {
        private const val TAG = "InnerTubeExtractor"
        private const val DEFAULT_BOUNDED_RANGE_CHUNK_BYTES = 1_048_576L
        private const val PLAYER_CONFIG_CACHE_TTL_MS = 30 * 60 * 1000L
        private const val PREWARM_VIDEO_ID = "dQw4w9WgXcQ"
        private const val WEB_EMBEDDED_PLAYER_ID = "WEB_EMBEDDED_PLAYER"
        private const val WEB_KIDS_ID = "WEB_KIDS"
        private val PO_TOKEN_PREFETCH_TIMEOUT = 18.seconds
        private val MAX_PLAYER_REQUESTS_PER_EXTRACTION = PlaybackClientCatalog.automaticManifests.size * 2 + 2
    }

    private data class CachedPlayerConfig(
        val config: PlayerConfig,
        val cachedAtMs: Long,
        val sessionIdentity: InnerTube.SessionSnapshot,
        val usedLoginCookies: Boolean,
    )

    private val playerConfigCache = mutableMapOf<Boolean, CachedPlayerConfig>()
    private val playerConfigFetchMutex = Mutex()

    override suspend fun prewarm() {
        val startMs = Clock.System.now().toEpochMilliseconds()
        try {
            val initialSession = innerTube.sessionSnapshot()
            val fetchedVisitorData =
                if (tokenProvider.capabilities.providers.isNotEmpty()) {
                    initialSession.visitorData ?: innerTube.fetchFreshVisitorData(initialSession)
                } else {
                    null
                }

            suspend fun fetchConfig(useLoginCookies: Boolean) =
                playerConfigFetchMutex.withLock {
                    getCachedPlayerConfigLocked(
                        useLoginCookies = useLoginCookies,
                        nowMs = Clock.System.now().toEpochMilliseconds(),
                    )?.config
                        ?: run {
                            val expectedSession = innerTube.sessionSnapshot()
                            val config = configParser.fetchConfig(PREWARM_VIDEO_ID, useLoginCookies)
                            if (innerTube.sessionSnapshot() != expectedSession) {
                                throw CancellationException("InnerTube session changed")
                            }
                            cachePlayerConfig(
                                useLoginCookies = useLoginCookies,
                                config = config,
                                sessionIdentity = expectedSession,
                            ).config
                        }
                }
            val configs =
                coroutineScope {
                    val defaultConfigFetch = async { fetchConfig(useLoginCookies = false) }
                    cipherService.initialize()
                    buildList {
                        add(defaultConfigFetch.await())
                        if (innerTube.hasSapCookieAuth()) {
                            try {
                                add(fetchConfig(useLoginCookies = true))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.w(
                                    TAG,
                                    "authenticated player config prewarm failed",
                                    details = mapOf("exceptionType" to (e::class.simpleName ?: "unknown")),
                                )
                            }
                        }
                    }
                }
            coroutineScope {
                val session = innerTube.sessionSnapshot()
                val tokenWarmup =
                    (session.visitorData ?: configs.firstNotNullOfOrNull(PlayerConfig::visitorData) ?: fetchedVisitorData)
                        ?.takeIf { it.isNotBlank() && tokenProvider.capabilities.providers.isNotEmpty() }
                        ?.let { visitorData ->
                            async {
                                withTimeoutOrNull(PO_TOKEN_PREFETCH_TIMEOUT) {
                                    try {
                                        tokenProvider.getPoToken(PREWARM_VIDEO_ID, visitorData, session.cookie)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }
                        }
                // ponytail: prewarm the authenticated player when available.
                // Warm both only if normal cipher fallbacks become common.
                configs
                    .asReversed()
                    .firstOrNull { it.playerUrl.isNotBlank() }
                    ?.let { cipherService.preloadPlayerCode(it.playerUrl) }
                tokenWarmup?.await()
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
        val totalStart = Clock.System.now().toEpochMilliseconds()
        val diagnostics =
            ExtractionDiagnostics(
                maxPlayerRequests = if (hints.playbackClientOverrideId != null) 1 else MAX_PLAYER_REQUESTS_PER_EXTRACTION,
            )
        return try {
            coroutineScope {
                val session = innerTube.sessionSnapshot()
                val prefetchedPoToken =
                    if (
                        hints.isExplicit == true &&
                        hints.playbackClientOverrideId == null &&
                        !session.visitorData.isNullOrBlank() &&
                        tokenProvider.capabilities.providers.isNotEmpty()
                    ) {
                        async {
                            withTimeoutOrNull(PO_TOKEN_PREFETCH_TIMEOUT) {
                                try {
                                    tokenProvider.getPoToken(videoId, session.visitorData, session.cookie)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    } else {
                        null
                    }
                extractWithDiagnostics(
                    videoId,
                    hints,
                    excludedClients,
                    audioQuality,
                    clientPlaybackNonce,
                    totalStart,
                    diagnostics,
                    prefetchedPoToken,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
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
        prefetchedPoToken: Deferred<PoTokenResult?>? = null,
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
            throwExtractionFailure(hints, diagnostics)
        }

        val cookieFirst = hints.isExplicit == true && innerTube.hasSapCookieAuth()
        val stream =
            extractWithCachedConfig(
                videoId = videoId,
                hints = hints,
                excludedClients = excludedClients,
                clientPlaybackNonce = clientPlaybackNonce,
                useLoginCookies = cookieFirst,
                totalStartMs = totalStart,
                audioQuality = audioQuality,
                diagnostics = diagnostics,
                prefetchedPoToken = prefetchedPoToken,
            )
        if (stream != null) return stream

        if (!cookieFirst && innerTube.hasSapCookieAuth()) {
            logger.w(TAG, "authenticated watch page retry", details = mapOf("authenticated" to "true"))
            val authenticatedStream =
                extractWithCachedConfig(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    useLoginCookies = true,
                    totalStartMs = totalStart,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                    prefetchedPoToken = prefetchedPoToken,
                )
            if (authenticatedStream != null) return authenticatedStream
        }

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

        val classifiedFailure = classifyPlayabilityFailures(diagnostics.failures)
        if (shouldTryUnknownKidsFallback(hints, classifiedFailure)) {
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

        throwExtractionFailure(hints, diagnostics)
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
        return extractWithConfig(
            videoId = videoId,
            hints =
                hints.copy(
                    isKidsContent = true,
                    playbackClientOverrideId = WEB_KIDS_ID,
                ),
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
        return extractWithConfig(
            videoId = videoId,
            hints =
                hints.copy(
                    isAgeRestricted = true,
                    playbackClientOverrideId =
                        hints.playbackClientOverrideId?.takeIf { id ->
                            PlaybackClientCatalog.findManifest(id)?.request?.embedded == true
                        } ?: WEB_EMBEDDED_PLAYER_ID,
                ),
            excludedClients = excludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = config,
            totalStartMs = totalStartMs,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
        )
    }

    private fun throwExtractionFailure(
        hints: ContentHints,
        diagnostics: ExtractionDiagnostics,
    ): Nothing {
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
            reason =
                if (hints.isExplicit == true) {
                    StreamResolveException.Reason.EXPLICIT_UNSUPPORTED
                } else {
                    StreamResolveException.Reason.NO_PLAYABLE_STREAM
                },
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
        prefetchedPoToken: Deferred<PoTokenResult?>? = null,
    ): ExtractedStream? {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val cachedConfig = getCachedPlayerConfig(useLoginCookies, nowMs)
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
            val cachedStream =
                extractWithConfig(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    playerConfig = cachedConfig.config,
                    totalStartMs = totalStartMs,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                    prefetchedPoToken = prefetchedPoToken,
                )
            if (cachedStream != null) return cachedStream
            logger.w(TAG, "watch page cache unusable", details = mapOf("authenticated" to useLoginCookies.toString()))
        }

        val configStart = Clock.System.now().toEpochMilliseconds()
        var fetchedFreshConfig = false
        val freshCachedConfig =
            playerConfigFetchMutex.withLock {
                getCachedPlayerConfigLocked(useLoginCookies, Clock.System.now().toEpochMilliseconds())
                    ?: run {
                        val expectedSession = innerTube.sessionSnapshot()
                        val config = configParser.fetchConfig(videoId, useLoginCookies)
                        if (innerTube.sessionSnapshot() != expectedSession) {
                            throw CancellationException("InnerTube session changed")
                        }
                        fetchedFreshConfig = true
                        cachePlayerConfig(useLoginCookies, config, expectedSession)
                    }
            }
        diagnostics.usedAuthenticatedWatchPage =
            diagnostics.usedAuthenticatedWatchPage || freshCachedConfig.usedLoginCookies
        logger.d(
            TAG,
            "watch page config ready",
            details =
                mapOf(
                    "fetched" to fetchedFreshConfig.toString(),
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
            prefetchedPoToken = prefetchedPoToken,
        )
    }

    private suspend fun getCachedPlayerConfig(
        useLoginCookies: Boolean,
        nowMs: Long,
    ): CachedPlayerConfig? =
        playerConfigFetchMutex.withLock {
            getCachedPlayerConfigLocked(useLoginCookies, nowMs)
        }

    private fun getCachedPlayerConfigLocked(
        useLoginCookies: Boolean,
        nowMs: Long,
    ): CachedPlayerConfig? {
        val cached = playerConfigCache[useLoginCookies] ?: return null
        val currentSession = innerTube.sessionSnapshot()
        if (cached.sessionIdentity != currentSession) {
            playerConfigCache.clear()
            return null
        }
        if (nowMs - cached.cachedAtMs <= PLAYER_CONFIG_CACHE_TTL_MS) return cached

        playerConfigCache.remove(useLoginCookies)
        return null
    }

    private fun cachePlayerConfig(
        useLoginCookies: Boolean,
        config: PlayerConfig,
        sessionIdentity: InnerTube.SessionSnapshot = innerTube.sessionSnapshot(),
    ): CachedPlayerConfig =
        CachedPlayerConfig(
            config = config,
            cachedAtMs = Clock.System.now().toEpochMilliseconds(),
            sessionIdentity = sessionIdentity,
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
        prefetchedPoToken: Deferred<PoTokenResult?>? = null,
    ): ExtractedStream? {
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
                requestBudget = diagnostics.requestBudget,
                prefetchedPoToken = prefetchedPoToken,
            )
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
            if (requireBoundedRange && !hints.allowBoundedRange) {
                logger.d(TAG, "bounded-range client skipped by request", details = mapOf("client" to result.clientName))
                continue
            }
            val streamingData = response.streamingData
            if (streamingData == null) {
                logger.w(TAG, "response missing streaming data", details = mapOf("client" to result.clientName))
                continue
            }
            val playbackTracking =
                response.playbackTracking.toPlaybackTrackingData(clientPlaybackNonce)

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
                        formats = allFormats.filter(PlayerResponse.StreamingData.Format::isAudio),
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
                            formats = allFormats.filterNot(PlayerResponse.StreamingData.Format::isAudio),
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
                            ).firstOrNull()
                            ?.url
                    } else {
                        rawSabrUrl
                    }
                if (rawSabrUrl?.hasNParameter() == true && processedSabrUrl == null) {
                    logger.w(TAG, "SABR endpoint rejected", details = mapOf("client" to result.clientName))
                    continue
                }
                val trackedSabrUrl =
                    processedSabrUrl?.let { candidate ->
                        runCatching {
                            requireAllowedSabrUrl(appendClientPlaybackNonce(candidate, clientPlaybackNonce))
                        }.getOrNull()
                    }
                if (processedSabrUrl != null && trackedSabrUrl == null) continue
                val bootstrapResult =
                    runCatching {
                        response.toSabrBootstrap(
                            clientId = result.clientId,
                            clientVersion = result.clientVersion,
                            audioFormat = audioFormat,
                            poToken = result.streamingDataPoToken,
                            requestUserAgent = result.userAgent,
                            requestOrigin = sabrRequestOrigin(result.clientName),
                            serverAbrStreamingUrlOverride = trackedSabrUrl,
                            videoFormat = videoFormat,
                        )
                    }
                val bootstrap = bootstrapResult.getOrNull()
                if (bootstrap == null) {
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
                ).withResponseMetadata(response)
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
                (isLive || allFormats.isEmpty() || needsVideoButNoVideoFormats || result.clientName == "TVHTML5_SIMPLY")
            ) {
                logger.i(TAG, "HLS stream selected", details = mapOf("client" to result.clientName, "profile" to result.profileId))
                return ExtractedStream(
                    videoId = videoId,
                    audioUrl = hlsManifestUrl,
                    videoUrl = hlsManifestUrl,
                    headers = buildHeaders(result.clientName, result.userAgent, hlsManifestUrl, hints.isUploaded == true),
                    loudnessDb = response.playerConfig?.audioConfig?.loudnessDb,
                    expiresAt = null,
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
                ).withResponseMetadata(response)
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
            val wantVideo = hints.wantVideo
            val directVideoFormats =
                if (wantVideo) allFormats.filter { it.width != null && !it.url.isNullOrBlank() } else emptyList()
            val directFastPathVideo = selectBestVideoFormat(directVideoFormats, maxHeight = hints.maxVideoHeight ?: 2160)
            if (directFastPathCandidate != null && (!wantVideo || directFastPathVideo != null)) {
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
                    val directHeaders = buildHeaders(result.clientName, result.userAgent, directUrl, hints.isUploaded == true)
                    val contentLength =
                        resolveBoundedContentLength(
                            directFastPathCandidate.contentLength,
                            directUrl,
                            directHeaders,
                            requireBoundedRange,
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
                    ).withResponseMetadata(response)
                }
            }

            if (!allowCipherProcessing) {
                logger.d(TAG, "cipher pass deferred", details = mapOf("client" to result.clientName))
                continue
            }

            val cipherStart = Clock.System.now().toEpochMilliseconds()
            val rawAudioFormats = allFormats.filter { it.width == null }
            val preferredRawAudioFormat = selectBestAudioFormat(rawAudioFormats, audioQuality, requireUrl = false)
            val formatsForCipher = preferredRawAudioFormat?.let { listOf(it) } ?: rawAudioFormats
            var processedFormats =
                cipherService.processFormats(
                    playerUrl = playerConfig.playerUrl,
                    formats = formatsForCipher,
                )
            val rawVideoFormats = if (hints.wantVideo) allFormats.filter { it.width != null } else emptyList()
            val preferredRawVideoFormat =
                selectBestVideoFormat(
                    rawVideoFormats,
                    requireUrl = false,
                    maxHeight =
                        hints.maxVideoHeight ?: 2160,
                )
            val processedVideoFormat =
                if (preferredRawVideoFormat != null) {
                    cipherService
                        .processFormats(
                            playerUrl = playerConfig.playerUrl,
                            formats = listOf(preferredRawVideoFormat),
                        ).firstOrNull { !it.url.isNullOrBlank() }
                } else {
                    null
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

            var audioFormats = processedFormats
            var usableAudioFormats = audioFormats.filter { !it.url.isNullOrBlank() }
            var directUrlAudioFormats = usableAudioFormats.filter { it.itag in directAudioItags }
            var selectionPool =
                directUrlAudioFormats.ifEmpty { usableAudioFormats }
            var audioFormat = selectBestAudioFormat(selectionPool, audioQuality)

            if (audioFormat == null && formatsForCipher.size != rawAudioFormats.size) {
                val fallbackCipherStart = Clock.System.now().toEpochMilliseconds()
                processedFormats =
                    cipherService.processFormats(
                        playerUrl = playerConfig.playerUrl,
                        formats = rawAudioFormats,
                    )
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
            val directHeaders = buildHeaders(result.clientName, result.userAgent, url, hints.isUploaded == true)
            val contentLength =
                resolveBoundedContentLength(
                    audioFormat.contentLength,
                    url,
                    directHeaders,
                    requireBoundedRange,
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
            ).withResponseMetadata(response)
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
                    headers = buildHeaders(fallbackResult.clientName, fallbackResult.userAgent),
                    loudnessDb = null,
                    expiresAt = null,
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
                        fallbackResult.response.playbackTracking.toPlaybackTrackingData(clientPlaybackNonce),
                    streamDiagnostics = diagnostics.snapshot(),
                ).withResponseMetadata(fallbackResult.response)
            }
        }

        logger.d(TAG, "playable clients produced no usable audio")
        val failedProfiles =
            results
                .flatMap { result -> listOf(result.profileId, result.clientName) }
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
        )
    }

    private class ExtractionDiagnostics(
        maxPlayerRequests: Int,
    ) {
        val requestBudget = PlayerRequestBudget(maxPlayerRequests)
        val failures = mutableListOf<PlayabilityFailure>()
        val requestFailures = mutableListOf<Throwable>()
        val attempts = mutableListOf<StreamAttemptDiagnostic>()
        var sawPlayableResponse = false
        var usedAuthenticatedWatchPage = false

        fun snapshot() =
            StreamDiagnostics(
                attempts = attempts.takeLast(32),
                usedAuthenticatedWatchPage = usedAuthenticatedWatchPage,
            )
    }

    private fun extractExpire(url: String): Long? {
        val match = Regex("[?&]expire=([0-9]+)").find(url)
        return match?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun String.hasNParameter(): Boolean = Regex("(?:[?&]|%26)n(?:=|%3[dD])", RegexOption.IGNORE_CASE).containsMatchIn(this)

    private suspend fun resolveBoundedContentLength(
        contentLength: Long?,
        url: String,
        headers: Map<String, String>,
        requireBoundedRange: Boolean,
    ): Long? {
        contentLength?.takeIf { it > 0L }?.let { return it }
        if (!requireBoundedRange) return null
        return withTimeoutOrNull(8.seconds) {
            innerTube.mediaContentLength(url, headers)
        }
    }

    private fun buildHeaders(
        clientName: String,
        userAgent: String,
        mediaUrl: String? = null,
        includeLoginCookies: Boolean = false,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        if (clientName != "ANDROID_VR" && clientName != "VISIONOS" && clientName != "TVHTML5_SIMPLY") {
            headers["User-Agent"] = userAgent
            headers["Accept"] = "*/*"
            headers["Accept-Language"] = innerTube.locale.acceptLanguageHeader()

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
            innerTube
                .sessionSnapshot()
                .cookie
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
            append(poToken.encodeQueryComponent())
            append(this@withPoToken, fragmentStart, this@withPoToken.length)
        }
    }

    private fun String.encodeQueryComponent(): String =
        buildString(length) {
            this@encodeQueryComponent.encodeToByteArray().forEach { byte ->
                val value = byte.toInt() and 0xff
                if (
                    value in '0'.code..'9'.code ||
                    value in 'A'.code..'Z'.code ||
                    value in 'a'.code..'z'.code ||
                    value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code
                ) {
                    append(value.toChar())
                } else {
                    append('%')
                    append("0123456789ABCDEF"[value ushr 4])
                    append("0123456789ABCDEF"[value and 15])
                }
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

    private fun String.extractCodecs(): String? = Regex("codecs=\"([^\"]+)\"").find(this)?.groupValues?.getOrNull(1)

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
