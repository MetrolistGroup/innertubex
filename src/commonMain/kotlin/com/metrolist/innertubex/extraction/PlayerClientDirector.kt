package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.d
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackTransportPreference
import com.metrolist.innertubex.extraction.strategy.PoTokenRequirement
import com.metrolist.innertubex.extraction.strategy.PoTokenRule
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.i
import com.metrolist.innertubex.models.PoTokenBinding
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.w
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal class PlayerClientDirector(
    private val innerTube: InnerTube,
    private val fallbackStrategy: ClientFallbackStrategy,
    private val tokenProvider: TokenProvider,
    private val clientHealthMonitor: ClientHealthMonitor = ClientHealthMonitor.NONE,
    private val logger: InnerTubeLogger = InnerTubeLogger.NONE,
    private val playerRequestTimeoutMs: Long = DEFAULT_PLAYER_REQUEST_TIMEOUT_MS,
    private val visitorDataFetchTimeoutMs: Long = DEFAULT_VISITOR_DATA_FETCH_TIMEOUT_MS,
    private val maxPlayerRequests: Int = DEFAULT_MAX_PLAYER_REQUESTS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        private const val TAG = "PlayerClientDirector"
        private const val PO_TOKEN_FETCH_TIMEOUT_MS = 18_000L
        private const val DEFAULT_PLAYER_REQUEST_TIMEOUT_MS = 8_000L
        private const val DEFAULT_VISITOR_DATA_FETCH_TIMEOUT_MS = 8_000L
        private val DEFAULT_MAX_PLAYER_REQUESTS = PlaybackClientCatalog.automaticManifests.size * 2
        private const val MAX_PLAYER_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_PLAYER_FORMATS = 2048
        private val DYNAMIC_WEB_VERSION_CLIENT_NAMES = setOf("WEB", "WEB_EMBEDDED_PLAYER")
    }

    internal suspend fun fetchPlayerResponses(
        videoId: String,
        playerConfig: PlayerConfig,
        hints: ContentHints,
        excludedClients: Set<String> = emptySet(),
        acceptCipherOnlyResponse: Boolean = false,
        directAudioOnlyClients: Boolean = false,
        wantVideo: Boolean = false,
        requestBudget: PlayerRequestBudget? = null,
        prefetchedPoToken: Deferred<PoTokenResult?>? = null,
    ): PlayerResponseBatch {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val initialSession = innerTube.sessionSnapshot()
        val requestVisitorData =
            initialSession.visitorData?.takeIf { it.isNotBlank() }
                ?: playerConfig.visitorData?.takeIf { it.isNotBlank() }
        val requestSession = initialSession.copy(visitorData = requestVisitorData)
        val healthScope = ClientHealthScope.from(hints, authenticated = !requestSession.sapisid.isNullOrBlank())
        val selection =
            fallbackStrategy.selectClients(
                ClientSelectionRequest(
                    hints = hints,
                    authenticated = !requestSession.sapisid.isNullOrBlank(),
                    availablePoTokenProviders = tokenProvider.capabilities.providers,
                    javaScriptRuntimeAvailable = playerConfig.playerUrl.isNotBlank(),
                    webViewAvailable = tokenProvider.capabilities.usesWebView,
                    fastPathOnly = directAudioOnlyClients,
                    transportPreference =
                        when {
                            hints.isLive == true -> PlaybackTransportPreference.HLS
                            hints.sabrFirst -> PlaybackTransportPreference.SABR
                            hints.wantVideo -> PlaybackTransportPreference.DIRECT
                            else -> PlaybackTransportPreference.AUTO
                        },
                    excludedClients = excludedClients,
                ),
            )
        val attempts =
            selection.rejected
                .mapTo(mutableListOf<StreamAttemptDiagnostic>()) { rejected ->
                    StreamAttemptDiagnostic(
                        clientName = rejected.manifest.client.clientName,
                        profileId = rejected.manifest.id,
                        userAgent = rejected.manifest.client.userAgent,
                        outcome = "selection:${rejected.reasons.joinToString("+")}",
                    )
                }
        val clients =
            selection.candidates.filterNot { selected -> selected.isExcluded(excludedClients) }
        logger.d(
            TAG,
            "player client selection",
            details =
                mapOf(
                    "candidateCount" to clients.size.toString(),
                    "rejectedCount" to selection.rejected.size.toString(),
                    "wantVideo" to hints.wantVideo.toString(),
                ),
        )
        logger.d(
            TAG,
            "player response batch started",
            details =
                mapOf(
                    "candidateCount" to clients.size.toString(),
                    "excludedCount" to excludedClients.size.toString(),
                    "directAudioOnly" to directAudioOnlyClients.toString(),
                    "wantVideo" to wantVideo.toString(),
                ),
        )
        val playableResults = mutableListOf<ClientResult>()
        val failures = mutableListOf<PlayabilityFailure>()
        val requestFailures = mutableListOf<Throwable>()
        val effectiveRequestBudget =
            requestBudget ?: PlayerRequestBudget(if (hints.playbackClientOverrideId != null) 1 else maxPlayerRequests)
        var requestsConsumedInBatch = 0
        var forceTokenizedTvHtml5 = false
        val unavailablePoTokenCookieModes = mutableSetOf<Boolean>()
        for (declaredClient in clients) {
            if (requestsConsumedInBatch >= maxPlayerRequests || effectiveRequestBudget.remaining <= 0) break
            val selectedClient = declaredClient.withPlayerConfigVersion(playerConfig)
            val client = selectedClient.client
            val tokenUsesCookie = selectedClient.manifest?.request?.cookies != false
            val untokenizedProfileFailed =
                selectedClient.canUsePoTokens() &&
                    selectedClient.profileIds(usedPoToken = false).any { it in excludedClients }
            val remainingBeforeAttempt = effectiveRequestBudget.remaining
            val attemptResult =
                tryPlayer(
                    selectedClient = selectedClient,
                    videoId = videoId,
                    playerConfig = playerConfig,
                    hints = hints,
                    allowUntokenizedWebPoClient =
                        selectedClient.allowsUntokenizedPlayback(
                            authenticated = !requestSession.sapisid.isNullOrBlank(),
                        ),
                    forcePoToken =
                        (hints.playbackClientOverrideId != null && selectedClient.canUsePoTokens()) ||
                            untokenizedProfileFailed ||
                            (
                                forceTokenizedTvHtml5 &&
                                    client.clientName == YouTubeClient.TVHTML5.clientName
                            ),
                    requestSession = requestSession,
                    requestBudget = effectiveRequestBudget,
                    poTokenFetchUnavailable = tokenUsesCookie in unavailablePoTokenCookieModes,
                    prefetchedPoToken = prefetchedPoToken,
                )
            if (attemptResult.tokenFetchUnavailable) unavailablePoTokenCookieModes += tokenUsesCookie
            requestsConsumedInBatch += remainingBeforeAttempt - effectiveRequestBudget.remaining
            val attempt = attemptResult.attempt
            selectedClient.manifest?.id?.let { manifestId ->
                when {
                    attemptResult.requestFailure != null -> {
                        clientHealthMonitor.recordFailure(manifestId, ClientFailureKind.PLAYER_REQUEST, healthScope)
                    }

                    attempt == null && !attemptResult.tokenUnavailable -> {
                        clientHealthMonitor.recordFailure(manifestId, ClientFailureKind.PLAYABILITY, healthScope)
                    }
                }
            }
            attempts +=
                StreamAttemptDiagnostic(
                    clientName = client.clientName,
                    profileId = attempt?.let { selectedClient.profileId(it.usedPoToken) },
                    userAgent = client.userAgent,
                    outcome =
                        when {
                            attempt != null -> {
                                "playable_response"
                            }

                            attemptResult.tokenUnavailable -> {
                                "po_token_unavailable"
                            }

                            attemptResult.failure?.status != null -> {
                                "playability:${attemptResult.failure.status}"
                            }

                            attemptResult.requestFailure != null -> {
                                "request:${attemptResult.requestFailure::class.simpleName ?: "unknown"}"
                            }

                            else -> {
                                "no_playable_response"
                            }
                        },
                )
            if (attempt != null) {
                val result =
                    ClientResult(
                        clientName = client.clientName,
                        profileId = selectedClient.profileId(attempt.usedPoToken),
                        userAgent = client.userAgent,
                        response = attempt.response,
                        usedPoToken = attempt.usedPoToken,
                        streamingDataPoToken = attempt.streamingDataPoToken,
                        clientId = client.clientId.toIntOrNull() ?: 0,
                        clientVersion = client.clientVersion,
                        useSabr = client.useSabr,
                    )
                if (result.profileId in excludedClients) {
                    logger.d(
                        TAG,
                        "client response skipped",
                        details = mapOf("client" to client.clientName, "profile" to result.profileId),
                    )
                    continue
                }
                playableResults += result

                if (acceptCipherOnlyResponse && (!wantVideo || hasUsableVideoTransport(attempt.response))) {
                    val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
                    logger.d(TAG, "player response selected", details = mapOf("elapsedMs" to elapsed.toString()))
                    return PlayerResponseBatch(listOf(result), failures, requestFailures, attempts)
                }

                val yieldsDirect =
                    (client.useSabr && hasUsableSabrAudio(attempt.response)) ||
                        (
                            !client.useSabr &&
                                hasPlaybackReadyDirectAudioUrl(attempt.response) &&
                                (!wantVideo || hasPlaybackReadyDirectVideoUrl(attempt.response))
                        )
                if (yieldsDirect) {
                    // For video, prefer a poToken client over a direct non-poToken one: YouTube 403s
                    // direct video URLs without a poToken, attached by the extractor after cipher resolution.
                    val preferDirect = !wantVideo || attempt.usedPoToken || playableResults.none { it.usedPoToken }
                    if (preferDirect) {
                        logger.i(
                            TAG,
                            "playback-ready client response",
                            details =
                                mapOf(
                                    "client" to client.clientName,
                                    "profile" to result.profileId,
                                    "transport" to if (client.useSabr) "SABR" else "Direct",
                                    "tokenPresent" to attempt.usedPoToken.toString(),
                                ),
                        )
                        val selectedResults =
                            if (acceptCipherOnlyResponse) {
                                if (wantVideo) {
                                    playableResults.sortedByDescending(ClientResult::usedPoToken)
                                } else {
                                    playableResults.toList()
                                }
                            } else {
                                listOf(result)
                            }
                        val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
                        logger.d(
                            TAG,
                            "player response batch completed",
                            details =
                                mapOf(
                                    "resultCount" to selectedResults.size.toString(),
                                    "elapsedMs" to elapsed.toString(),
                                ),
                        )
                        return PlayerResponseBatch(selectedResults, failures, requestFailures, attempts)
                    }
                    logger.d(
                        TAG,
                        "client response deferred",
                        details =
                            mapOf(
                                "client" to client.clientName,
                                "tokenPresent" to attempt.usedPoToken.toString(),
                            ),
                    )
                }

                logger.d(
                    TAG,
                    "client response requires processing",
                    details =
                        mapOf(
                            "client" to client.clientName,
                            "cipherOnly" to acceptCipherOnlyResponse.toString(),
                        ),
                )
            } else {
                attemptResult.failure?.let(failures::add)
                attemptResult.requestFailure?.let(requestFailures::add)
                logger.w(
                    TAG,
                    "client response unavailable",
                    details =
                        buildMap {
                            put("client", client.clientName)
                            put("profilePresent", (!selectedClient.manifest?.id.isNullOrBlank()).toString())
                            put("tokenUnavailable", attemptResult.tokenUnavailable.toString())
                            put("requestFailure", (attemptResult.requestFailure != null).toString())
                            put("failurePresent", (attemptResult.failure != null).toString())
                        },
                )
                if (client == YouTubeClient.ANDROID_VR_1_43_32 &&
                    attemptResult.failure?.status == "UNPLAYABLE"
                ) {
                    forceTokenizedTvHtml5 = true
                    logger.d(TAG, "tokenized fallback required", details = mapOf("client" to client.clientName))
                }
            }
        }

        val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
        if (playableResults.isNotEmpty()) {
            // For video, order poToken clients first so the extractor can attach the GVS poToken.
            val orderedResults =
                if (wantVideo) {
                    playableResults.sortedByDescending { it.usedPoToken }
                } else {
                    playableResults
                }
            logger.d(
                TAG,
                "player response batch completed",
                details =
                    mapOf(
                        "resultCount" to orderedResults.size.toString(),
                        "elapsedMs" to elapsed.toString(),
                    ),
            )
            return PlayerResponseBatch(orderedResults, failures, requestFailures, attempts)
        }

        logger.d(TAG, "player response batch completed", details = mapOf("resultCount" to "0", "elapsedMs" to elapsed.toString()))
        return PlayerResponseBatch(emptyList(), failures, requestFailures, attempts)
    }

    private suspend fun tryPlayer(
        selectedClient: SelectedClient,
        videoId: String,
        playerConfig: PlayerConfig,
        hints: ContentHints,
        allowUntokenizedWebPoClient: Boolean,
        forcePoToken: Boolean,
        requestSession: InnerTube.SessionSnapshot,
        requestBudget: PlayerRequestBudget,
        poTokenFetchUnavailable: Boolean,
        prefetchedPoToken: Deferred<PoTokenResult?>?,
    ): ClientAttemptResult =
        try {
            val client = selectedClient.client
            val tokenPlan = selectedClient.tokenPlan()
            if (poTokenFetchUnavailable && tokenPlan.tokenRequired && !allowUntokenizedWebPoClient) {
                return ClientAttemptResult(
                    attempt = null,
                    failure = null,
                    tokenUnavailable = true,
                    tokenFetchUnavailable = true,
                )
            }
            if ((forcePoToken || tokenPlan.playerRequired) && tokenPlan.canMint) {
                logger.d(TAG, "tokenized request selected", details = mapOf("client" to client.clientName))
                return tryTokenizedPlayer(
                    selectedClient = selectedClient,
                    tokenPlan = tokenPlan,
                    videoId = videoId,
                    playerConfig = playerConfig,
                    requestSession = requestSession,
                    fallbackFailure = null,
                    existingPlayableResponse = null,
                    requestBudget = requestBudget,
                    poTokenFetchUnavailable = poTokenFetchUnavailable,
                    prefetchedPoToken = prefetchedPoToken,
                )
            }

            // Fast path: try once without PO token first.
            val initialResponse =
                requestPlayer(
                    client,
                    videoId,
                    playerConfig.signatureTimestamp,
                    poToken = null,
                    requestSession = requestSession,
                    encryptedHostFlags = playerConfig.encryptedHostFlags,
                    requestBudget = requestBudget,
                )
                    ?: return ClientAttemptResult(null, null)
            val initialPlayable = isPlayable(initialResponse, client)
            val initialStatus = initialResponse.playabilityStatus.status
            val initialFailure =
                PlayabilityFailure(
                    status = initialStatus,
                    reason = initialResponse.playabilityStatus.reason,
                )
            if (initialPlayable) {
                if (!tokenPlan.tokenRequired || allowUntokenizedWebPoClient) {
                    if (tokenPlan.canMint && allowUntokenizedWebPoClient) {
                        logger.d(TAG, "untokenized response accepted", details = mapOf("client" to client.clientName))
                    }
                    return ClientAttemptResult(ClientAttempt(initialResponse, usedPoToken = false), null)
                }
                logger.d(TAG, "token required for playback stability", details = mapOf("client" to client.clientName))
            }

            if (!tokenPlan.canMint) {
                return ClientAttemptResult(
                    attempt = null,
                    failure = initialFailure.takeUnless { initialPlayable },
                    tokenUnavailable = initialPlayable,
                )
            }
            // Zemer-style recovery: restricted and uploaded media can return a non-OK
            // response until the same client is retried with a fresh PO token. Keep the
            // historical fast path for ordinary media, but do not stop this client early
            // for the content classes that commonly require authenticated attestation.
            val retryRestrictedWithPoToken =
                !initialPlayable &&
                    (hints.isUploaded == true || hints.isAgeRestricted == true)
            if (!initialPlayable && !retryRestrictedWithPoToken) {
                logger.d(TAG, "token fetch skipped", details = mapOf("client" to client.clientName, "playable" to "false"))
                return ClientAttemptResult(null, initialFailure)
            }
            if (retryRestrictedWithPoToken) {
                logger.d(TAG, "token fetch retried", details = mapOf("client" to client.clientName, "restrictedContent" to "true"))
            }

            tryTokenizedPlayer(
                selectedClient = selectedClient,
                tokenPlan = tokenPlan,
                videoId = videoId,
                playerConfig = playerConfig,
                requestSession = requestSession,
                fallbackFailure = initialFailure.takeUnless { initialPlayable },
                existingPlayableResponse = initialResponse.takeIf { initialPlayable },
                requestBudget = requestBudget,
                poTokenFetchUnavailable = poTokenFetchUnavailable,
                prefetchedPoToken = prefetchedPoToken,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(
                TAG,
                "player request failed",
                details =
                    mapOf(
                        "client" to selectedClient.client.clientName,
                        "profilePresent" to (!selectedClient.manifest?.id.isNullOrBlank()).toString(),
                        "exceptionType" to (e::class.simpleName ?: "unknown"),
                    ),
            )
            ClientAttemptResult(attempt = null, failure = null, requestFailure = e)
        }

    private suspend fun tryTokenizedPlayer(
        selectedClient: SelectedClient,
        tokenPlan: TokenPlan,
        videoId: String,
        playerConfig: PlayerConfig,
        requestSession: InnerTube.SessionSnapshot,
        fallbackFailure: PlayabilityFailure?,
        existingPlayableResponse: PlayerResponse?,
        requestBudget: PlayerRequestBudget,
        poTokenFetchUnavailable: Boolean,
        prefetchedPoToken: Deferred<PoTokenResult?>?,
    ): ClientAttemptResult {
        if (poTokenFetchUnavailable) {
            return ClientAttemptResult(
                attempt = null,
                failure = fallbackFailure,
                tokenUnavailable = true,
                tokenFetchUnavailable = true,
            )
        }
        val client = selectedClient.client
        val tokenStart = Clock.System.now().toEpochMilliseconds()
        val tokenRequestSession =
            if (requestSession.visitorData.isNullOrBlank()) {
                logger.d(TAG, "visitor data requested", details = mapOf("client" to client.clientName))
                val visitorData =
                    withTimeoutOrNull(visitorDataFetchTimeoutMs.milliseconds) {
                        innerTube.fetchFreshVisitorData(requestSession)
                    }
                if (visitorData.isNullOrBlank()) {
                    logger.d(TAG, "visitor data unavailable", details = mapOf("client" to client.clientName))
                    return ClientAttemptResult(null, fallbackFailure, tokenUnavailable = true)
                }
                innerTube.sessionSnapshotWithVisitorData(requestSession, visitorData)
                    ?: return ClientAttemptResult(null, fallbackFailure, tokenUnavailable = true)
            } else {
                requestSession
            }
        val visitorData = checkNotNull(tokenRequestSession.visitorData)
        val token =
            withTimeoutOrNull(PO_TOKEN_FETCH_TIMEOUT_MS.milliseconds) {
                val prefetched =
                    prefetchedPoToken
                        ?.takeIf { selectedClient.manifest?.request?.cookies != false }
                        ?.await()
                        ?.takeIf { it.visitorData == visitorData }
                prefetched
                    ?: tokenProvider.getPoToken(
                        videoId,
                        visitorData,
                        tokenRequestSession.cookie.takeIf { selectedClient.manifest?.request?.cookies != false },
                    )
            }
        val tokenElapsed = Clock.System.now().toEpochMilliseconds() - tokenStart
        logger.d(
            TAG,
            "token fetch completed",
            details =
                mapOf(
                    "client" to client.clientName,
                    "tokenPresent" to (token != null).toString(),
                    "elapsedMs" to tokenElapsed.toString(),
                ),
        )
        val playerRequestPoToken = tokenPlan.playerBinding?.let { binding -> token?.tokenFor(binding) }
        val streamingDataPoToken = tokenPlan.gvsBinding?.let { binding -> token?.tokenFor(binding) }
        val missingRequiredToken =
            (tokenPlan.playerRequired && playerRequestPoToken.isNullOrBlank()) ||
                (tokenPlan.gvsRequired && streamingDataPoToken.isNullOrBlank())
        val noUsableToken = playerRequestPoToken.isNullOrBlank() && streamingDataPoToken.isNullOrBlank()
        if (token == null) {
            return ClientAttemptResult(
                attempt = null,
                failure = fallbackFailure,
                tokenUnavailable = true,
                tokenFetchUnavailable = true,
            )
        }
        if (token.visitorData != visitorData || missingRequiredToken || noUsableToken) {
            logger.w(TAG, "token binding rejected", details = mapOf("client" to client.clientName))
            return ClientAttemptResult(null, fallbackFailure, tokenUnavailable = true)
        }

        if (existingPlayableResponse != null && playerRequestPoToken.isNullOrBlank()) {
            return ClientAttemptResult(
                attempt =
                    ClientAttempt(
                        response = existingPlayableResponse,
                        usedPoToken = true,
                        streamingDataPoToken = streamingDataPoToken,
                    ),
                failure = null,
            )
        }

        val tokenizedResponse =
            requestPlayer(
                client,
                videoId,
                playerConfig.signatureTimestamp,
                poToken = playerRequestPoToken,
                requestSession = tokenRequestSession,
                encryptedHostFlags = playerConfig.encryptedHostFlags,
                requestBudget = requestBudget,
            )
                ?: return ClientAttemptResult(null, fallbackFailure)
        val tokenizedStatus = tokenizedResponse.playabilityStatus.status
        return if (isPlayable(tokenizedResponse, client)) {
            ClientAttemptResult(
                ClientAttempt(
                    response = tokenizedResponse,
                    usedPoToken = !playerRequestPoToken.isNullOrBlank() || !streamingDataPoToken.isNullOrBlank(),
                    streamingDataPoToken = streamingDataPoToken,
                ),
                null,
            )
        } else {
            logger.d(TAG, "tokenized response unavailable", details = mapOf("client" to client.clientName))
            ClientAttemptResult(
                attempt = null,
                failure =
                    PlayabilityFailure(
                        status = tokenizedStatus,
                        reason = tokenizedResponse.playabilityStatus.reason,
                    ),
            )
        }
    }

    private suspend fun requestPlayer(
        client: YouTubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        requestSession: InnerTube.SessionSnapshot,
        encryptedHostFlags: String?,
        requestBudget: PlayerRequestBudget,
    ): PlayerResponse? =
        try {
            requestBudget.consume()
            withTimeout(playerRequestTimeoutMs.milliseconds) {
                requestPlayerWithoutTimeout(
                    client = client,
                    videoId = videoId,
                    signatureTimestamp = signatureTimestamp,
                    poToken = poToken,
                    requestSession = requestSession,
                    encryptedHostFlags = encryptedHostFlags,
                )
            }
        } catch (error: TimeoutCancellationException) {
            throw PlayerRequestTimeoutException(client.clientName, playerRequestTimeoutMs, error)
        }

    private suspend fun requestPlayerWithoutTimeout(
        client: YouTubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        requestSession: InnerTube.SessionSnapshot,
        encryptedHostFlags: String?,
    ): PlayerResponse? {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val httpResponse =
            innerTube.playerWithSessionBound(
                client = client,
                videoId = videoId,
                playlistId = null,
                signatureTimestamp = signatureTimestamp,
                poToken = poToken,
                requestVisitorData = requestSession.visitorData,
                requestSession = requestSession,
                encryptedHostFlags = encryptedHostFlags,
            )
        if (!httpResponse.status.isSuccess()) {
            httpResponse.bodyAsTextLimited(MAX_PLAYER_RESPONSE_BYTES)
            return null
        }
        val payload = httpResponse.bodyAsTextLimited(MAX_PLAYER_RESPONSE_BYTES)
        val response = runCatching { json.decodeFromString<PlayerResponse>(payload) }.getOrNull()
        if (response == null) {
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            if (root == null) {
                logger.w(
                    TAG,
                    "invalid player response",
                    details =
                        mapOf(
                            "client" to client.clientName,
                            "httpStatus" to httpResponse.status.value.toString(),
                            "elapsedMs" to elapsed.toString(),
                        ),
                )
            } else if ("playabilityStatus" !in root) {
                logger.d(
                    TAG,
                    "player response missing status",
                    details =
                        mapOf(
                            "client" to client.clientName,
                            "httpStatus" to httpResponse.status.value.toString(),
                            "elapsedMs" to elapsed.toString(),
                        ),
                )
            } else {
                logger.w(
                    TAG,
                    "player response decode failed",
                    details =
                        mapOf(
                            "client" to client.clientName,
                            "httpStatus" to httpResponse.status.value.toString(),
                            "elapsedMs" to elapsed.toString(),
                        ),
                )
            }
            return null
        }

        val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
        val formatCount =
            (response.streamingData?.formats?.size ?: 0) +
                response.streamingData
                    ?.adaptiveFormats
                    .orEmpty()
                    .size
        logger.d(
            TAG,
            "player response decoded",
            details =
                mapOf(
                    "client" to client.clientName,
                    "streamingPresent" to (response.streamingData != null).toString(),
                    "formatCount" to formatCount.toString(),
                    "elapsedMs" to elapsed.toString(),
                ),
        )
        if (formatCount > MAX_PLAYER_FORMATS) return null
        return response
    }

    private fun isPlayable(
        response: PlayerResponse,
        client: YouTubeClient,
    ): Boolean =
        (response.playabilityStatus.status == "OK" || client.skipPlayerResponseValidation) &&
            if (client.useSabr) {
                hasUsableSabrAudio(response)
            } else {
                hasUsableAudioFormat(response) || !response.streamingData?.hlsManifestUrl.isNullOrBlank()
            }

    private fun hasUsableSabrAudio(response: PlayerResponse): Boolean {
        val streamingData = response.streamingData ?: return false
        return !streamingData.serverAbrStreamingUrl.isNullOrBlank() &&
            !response.playerConfig
                ?.mediaCommonConfig
                ?.mediaUstreamerRequestConfig
                ?.videoPlaybackUstreamerConfig
                .isNullOrBlank() &&
            streamingData.adaptiveFormats.any { it.isAudio && it.itag > 0 }
    }

    private fun hasUsableAudioFormat(response: PlayerResponse): Boolean {
        val streamingData = response.streamingData ?: return false
        val allFormats = (streamingData.formats ?: emptyList()) + streamingData.adaptiveFormats
        return allFormats.any { format ->
            format.isAudio && (
                !format.url.isNullOrBlank() ||
                    !format.signatureCipher.isNullOrBlank() ||
                    !format.cipher.isNullOrBlank()
            )
        }
    }

    private fun hasUsableVideoTransport(response: PlayerResponse): Boolean {
        val streamingData = response.streamingData ?: return false
        if (!streamingData.hlsManifestUrl.isNullOrBlank()) return true
        val allFormats = (streamingData.formats ?: emptyList()) + streamingData.adaptiveFormats
        return allFormats.any { format ->
            format.width != null && (
                !format.url.isNullOrBlank() ||
                    !format.signatureCipher.isNullOrBlank() ||
                    !format.cipher.isNullOrBlank()
            )
        }
    }

    private fun hasPlaybackReadyDirectAudioUrl(response: PlayerResponse): Boolean {
        val streamingData = response.streamingData ?: return false
        val allFormats = (streamingData.formats ?: emptyList()) + streamingData.adaptiveFormats
        return allFormats.any { format ->
            val url = format.url
            format.isAudio &&
                !url.isNullOrBlank() &&
                !url.hasNParameter()
        }
    }

    private fun hasPlaybackReadyDirectVideoUrl(response: PlayerResponse): Boolean {
        val streamingData = response.streamingData ?: return false
        val allFormats = (streamingData.formats ?: emptyList()) + streamingData.adaptiveFormats
        return allFormats.any { format ->
            val url = format.url
            format.width != null &&
                !url.isNullOrBlank() &&
                !url.hasNParameter()
        }
    }

    private fun String.hasNParameter(): Boolean = Regex("[?&]n=[^&]+", RegexOption.IGNORE_CASE).containsMatchIn(this)

    private fun SelectedClient.withPlayerConfigVersion(playerConfig: PlayerConfig): SelectedClient {
        val liveVersion = playerConfig.clientVersion?.takeIf { it.isNotBlank() } ?: return this
        if (client.clientName !in DYNAMIC_WEB_VERSION_CLIENT_NAMES || client.clientVersion == liveVersion) return this
        return copy(client = client.copy(clientVersion = liveVersion))
    }

    private fun SelectedClient.isExcluded(excludedClients: Set<String>): Boolean {
        if (client.clientName in excludedClients) return true
        val noPoExcluded = profileIds(usedPoToken = false).any { it in excludedClients }
        val poExcluded = profileIds(usedPoToken = true).any { it in excludedClients }
        return (noPoExcluded && poExcluded) ||
            (!canUsePoTokens() && noPoExcluded) ||
            (requiresPoTokens() && poExcluded)
    }

    private fun SelectedClient.profileIds(usedPoToken: Boolean): Set<String> =
        manifest?.let { PlaybackClientCatalog.profileIds(it, usedPoToken) }
            ?: setOf(client.legacyProfileId(usedPoToken))

    private fun SelectedClient.profileId(usedPoToken: Boolean): String =
        manifest?.let { "${it.id}__${if (usedPoToken) "po" else "nopo"}" }
            ?: client.legacyProfileId(usedPoToken)

    private fun SelectedClient.canUsePoTokens(): Boolean = tokenPlan().canMint

    private fun SelectedClient.requiresPoTokens(): Boolean = tokenPlan().tokenRequired

    private fun SelectedClient.allowsUntokenizedPlayback(authenticated: Boolean): Boolean =
        ("manual override" in reasons && !canUsePoTokens()) ||
            manifest?.let {
                it.poTokens.player.requirement != PoTokenRequirement.REQUIRED &&
                    it.poTokens.gvs.requirement != PoTokenRequirement.REQUIRED
            } ?: (!client.useWebPoTokens || authenticated)

    private fun SelectedClient.tokenPlan(): TokenPlan {
        val declaredManifest = manifest
        if (declaredManifest == null) {
            return TokenPlan(
                playerBinding = client.poTokenBinding.takeIf { client.useWebPoTokens },
                gvsBinding = PoTokenBinding.VIDEO_ID.takeIf { client.useWebPoTokens },
                playerRequired = client.requirePoToken,
                gvsRequired = client.useWebPoTokens,
            )
        }

        fun compatibleBinding(rule: PoTokenRule): PoTokenBinding? =
            rule.binding?.takeIf {
                rule.requirement != PoTokenRequirement.NONE &&
                    rule.providers.any { provider -> provider in tokenProvider.capabilities.providers }
            }

        return TokenPlan(
            playerBinding = compatibleBinding(declaredManifest.poTokens.player),
            gvsBinding = compatibleBinding(declaredManifest.poTokens.gvs),
            playerRequired = declaredManifest.poTokens.player.requirement == PoTokenRequirement.REQUIRED,
            gvsRequired = declaredManifest.poTokens.gvs.requirement == PoTokenRequirement.REQUIRED,
        )
    }

    private fun PoTokenResult.tokenFor(binding: PoTokenBinding): String =
        when (binding) {
            PoTokenBinding.VIDEO_ID -> streamingDataToken
            PoTokenBinding.VISITOR_DATA -> playerRequestToken
        }

    private fun YouTubeClient.legacyProfileId(usedPoToken: Boolean): String {
        val base =
            buildString {
                append(clientName)
                append('_')
                append(friendlyName ?: clientVersion)
                if (isEmbedded) append("_embedded")
            }
        val safeBase = base.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return "${safeBase}_${if (usedPoToken) "po" else "nopo"}"
    }

    private data class TokenPlan(
        val playerBinding: PoTokenBinding?,
        val gvsBinding: PoTokenBinding?,
        val playerRequired: Boolean,
        val gvsRequired: Boolean,
    ) {
        val canMint: Boolean
            get() = playerBinding != null || gvsBinding != null

        val tokenRequired: Boolean
            get() = playerRequired || gvsRequired
    }

    private data class ClientAttempt(
        val response: PlayerResponse,
        val usedPoToken: Boolean,
        val streamingDataPoToken: String? = null,
    )

    private data class ClientAttemptResult(
        val attempt: ClientAttempt?,
        val failure: PlayabilityFailure?,
        val requestFailure: Throwable? = null,
        val tokenUnavailable: Boolean = false,
        val tokenFetchUnavailable: Boolean = false,
    )

    private class PlayerRequestTimeoutException(
        clientName: String,
        timeoutMs: Long,
        cause: Throwable,
    ) : Exception("Player request for $clientName exceeded ${timeoutMs}ms", cause)
}

@OptIn(ExperimentalAtomicApi::class)
internal class PlayerRequestBudget(
    initial: Int,
) {
    private val remainingCount = AtomicInt(initial.also { require(it > 0) { "Player request budget must be positive" } })

    val remaining: Int
        get() = remainingCount.load()

    fun consume() {
        while (true) {
            val current = remainingCount.load()
            check(current > 0) { "Player request budget exhausted" }
            if (remainingCount.compareAndSet(current, current - 1)) {
                return
            }
        }
    }
}
