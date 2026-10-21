package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.d
import com.metrolist.innertubex.extraction.strategy.CapabilitySupport
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackTransportPreference
import com.metrolist.innertubex.extraction.strategy.PoTokenRequirement
import com.metrolist.innertubex.extraction.strategy.PoTokenRule
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.i
import com.metrolist.innertubex.models.PoTokenBinding
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.sabr.hasSabrBootstrapPrerequisites
import com.metrolist.innertubex.w
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
        private val N_PARAMETER_REGEX = Regex("[?&]n=[^&]+", RegexOption.IGNORE_CASE)
        private val UNSAFE_PROFILE_CHARACTERS = Regex("[^A-Za-z0-9_.-]")
        private val DYNAMIC_WEB_VERSION_CLIENT_NAMES = setOf("WEB", "WEB_EMBEDDED_PLAYER")
        private val TRANSPORT_PROBE_CLIENT_NAMES =
            setOf("IOS_MUSIC", "ANDROID_KIDS", "ANDROID_PRODUCER", "MEDIA_CONNECT_FRONTEND")
        private val KNOWN_DRM_KEYS =
            setOf(
                "contentprotection",
                "contentprotectionids",
                "drmfamilies",
                "drmfamily",
                "drmparameters",
                "drmparams",
                "drmtracktype",
                "fairplay",
                "licenseinfo",
                "licenseinfos",
                "licenseurl",
                "playready",
                "widevine",
            )
    }

    internal val usesAutomaticCatalog: Boolean = fallbackStrategy is ContentAwareFallbackStrategy

    internal fun includesVisitorBackedLegacy(excludedClients: Set<String>): Boolean =
        (fallbackStrategy as? ContentAwareFallbackStrategy)?.includesVisitorBackedLegacy(excludedClients) == true

    internal suspend fun fetchPlayerResponses(
        videoId: String,
        playerConfig: PlayerConfig,
        hints: ContentHints,
        excludedClients: Set<String> = emptySet(),
        acceptCipherOnlyResponse: Boolean = false,
        directAudioOnlyClients: Boolean = false,
        wantVideo: Boolean = false,
        premiumHighQuality: Boolean = false,
        requestBudget: PlayerRequestBudget? = null,
        prefetchedPoToken: PoTokenPrefetch? = null,
        tvBearerCredential: TvBearerCredential? = null,
    ): PlayerResponseBatch {
        val budget = requestBudget ?: PlayerRequestBudget(if (hints.playbackClientOverrideId != null) 1 else maxPlayerRequests)
        return budget.withTokenScope {
            fetchPlayerResponsesInScope(
                videoId,
                playerConfig,
                hints,
                excludedClients,
                acceptCipherOnlyResponse,
                directAudioOnlyClients,
                wantVideo,
                premiumHighQuality,
                budget,
                prefetchedPoToken,
                tvBearerCredential,
            )
        }
    }

    private suspend fun fetchPlayerResponsesInScope(
        videoId: String,
        playerConfig: PlayerConfig,
        hints: ContentHints,
        excludedClients: Set<String>,
        acceptCipherOnlyResponse: Boolean,
        directAudioOnlyClients: Boolean,
        wantVideo: Boolean,
        premiumHighQuality: Boolean,
        requestBudget: PlayerRequestBudget,
        prefetchedPoToken: PoTokenPrefetch?,
        tvBearerCredential: TvBearerCredential?,
    ): PlayerResponseBatch {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val initialSession = innerTube.sessionSnapshot()
        // Embed-page encryptedHostFlags are only honoured with the visitor that fetched that page.
        val embedVisitorData = playerConfig.visitorData?.takeIf { it.isNotBlank() && !playerConfig.encryptedHostFlags.isNullOrBlank() }
        val requestVisitorData =
            embedVisitorData
                ?: initialSession.visitorData?.takeIf { it.isNotBlank() }
                ?: playerConfig.visitorData?.takeIf { it.isNotBlank() }
        var requestSession = initialSession.copy(visitorData = requestVisitorData)
        val authenticated = !requestSession.sapisid.isNullOrBlank()
        val effectiveRequestBudget = requestBudget
        if (hints.isAgeRestricted == true) effectiveRequestBudget.ageRestricted = true
        val routingHints = effectiveRequestBudget.withKnownAge(hints)
        val selectionRequest =
            ClientSelectionRequest(
                hints = routingHints,
                authenticated = authenticated,
                premium = hints.premium,
                availablePoTokenProviders = tokenProvider.capabilities.providers,
                javaScriptRuntimeAvailable = playerConfig.playerUrl.isNotBlank(),
                webViewAvailable = tokenProvider.capabilities.usesWebView,
                fastPathOnly = directAudioOnlyClients,
                transportPreference =
                    when {
                        hints.isLive == true -> PlaybackTransportPreference.HLS

                        // Direct video needs a poToken and cipher pass; SABR video needs neither.
                        hints.sabrFirst || hints.wantVideo -> PlaybackTransportPreference.SABR

                        else -> PlaybackTransportPreference.AUTO
                    },
                excludedClients = excludedClients,
            )
        val selection =
            if (fallbackStrategy is ContentAwareFallbackStrategy) {
                fallbackStrategy.selectClients(selectionRequest, premiumHighQuality, requestVisitorData != null)
            } else {
                fallbackStrategy.selectClients(selectionRequest)
            }
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
            selection.candidates.filterNot { selected ->
                selected.isExcluded(
                    excludedClients,
                    premiumEntitlement = selected.hasUsablePremiumEntitlement(authenticated, hints.premium),
                )
            }
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
        if (tvBearerCredential != null) {
            return fetchTvBearerResponse(
                videoId = videoId,
                playerConfig = playerConfig,
                excludedClients = excludedClients,
                wantVideo = wantVideo,
                requestBudget = effectiveRequestBudget,
                initialSession = requestSession,
                credential = tvBearerCredential,
                attempts = attempts,
                failures = failures,
                requestFailures = requestFailures,
            )
        }
        var requestsConsumedInBatch = 0
        var forceTokenizedTvHtml5 = false
        var onlyAccountGateFailures = true
        var onlyUnavailableFailures =
            !directAudioOnlyClients && playerConfig.playerUrl.isNotBlank() &&
                (!authenticated || clients.any { it.client.loginSupported })
        var onlyBootstrapIndependentFailures = true
        for (declaredClient in clients) {
            if (requestsConsumedInBatch >= maxPlayerRequests || effectiveRequestBudget.remaining <= 0) break
            if (hints.playbackClientOverrideId == null && effectiveRequestBudget.ageRestricted &&
                declaredClient.manifest?.content?.ageRestricted == CapabilitySupport.UNSUPPORTED
            ) {
                continue
            }
            // A tokenized attempt may have published fresh visitor data. Carry it forward; any other change still cancels.
            innerTube
                .sessionSnapshot()
                .takeIf { it.generation != requestSession.generation }
                ?.visitorData
                ?.takeIf(String::isNotBlank)
                ?.let { innerTube.sessionSnapshotWithVisitorData(requestSession, it) }
                ?.let { requestSession = it }
            val selectedClient = declaredClient.withPlayerConfigVersion(playerConfig)
            val client = selectedClient.client
            // A visitor-less automatic legacy probe says nothing about its visitor-backed playback health.
            if (usesAutomaticCatalog && selectedClient.manifest?.id == "VISIONOS_0_1" &&
                requestVisitorData.isNullOrBlank() && hints.playbackClientOverrideId == null
            ) {
                continue
            }
            val premiumEntitlement = selectedClient.hasUsablePremiumEntitlement(authenticated, hints.premium)
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
                            authenticated = authenticated,
                            premiumEntitlement = premiumEntitlement,
                        ),
                    premiumEntitlement = premiumEntitlement,
                    forcePoToken =
                        (
                            hints.playbackClientOverrideId != null &&
                                selectedClient.canUsePoTokens() &&
                                !premiumEntitlement
                        ) ||
                            untokenizedProfileFailed ||
                            (
                                forceTokenizedTvHtml5 &&
                                    client.clientName == YouTubeClient.TVHTML5.clientName
                            ),
                    requestSession = requestSession,
                    requestBudget = effectiveRequestBudget,
                    prefetchedPoToken = prefetchedPoToken,
                )
            requestsConsumedInBatch += remainingBeforeAttempt - effectiveRequestBudget.remaining
            val attempt = attemptResult.attempt
            onlyAccountGateFailures = onlyAccountGateFailures && attemptResult.failure?.status.isAccountGateStatus() &&
                attemptResult.requestFailure == null
            onlyUnavailableFailures = onlyUnavailableFailures && attemptResult.failure?.status == "ERROR" &&
                attemptResult.requestFailure == null &&
                (!client.isEmbedded || !playerConfig.encryptedHostFlags.isNullOrBlank())
            onlyBootstrapIndependentFailures = onlyBootstrapIndependentFailures &&
                (attemptResult.failure?.status.isAccountGateStatus() || attemptResult.failure?.status == "ERROR") &&
                attemptResult.requestFailure == null
            val healthScope = ClientHealthScope.from(effectiveRequestBudget.withKnownAge(hints), authenticated)
            selectedClient.manifest
                ?.id
                ?.takeUnless {
                    client == YouTubeClient.VISIONOS_0_1 && requestVisitorData.isNullOrBlank()
                }?.let { manifestId ->
                    // Later extraction passes can replay the same outcome; report each one once.
                    when {
                        attemptResult.requestFailure != null -> {
                            if (effectiveRequestBudget.firstHealthReport(manifestId, "request")) {
                                clientHealthMonitor.recordFailure(manifestId, ClientFailureKind.PLAYER_REQUEST, healthScope)
                            }
                        }

                        // Success is left to the host: a playable response can still fail at the CDN,
                        // and recording it here would clear that failure's cooldown.
                        attempt == null && !attemptResult.tokenUnavailable && !attemptResult.budgetExhausted &&
                            !attemptResult.failure?.status.isAccountGateStatus() &&
                            attemptResult.failure?.status != "VIDEO_UNAVAILABLE" &&
                            attemptResult.failure?.status != "LIVE_STREAM_OFFLINE" -> {
                            if (effectiveRequestBudget.firstHealthReport(manifestId, "playability")) {
                                clientHealthMonitor.recordFailure(manifestId, ClientFailureKind.PLAYABILITY, healthScope)
                            }
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
                            attempt != null && client.clientName in TRANSPORT_PROBE_CLIENT_NAMES -> {
                                responseTransportOutcome(attempt.response)
                            }

                            attempt != null -> {
                                "playable_response"
                            }

                            attemptResult.observedResponse != null && client.clientName in TRANSPORT_PROBE_CLIENT_NAMES -> {
                                responseTransportOutcome(attemptResult.observedResponse)
                            }

                            attemptResult.budgetExhausted -> {
                                "request_budget_exhausted"
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
        return PlayerResponseBatch(
            emptyList(),
            failures,
            requestFailures,
            attempts,
            onlyAccountGateFailures = onlyAccountGateFailures && failures.isNotEmpty(),
            onlyUnavailableFailures = onlyUnavailableFailures && failures.isNotEmpty(),
            onlyBootstrapIndependentFailures = onlyBootstrapIndependentFailures && failures.isNotEmpty(),
        )
    }

    private suspend fun fetchTvBearerResponse(
        videoId: String,
        playerConfig: PlayerConfig,
        excludedClients: Set<String>,
        wantVideo: Boolean,
        requestBudget: PlayerRequestBudget,
        initialSession: InnerTube.SessionSnapshot,
        credential: TvBearerCredential,
        attempts: MutableList<StreamAttemptDiagnostic>,
        failures: MutableList<PlayabilityFailure>,
        requestFailures: MutableList<Throwable>,
    ): PlayerResponseBatch {
        val manifest =
            PlaybackClientCatalog
                .findManifest(credential.profileId)
                ?.takeIf { it.client.clientName == "TVHTML5" && it.client.loginSupported }
                ?: return PlayerResponseBatch(emptyList(), failures, requestFailures, attempts)
        val bearerProfileId = "${manifest.id}__bearer"
        if (
            credential.profileId in excludedClients ||
            manifest.client.clientName in excludedClients ||
            bearerProfileId in excludedClients ||
            !credential.isUsableFor(initialSession.generation, TV_BEARER_MINIMUM_LIFETIME) ||
            requestBudget.remaining <= 0
        ) {
            return PlayerResponseBatch(emptyList(), failures, requestFailures, attempts)
        }
        val bearerSession =
            initialSession.copy(
                visitorData = credential.visitorData?.takeIf(String::isNotBlank),
                dataSyncId = null,
                authUser = "0",
                cookie = null,
                sapisid = null,
                useLoginForBrowse = false,
            )
        val attemptResult =
            try {
                tryTvBearerPlayer(
                    client = manifest.client,
                    videoId = videoId,
                    playerConfig = playerConfig,
                    requestSession = bearerSession,
                    requestBudget = requestBudget,
                    credential = credential,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: PlayerRequestBudgetExhaustedException) {
                ClientAttemptResult(null, null, budgetExhausted = true)
            } catch (error: Exception) {
                ClientAttemptResult(attempt = null, failure = null, requestFailure = error)
            }
        val attempt = attemptResult.attempt
        attempts +=
            StreamAttemptDiagnostic(
                clientName = manifest.client.clientName,
                profileId = bearerProfileId,
                userAgent = manifest.client.userAgent,
                outcome =
                    when {
                        attempt != null -> "playable_response"
                        attemptResult.budgetExhausted -> "request_budget_exhausted"
                        attemptResult.requestFailure != null -> "request:${attemptResult.requestFailure::class.simpleName ?: "unknown"}"
                        else -> "no_playable_response"
                    },
            )
        if (attempt == null) {
            attemptResult.failure?.let(failures::add)
            attemptResult.requestFailure?.let(requestFailures::add)
            return PlayerResponseBatch(emptyList(), failures, requestFailures, attempts)
        }
        if (wantVideo && !hasUsableVideoTransport(attempt.response)) {
            return PlayerResponseBatch(emptyList(), failures, requestFailures, attempts)
        }
        return PlayerResponseBatch(
            listOf(
                ClientResult(
                    clientName = manifest.client.clientName,
                    profileId = bearerProfileId,
                    userAgent = manifest.client.userAgent,
                    response = attempt.response,
                    clientId = manifest.client.clientId.toIntOrNull() ?: 0,
                    clientVersion = manifest.client.clientVersion,
                    bearerAuthenticated = true,
                ),
            ),
            failures,
            requestFailures,
            attempts,
        )
    }

    private suspend fun tryTvBearerPlayer(
        client: YouTubeClient,
        videoId: String,
        playerConfig: PlayerConfig,
        requestSession: InnerTube.SessionSnapshot,
        requestBudget: PlayerRequestBudget,
        credential: TvBearerCredential,
    ): ClientAttemptResult {
        val response =
            requestPlayer(
                client = client,
                videoId = videoId,
                signatureTimestamp = playerConfig.signatureTimestamp,
                poToken = null,
                requestSession = requestSession,
                encryptedHostFlags = null,
                requestBudget = requestBudget,
                bearerToken = credential.bearerValue(),
            ) ?: return ClientAttemptResult(null, null)
        return if (isPlayable(response, client)) {
            ClientAttemptResult(ClientAttempt(response, usedPoToken = false), null)
        } else {
            ClientAttemptResult(
                attempt = null,
                failure = response.playabilityStatus.toFailure(),
            )
        }
    }

    private suspend fun tryPlayer(
        selectedClient: SelectedClient,
        videoId: String,
        playerConfig: PlayerConfig,
        hints: ContentHints,
        allowUntokenizedWebPoClient: Boolean,
        premiumEntitlement: Boolean,
        forcePoToken: Boolean,
        requestSession: InnerTube.SessionSnapshot,
        requestBudget: PlayerRequestBudget,
        prefetchedPoToken: PoTokenPrefetch?,
    ): ClientAttemptResult =
        try {
            val client = selectedClient.client
            val tokenPlan = selectedClient.tokenPlan(premiumEntitlement)
            val knownTokenKey =
                requestSession.visitorData?.takeIf(String::isNotBlank)?.let {
                    PoTokenRequestKey(videoId, it, requestSession.cookie.takeIf { selectedClient.manifest?.request?.cookies != false })
                }
            if (tokenPlan.tokenRequired && !allowUntokenizedWebPoClient &&
                knownTokenKey != null && requestBudget.isTokenUnavailable(knownTokenKey)
            ) {
                return ClientAttemptResult(null, null, tokenUnavailable = true)
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
                    prefetchedPoToken = prefetchedPoToken,
                )
            }

            // A GVS-only token never enters the player request, so mint it while that request is in flight.
            val overlappedMint =
                knownTokenKey?.takeIf {
                    tokenPlan.canMint && tokenPlan.gvsRequired && !tokenPlan.playerRequired && !allowUntokenizedWebPoClient &&
                        prefetchedPoToken?.key != it && requestBudget.mintedToken(it) == null
                }
            if (overlappedMint != null) {
                val prefetch =
                    requestBudget.prefetchToken(overlappedMint) {
                        tokenProvider.getPoToken(videoId, overlappedMint.visitorData, overlappedMint.cookie)
                    }
                return tryUntokenizedFirst(
                    selectedClient,
                    tokenPlan,
                    videoId,
                    playerConfig,
                    hints,
                    allowUntokenizedWebPoClient,
                    requestSession,
                    requestBudget,
                    prefetch,
                )
            }
            tryUntokenizedFirst(
                selectedClient,
                tokenPlan,
                videoId,
                playerConfig,
                hints,
                allowUntokenizedWebPoClient,
                requestSession,
                requestBudget,
                prefetchedPoToken,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: PlayerRequestBudgetExhaustedException) {
            ClientAttemptResult(null, null, budgetExhausted = true)
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

    private suspend fun tryUntokenizedFirst(
        selectedClient: SelectedClient,
        tokenPlan: TokenPlan,
        videoId: String,
        playerConfig: PlayerConfig,
        hints: ContentHints,
        allowUntokenizedWebPoClient: Boolean,
        requestSession: InnerTube.SessionSnapshot,
        requestBudget: PlayerRequestBudget,
        prefetchedPoToken: PoTokenPrefetch?,
    ): ClientAttemptResult {
        val client = selectedClient.client
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
        val initialFailure = initialResponse.playabilityStatus.toFailure()
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
                observedResponse = initialResponse,
            )
        }
        // Zemer-style recovery: restricted and uploaded media can return a non-OK
        // response until the same client is retried with a fresh PO token. Keep the
        // historical fast path for ordinary media, but do not stop this client early
        // for the content classes that commonly require authenticated attestation.
        // A GVS-only token with an unchanged visitor would resend the identical request and replay its cached failure.
        val tokenCanChangeRequest = tokenPlan.playerBinding != null || requestSession.visitorData.isNullOrBlank()
        val retryRestrictedWithPoToken =
            !initialPlayable &&
                tokenCanChangeRequest &&
                (hints.isUploaded == true || hints.isAgeRestricted == true)
        if (!initialPlayable && !retryRestrictedWithPoToken) {
            logger.d(TAG, "token fetch skipped", details = mapOf("client" to client.clientName, "playable" to "false"))
            return ClientAttemptResult(null, initialFailure, observedResponse = initialResponse)
        }
        if (retryRestrictedWithPoToken) {
            logger.d(TAG, "token fetch retried", details = mapOf("client" to client.clientName, "restrictedContent" to "true"))
        }

        return tryTokenizedPlayer(
            selectedClient = selectedClient,
            tokenPlan = tokenPlan,
            videoId = videoId,
            playerConfig = playerConfig,
            requestSession = requestSession,
            fallbackFailure = initialFailure.takeUnless { initialPlayable },
            existingPlayableResponse = initialResponse.takeIf { initialPlayable },
            requestBudget = requestBudget,
            prefetchedPoToken = prefetchedPoToken,
        )
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
        prefetchedPoToken: PoTokenPrefetch?,
    ): ClientAttemptResult {
        val client = selectedClient.client
        val tokenStart = Clock.System.now().toEpochMilliseconds()
        val tokenRequestSession =
            if (requestSession.visitorData.isNullOrBlank()) {
                if (requestBudget.visitorUnavailable) return ClientAttemptResult(null, fallbackFailure, tokenUnavailable = true)
                logger.d(TAG, "visitor data requested", details = mapOf("client" to client.clientName))
                val visitorData =
                    withTimeoutOrNull(requestBudget.timeLeft(visitorDataFetchTimeoutMs.milliseconds)) {
                        innerTube.fetchFreshVisitorData(requestSession)
                    }
                if (visitorData.isNullOrBlank()) {
                    requestBudget.visitorUnavailable = true
                    logger.d(TAG, "visitor data unavailable", details = mapOf("client" to client.clientName))
                    return ClientAttemptResult(null, fallbackFailure, tokenUnavailable = true)
                }
                innerTube.sessionSnapshotWithVisitorData(requestSession, visitorData)
                    ?: return ClientAttemptResult(null, fallbackFailure, tokenUnavailable = true)
            } else {
                requestSession
            }
        val visitorData = checkNotNull(tokenRequestSession.visitorData)
        val tokenKey =
            PoTokenRequestKey(
                videoId,
                visitorData,
                tokenRequestSession.cookie.takeIf { selectedClient.manifest?.request?.cookies != false },
            )
        if (requestBudget.isTokenUnavailable(tokenKey)) {
            return ClientAttemptResult(null, fallbackFailure, tokenUnavailable = true)
        }
        val token =
            requestBudget.mintedToken(tokenKey) ?: try {
                withTimeoutOrNull(requestBudget.timeLeft(PO_TOKEN_FETCH_TIMEOUT_MS.milliseconds)) {
                    if (prefetchedPoToken?.key == tokenKey) {
                        prefetchedPoToken.result.await()
                    } else {
                        requestBudget
                            .prefetchToken(tokenKey) {
                                tokenProvider.getPoToken(videoId, visitorData, tokenKey.cookie)
                            }.result
                            .await()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }?.also { requestBudget.rememberToken(tokenKey, it) }
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
            requestBudget.markTokenUnavailable(tokenKey)
            return ClientAttemptResult(
                attempt = null,
                failure = fallbackFailure,
                tokenUnavailable = true,
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
                failure = tokenizedResponse.playabilityStatus.toFailure(),
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
        bearerToken: String? = null,
    ): PlayerResponse? {
        // Bearer requests are never remembered so the credential cannot outlive its single use.
        val key =
            if (bearerToken == null) {
                PlayerRequestKey(
                    client = client.wireIdentity(),
                    videoId = videoId,
                    signatureTimestamp = signatureTimestamp.takeIf { client.useSignatureTimestamp },
                    poToken = poToken,
                    requestSession = requestSession,
                    encryptedHostFlags = encryptedHostFlags.takeIf { client.isEmbedded },
                )
            } else {
                null
            }
        if (key != null && requestBudget.hasResponse(key)) {
            logger.d(TAG, "player response reused", details = mapOf("client" to client.clientName))
            return requestBudget.response(key)
        }
        requestBudget.consume()
        val timeout = requestBudget.timeLeft(playerRequestTimeoutMs.milliseconds)
        val response =
            try {
                withTimeout(timeout) {
                    requestPlayerWithoutTimeout(
                        client = client,
                        videoId = videoId,
                        signatureTimestamp = signatureTimestamp,
                        poToken = poToken,
                        requestSession = requestSession,
                        encryptedHostFlags = encryptedHostFlags,
                        bearerToken = bearerToken,
                    )
                }
            } catch (error: TimeoutCancellationException) {
                // Running out of the extraction's own time is not this client's fault; don't record it as a failure.
                if (timeout < playerRequestTimeoutMs.milliseconds) throw PlayerRequestBudgetExhaustedException()
                throw PlayerRequestTimeoutException(client.clientName, playerRequestTimeoutMs, error)
            }
        if (isConfirmedAgeGate(response?.playabilityStatus?.status, response?.playabilityStatus?.desktopLegacyAgeGateReason)) {
            requestBudget.ageRestricted = true
        }
        key?.let { requestBudget.remember(it, response) }
        return response
    }

    private suspend fun requestPlayerWithoutTimeout(
        client: YouTubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        requestSession: InnerTube.SessionSnapshot,
        encryptedHostFlags: String?,
        bearerToken: String? = null,
    ): PlayerResponse? {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val payload =
            if (bearerToken != null) {
                innerTube.playerWithTvBearerSessionBound(
                    client = client,
                    videoId = videoId,
                    signatureTimestamp = signatureTimestamp,
                    requestSession = requestSession,
                    bearerToken = bearerToken,
                ) ?: return null
            } else {
                // Non-2xx responses throw InnerTubeHttpException before their bodies are read.
                innerTube.playerTextWithSessionBound(
                    client = client,
                    videoId = videoId,
                    signatureTimestamp = signatureTimestamp,
                    poToken = poToken,
                    requestSession = requestSession,
                    encryptedHostFlags = encryptedHostFlags,
                    maxBytes = MAX_PLAYER_RESPONSE_BYTES,
                )
            }
        return withContext(Dispatchers.Default) {
            parsePlayerResponse(payload, videoId, client, startTime, bearerToken != null)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parsePlayerResponse(
        payload: String,
        videoId: String,
        client: YouTubeClient,
        startTime: Long,
        bearerAuthenticated: Boolean = false,
    ): PlayerResponse? {
        val parsedRoot =
            if (bearerAuthenticated) {
                runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            } else {
                null
            }
        if (bearerAuthenticated && parsedRoot?.let(::containsKnownDrmMarker) == true) {
            logger.w(TAG, "bearer player response rejected", details = mapOf("client" to client.clientName, "reason" to "drm"))
            return null
        }
        val decoded =
            runCatching {
                if (parsedRoot != null) {
                    json.decodeFromJsonElement(PlayerResponse.serializer(), parsedRoot)
                } else {
                    json.decodeFromString<PlayerResponse>(payload)
                }
            }
        val response = decoded.getOrNull()
        if (response == null) {
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            val details =
                mapOf(
                    "client" to client.clientName,
                    "httpStatus" to "200",
                    "elapsedMs" to elapsed.toString(),
                )
            // Classified from the decode error instead of re-parsing the payload into a second, possibly huge, tree.
            val error = decoded.exceptionOrNull()
            when {
                error is MissingFieldException && "playabilityStatus" in error.missingFields -> {
                    logger.d(TAG, "player response missing status", details = details)
                }

                else -> {
                    logger.w(TAG, "player response decode failed", details = details)
                }
            }
            return null
        }
        if (!response.matchesRequestedVideo(videoId)) {
            logger.w(TAG, "player response rejected", details = mapOf("client" to client.clientName, "reason" to "video_identity"))
            return null
        }

        val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
        val formatCount =
            (response.streamingData?.formats?.size ?: 0) +
                response.streamingData
                    ?.adaptiveFormats
                    .orEmpty()
                    .size
        val transportDiagnostics =
            if (client.clientName in TRANSPORT_PROBE_CLIENT_NAMES) {
                val adaptiveFormats = response.streamingData?.adaptiveFormats.orEmpty()
                val progressiveFormats = response.streamingData?.formats.orEmpty()
                mapOf(
                    "adaptiveFormatCount" to adaptiveFormats.size.toString(),
                    "adaptiveDirectCount" to adaptiveFormats.count { it.url?.isNotBlank() == true }.toString(),
                    "adaptiveCipherCount" to
                        adaptiveFormats.count { !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank() }.toString(),
                    "progressiveFormatCount" to progressiveFormats.size.toString(),
                    "progressiveDirectCount" to progressiveFormats.count { it.url?.isNotBlank() == true }.toString(),
                    "progressiveCipherCount" to
                        progressiveFormats.count { !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank() }.toString(),
                    "hlsPresent" to (!response.streamingData?.hlsManifestUrl.isNullOrBlank()).toString(),
                    "dashPresent" to (!response.streamingData?.dashManifestUrl.isNullOrBlank()).toString(),
                    "sabrPresent" to (!response.streamingData?.serverAbrStreamingUrl.isNullOrBlank()).toString(),
                    "sabrConfigPresent" to
                        (
                            !response.playerConfig
                                ?.mediaCommonConfig
                                ?.mediaUstreamerRequestConfig
                                ?.videoPlaybackUstreamerConfig
                                .isNullOrBlank()
                        ).toString(),
                )
            } else {
                emptyMap()
            }
        logger.d(
            TAG,
            "player response decoded",
            details =
                mapOf(
                    "client" to client.clientName,
                    "streamingPresent" to (response.streamingData != null).toString(),
                    "formatCount" to formatCount.toString(),
                    "elapsedMs" to elapsed.toString(),
                ) + transportDiagnostics,
        )
        if (formatCount > MAX_PLAYER_FORMATS) return null
        return response
    }

    private fun containsKnownDrmMarker(root: JsonObject): Boolean {
        val streamingData = root["streamingData"] as? JsonObject ?: return false
        if (KNOWN_DRM_KEYS.any { key -> knownDrmValue(streamingData, key)?.let(::hasDrmValue) == true }) return true
        return listOf("formats", "adaptiveFormats").any { key ->
            (streamingData[key] as? JsonArray).orEmpty().any { format ->
                (format as? JsonObject)?.let { item ->
                    KNOWN_DRM_KEYS.any { marker -> knownDrmValue(item, marker)?.let(::hasDrmValue) == true }
                } == true
            }
        }
    }

    private fun knownDrmValue(
        objectValue: JsonObject,
        key: String,
    ): JsonElement? = objectValue.entries.firstOrNull { it.key.lowercase() == key }?.value

    private fun hasDrmValue(element: JsonElement): Boolean =
        when (element) {
            is JsonObject -> {
                element.isNotEmpty()
            }

            is JsonArray -> {
                element.any { value ->
                    when (value) {
                        is JsonObject, is JsonArray -> true
                        is JsonPrimitive -> value.contentOrNull?.let { it.isNotBlank() && it != "false" && it != "0" } == true
                    }
                }
            }

            is JsonPrimitive -> {
                element.contentOrNull?.let { it.isNotBlank() && it != "false" && it != "0" } == true
            }
        }

    private fun PlayerResponse.matchesRequestedVideo(videoId: String): Boolean =
        videoDetails?.videoId?.takeIf(String::isNotBlank)?.let { it == videoId } ?: true

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

    private fun hasUsableSabrAudio(response: PlayerResponse): Boolean = response.hasSabrBootstrapPrerequisites()

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

    private fun String.hasNParameter(): Boolean = N_PARAMETER_REGEX.containsMatchIn(this)

    private fun SelectedClient.withPlayerConfigVersion(playerConfig: PlayerConfig): SelectedClient {
        val liveVersion = playerConfig.clientVersion?.takeIf { it.isNotBlank() } ?: return this
        if (client.clientName !in DYNAMIC_WEB_VERSION_CLIENT_NAMES || client.clientVersion == liveVersion) return this
        return copy(client = client.copy(clientVersion = liveVersion))
    }

    private fun SelectedClient.isExcluded(
        excludedClients: Set<String>,
        premiumEntitlement: Boolean,
    ): Boolean {
        if (client.clientName in excludedClients) return true
        val noPoExcluded = profileIds(usedPoToken = false).any { it in excludedClients }
        val poExcluded = profileIds(usedPoToken = true).any { it in excludedClients }
        return (noPoExcluded && poExcluded) ||
            (!canUsePoTokens() && noPoExcluded) ||
            (requiresPoTokens(premiumEntitlement) && poExcluded)
    }

    private fun SelectedClient.profileIds(usedPoToken: Boolean): Set<String> =
        manifest?.let { PlaybackClientCatalog.profileIds(it, usedPoToken) }
            ?: setOf(client.legacyProfileId(usedPoToken))

    private fun SelectedClient.profileId(usedPoToken: Boolean): String =
        manifest?.let { "${it.id}__${if (usedPoToken) "po" else "nopo"}" }
            ?: client.legacyProfileId(usedPoToken)

    private fun SelectedClient.canUsePoTokens(): Boolean = tokenPlan().canMint

    private fun SelectedClient.requiresPoTokens(premiumEntitlement: Boolean): Boolean = tokenPlan(premiumEntitlement).tokenRequired

    private fun SelectedClient.hasUsablePremiumEntitlement(
        authenticated: Boolean,
        premium: Boolean,
    ): Boolean = premium && authenticated && client.loginSupported

    private fun SelectedClient.allowsUntokenizedPlayback(
        authenticated: Boolean,
        premiumEntitlement: Boolean,
    ): Boolean =
        ("manual override" in reasons && !canUsePoTokens()) ||
            manifest?.let {
                it.poTokens.player.isSatisfiedByPremium(premiumEntitlement) &&
                    it.poTokens.gvs.isSatisfiedByPremium(premiumEntitlement)
            } ?: (!client.useWebPoTokens || authenticated)

    private fun SelectedClient.tokenPlan(premiumEntitlement: Boolean = false): TokenPlan {
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
            playerRequired = !declaredManifest.poTokens.player.isSatisfiedByPremium(premiumEntitlement),
            gvsRequired = !declaredManifest.poTokens.gvs.isSatisfiedByPremium(premiumEntitlement),
        )
    }

    private fun PoTokenRule.isSatisfiedByPremium(premiumEntitlement: Boolean): Boolean =
        requirement != PoTokenRequirement.REQUIRED || (premiumEntitlement && premiumMayBypass)

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
        val safeBase = base.replace(UNSAFE_PROFILE_CHARACTERS, "_")
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
        val budgetExhausted: Boolean = false,
        val tokenUnavailable: Boolean = false,
        val observedResponse: PlayerResponse? = null,
    )

    private fun responseTransportOutcome(response: PlayerResponse): String {
        val adaptiveFormats = response.streamingData?.adaptiveFormats.orEmpty()
        val progressiveFormats = response.streamingData?.formats.orEmpty()
        return buildString {
            append("response_shape:")
            append("adaptive_direct=")
            append(if (adaptiveFormats.any { it.url?.isNotBlank() == true }) "present" else "absent")
            append(",adaptive_cipher=")
            append(
                if (adaptiveFormats.any { !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank() }) {
                    "present"
                } else {
                    "absent"
                },
            )
            append(",progressive_direct=")
            append(if (progressiveFormats.any { it.url?.isNotBlank() == true }) "present" else "absent")
            append(",progressive_cipher=")
            append(
                if (progressiveFormats.any { !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank() }) {
                    "present"
                } else {
                    "absent"
                },
            )
            append(",hls=")
            append(if (!response.streamingData?.hlsManifestUrl.isNullOrBlank()) "present" else "absent")
            append(",dash=")
            append(if (!response.streamingData?.dashManifestUrl.isNullOrBlank()) "present" else "absent")
            append(",sabr=")
            append(if (!response.streamingData?.serverAbrStreamingUrl.isNullOrBlank()) "present" else "absent")
        }
    }

    private class PlayerRequestTimeoutException(
        clientName: String,
        timeoutMs: Long,
        cause: Throwable,
    ) : Exception("Player request for $clientName exceeded ${timeoutMs}ms", cause)
}

/**
 * Drops fields that only steer local transport/token policy, so profiles such as WEB_REMIX and WEB_REMIX_SABR
 * share one identical untokenized request. Each caller still applies its own transport and token checks.
 */
internal fun YouTubeClient.wireIdentity(): YouTubeClient =
    copy(
        friendlyName = null,
        requirePoToken = false,
        poTokenBinding = PoTokenBinding.VIDEO_ID,
        useWebPoTokens = false,
        useSabr = false,
        skipPlayerResponseValidation = false,
    )

/** Every input that can change a non-bearer player response. */
internal data class PlayerRequestKey(
    val client: YouTubeClient,
    val videoId: String,
    val signatureTimestamp: Int?,
    val poToken: String?,
    val requestSession: InnerTube.SessionSnapshot,
    val encryptedHostFlags: String?,
) {
    override fun toString(): String = "PlayerRequestKey(client=${client.clientName})"
}

/**
 * Per-extraction player-request budget. It also remembers each response, so the config-free, cached-config,
 * fresh-config and cookie passes of one extraction never resend an identical request.
 * Request failures are not remembered because they are usually transient.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PlayerRequestBudget(
    initial: Int,
    totalTime: Duration = DEFAULT_EXTRACTION_TIME_BUDGET,
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    // Per-stage timeouts (player, visitor, token, media probe) otherwise stack across clients and config passes.
    private val deadline = timeSource.markNow() + totalTime

    /** [limit] clamped to the time left in this extraction; throws once that time is spent. */
    fun timeLeft(limit: Duration): Duration = timeLeftOrNull(limit) ?: throw PlayerRequestBudgetExhaustedException()

    fun timeLeftOrNull(limit: Duration): Duration? {
        val left = -deadline.elapsedNow()
        return if (left.isPositive()) minOf(limit, left) else null
    }

    suspend fun <T> withinTime(block: suspend () -> T): T {
        val result =
            withTimeoutOrNull(timeLeft(Duration.INFINITE)) { Pair(block(), Unit) }
                ?: throw PlayerRequestBudgetExhaustedException()
        timeLeft(Duration.INFINITE)
        return result.first
    }

    private var tokenScope: CoroutineScope? = null
    private val inFlightTokens = mutableMapOf<PoTokenRequestKey, PoTokenPrefetch>()

    suspend fun <T> withTokenScope(block: suspend () -> T): T {
        if (tokenScope != null) return block()
        return coroutineScope {
            tokenScope = this
            try {
                block()
            } finally {
                inFlightTokens.values.forEach { it.result.cancel() }
                inFlightTokens.clear()
                tokenScope = null
            }
        }
    }

    fun prefetchToken(
        key: PoTokenRequestKey,
        mint: suspend () -> PoTokenResult?,
    ): PoTokenPrefetch =
        inFlightTokens.getOrPut(key) {
            PoTokenPrefetch(
                key,
                checkNotNull(tokenScope).async {
                    try {
                        withTimeoutOrNull(timeLeft(18.seconds)) { mint() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                },
            )
        }

    var ageRestricted: Boolean = false

    fun withKnownAge(hints: ContentHints): ContentHints = if (ageRestricted) hints.copy(isAgeRestricted = true) else hints

    private val responses = mutableMapOf<PlayerRequestKey, PlayerResponse?>()
    private val healthReports = mutableSetOf<Pair<String, String>>()
    private val unavailableTokens = mutableSetOf<PoTokenRequestKey>()
    private val mintedTokens = mutableMapOf<PoTokenRequestKey, PoTokenResult>()

    /** Set once visitor bootstrap fails, so later tokenized clients in this extraction do not repeat it. */
    var visitorUnavailable: Boolean = false

    fun isTokenUnavailable(key: PoTokenRequestKey): Boolean = key in unavailableTokens

    /** Extraction-local: a minted token is reused by later clients with the same video/visitor/cookie binding. */
    fun mintedToken(key: PoTokenRequestKey): PoTokenResult? = mintedTokens[key]

    fun rememberToken(
        key: PoTokenRequestKey,
        token: PoTokenResult,
    ) {
        mintedTokens[key] = token
    }

    fun markTokenUnavailable(key: PoTokenRequestKey) {
        unavailableTokens += key
    }

    fun hasResponse(key: PlayerRequestKey): Boolean = key in responses

    fun response(key: PlayerRequestKey): PlayerResponse? = responses[key]

    fun remember(
        key: PlayerRequestKey,
        response: PlayerResponse?,
    ) {
        responses[key] = response
    }

    fun terminalFailureReason(): StreamResolveException.Reason? {
        val reasons = responses.values.map { it?.playabilityStatus?.toFailure()?.terminalReason() }
        return reasons.firstOrNull()?.takeIf { reason -> reasons.all { it == reason } }
    }

    fun firstHealthReport(
        manifestId: String,
        outcome: String,
    ): Boolean = healthReports.add(manifestId to outcome)

    private val remainingCount = AtomicInt(initial.also { require(it > 0) { "Player request budget must be positive" } })

    /** Requests still allowed; zero once the extraction's time is spent, so later passes stop early. */
    val remaining: Int
        get() = if ((-deadline.elapsedNow()).isPositive()) remainingCount.load() else 0

    fun consume() {
        timeLeft(Duration.INFINITE)
        while (true) {
            val current = remainingCount.load()
            if (current <= 0) throw PlayerRequestBudgetExhaustedException()
            if (remainingCount.compareAndSet(current, current - 1)) {
                return
            }
        }
    }
}

internal class PlayerRequestBudgetExhaustedException : Exception()

internal data class PoTokenRequestKey(
    val videoId: String,
    val visitorData: String,
    val cookie: String?,
) {
    override fun toString(): String = "PoTokenRequestKey(redacted)"
}

internal class PoTokenPrefetch(
    val key: PoTokenRequestKey,
    val result: Deferred<PoTokenResult?>,
)

private val DEFAULT_EXTRACTION_TIME_BUDGET = 30.seconds
