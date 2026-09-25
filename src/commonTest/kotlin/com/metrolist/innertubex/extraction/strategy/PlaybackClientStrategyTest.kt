package com.metrolist.innertubex.extraction.strategy

import com.metrolist.innertubex.extraction.ContentHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackClientStrategyTest {
    private val allProviders = PoTokenProviderKind.entries.toSet()

    @Test
    fun catalogProfilesHaveConsistentManifestInvariants() {
        assertEquals(35, PlaybackClientCatalog.benchmarkOptions.size)
        assertEquals(
            PlaybackClientCatalog.manifests.size,
            PlaybackClientCatalog.manifests
                .map { it.id }
                .distinct()
                .size,
        )
        assertTrue(
            PlaybackClientCatalog.manifests.all { it.client.clientName.isNotBlank() },
        )
    }

    @Test
    fun requestedProbeClientsStayExplicitAndConservative() {
        val ids = setOf("IOS_MUSIC", "ANDROID_KIDS", "ANDROID_PRODUCER", "MEDIA_CONNECT_FRONTEND")
        val probes = ids.map { checkNotNull(PlaybackClientCatalog.findBenchmark(it)?.manifest) }

        assertTrue(probes.all { it.selectionMode == ClientSelectionMode.PROBE_ONLY })
        assertTrue(probes.all { it !in PlaybackClientCatalog.automaticManifests })
        assertTrue(probes.all { it.content.normal == CapabilitySupport.UNKNOWN })
        assertTrue(probes.all { it.content.explicit == CapabilitySupport.UNKNOWN })
        assertTrue(probes.all { it.evidence.isNotEmpty() && !it.notes.isNullOrBlank() })

        ids.forEach { id ->
            assertNotNull(PlaybackClientCatalog.find(id))
        }
        val kids = checkNotNull(PlaybackClientCatalog.findBenchmark("ANDROID_KIDS")).client
        assertEquals("7.36.1", kids.clientVersion)
        assertEquals("11", kids.osVersion)
        assertEquals("30", kids.androidSdkVersion)
        assertEquals("com.google.android.apps.youtube.kids/7.36.1 (Linux; U; Android 11) gzip", kids.userAgent)
    }

    @Test
    fun premiumDemotionRequiresHighQualityIntent() {
        val strategy = ContentAwareFallbackStrategy()
        val request =
            ClientSelectionRequest(
                hints = ContentHints().withPremium(),
                authenticated = true,
                premium = true,
                availablePoTokenProviders = allProviders,
                webViewAvailable = true,
            )

        val normal = strategy.selectClients(request, premiumHighQuality = false)
        val high = strategy.selectClients(request, premiumHighQuality = true)

        assertEquals(
            "VISIONOS_0_1",
            normal.candidates
                .first()
                .manifest
                ?.id,
        )
        assertTrue(
            high.candidates
                .first()
                .manifest
                ?.authentication != AuthenticationPolicy.UNSUPPORTED,
        )
    }

    @Test
    fun premiumBypassRequiresCallerHintAndAuthentication() {
        val strategy = ContentAwareFallbackStrategy()
        val withoutPremium =
            strategy.selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(),
                    authenticated = true,
                ),
            )
        val withPremium =
            strategy.selectClients(
                ClientSelectionRequest(
                    hints = ContentHints().withPremium(),
                    authenticated = true,
                    premium = true,
                ),
                premiumHighQuality = true,
            )
        val signedOutPremium =
            strategy.selectClients(
                ClientSelectionRequest(
                    hints = ContentHints().withPremium(),
                    authenticated = false,
                    premium = true,
                ),
            )

        assertTrue(withoutPremium.rejected.any { it.manifest.id == "WEB_REMIX" })
        assertTrue(
            withPremium.candidates
                .first()
                .manifest
                ?.authentication != AuthenticationPolicy.UNSUPPORTED,
        )
        assertTrue(signedOutPremium.rejected.any { it.manifest.id == "WEB_REMIX" })
        assertEquals(
            "VISIONOS_0_1",
            signedOutPremium.candidates
                .first()
                .manifest
                ?.id,
        )
    }

    @Test
    fun authenticatedNonPremiumSelectionKeepsAnonymousNormalClientFirst() {
        val candidates =
            ContentAwareFallbackStrategy()
                .selectClients(
                    ClientSelectionRequest(
                        hints = ContentHints(),
                        authenticated = true,
                        availablePoTokenProviders = allProviders,
                        webViewAvailable = true,
                    ),
                ).candidates

        assertTrue(candidates.isNotEmpty())
        assertEquals("VISIONOS_0_1", candidates.first().manifest?.id)
    }

    @Test
    fun requiredTokenProvidersFilterAutomaticCandidates() {
        val result =
            ContentAwareFallbackStrategy().selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(),
                    authenticated = true,
                    availablePoTokenProviders = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    webViewAvailable = true,
                ),
            )

        assertFalse(result.candidates.any { it.manifest?.id == "WEB_SABR" })
        assertTrue(result.rejected.any { it.manifest.id == "WEB_SABR" })
    }

    @Test
    fun manualOverrideIsRetainedForTechnicalProbing() {
        val result =
            ContentAwareFallbackStrategy().selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(playbackClientOverrideId = "WEB_SABR"),
                    authenticated = true,
                    availablePoTokenProviders = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    webViewAvailable = true,
                ),
            )

        assertEquals(listOf("WEB_SABR"), result.candidates.mapNotNull { it.manifest?.id })
        assertTrue(
            result.candidates
                .single()
                .reasons
                .any { it.startsWith("ignored:") },
        )
    }

    @Test
    fun transportPreferenceControlsOrdering() {
        val result =
            ContentAwareFallbackStrategy().selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(),
                    authenticated = true,
                    availablePoTokenProviders = allProviders,
                    webViewAvailable = true,
                    transportPreference = PlaybackTransportPreference.SABR,
                ),
            )

        assertEquals(
            "VISIONOS_SABR",
            result.candidates
                .first()
                .manifest
                ?.id,
        )
        val firstDirect = result.candidates.indexOfFirst { !it.client.useSabr }
        assertTrue(firstDirect > 0)
        assertTrue(result.candidates.take(firstDirect).all { it.client.useSabr })
    }

    @Test
    fun sabrAudioPrefersSustainedPlaybackWithoutWeakeningRestrictions() {
        val strategy = ContentAwareFallbackStrategy()
        val request =
            ClientSelectionRequest(
                hints = ContentHints(isExplicit = true, sabrFirst = true),
                authenticated = true,
                availablePoTokenProviders = allProviders,
                transportPreference = PlaybackTransportPreference.SABR,
            )
        assertEquals(
            "VISIONOS_SABR",
            strategy
                .selectClients(request)
                .candidates
                .first()
                .manifest
                ?.id,
        )
        assertEquals(
            "VISIONOS_SABR",
            strategy
                .selectClients(request.copy(authenticated = false, availablePoTokenProviders = emptySet()))
                .candidates
                .first()
                .manifest
                ?.id,
        )
        assertTrue(
            strategy
                .selectClients(request.copy(excludedClients = setOf("VISIONOS_SABR__nopo")))
                .candidates
                .none { it.manifest?.id == "VISIONOS_SABR" },
        )
        for (hints in listOf(
            request.hints.copy(isAgeRestricted = true),
            request.hints.copy(isKidsContent = true),
            request.hints.copy(isLive = true),
            request.hints.copy(isUploaded = true),
            request.hints.copy(wantVideo = true),
        )) {
            assertTrue(strategy.selectClients(request.copy(hints = hints)).candidates.none { it.manifest?.id == "VISIONOS_SABR" })
        }
        assertEquals(
            "WEB_REMIX_SABR",
            strategy
                .selectClients(request.copy(hints = request.hints.copy(playbackClientOverrideId = "WEB_REMIX_SABR")))
                .candidates
                .single()
                .manifest
                ?.id,
        )
    }

    @Test
    fun healthMonitorAdjustsSelectionScore() {
        val monitor =
            object : ClientHealthMonitor {
                override fun scoreAdjustment(
                    clientId: String,
                    scope: ClientHealthScope?,
                ): Int = if (clientId == "WEB_REMIX") -100 else 0
            }
        val result =
            ContentAwareFallbackStrategy(monitor).selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(),
                    authenticated = true,
                    availablePoTokenProviders = allProviders,
                    webViewAvailable = true,
                ),
            )

        val remix =
            assertNotNull(result.candidates.first { it.manifest?.id == "WEB_REMIX" })
        assertTrue(remix.reasons.any { it == "runtime-health=-100" })
    }

    @Test
    fun profileIdsRoundTripToManifest() {
        val manifest = assertNotNull(PlaybackClientCatalog.findManifest("WEB_REMIX"))
        PlaybackClientCatalog
            .profileIds(manifest, usedPoToken = false)
            .filter { "__" in it }
            .forEach { profileId ->
                assertEquals("WEB_REMIX", PlaybackClientCatalog.manifestIdFromProfileId(profileId))
            }
    }

    @Test
    fun signedOutRequiredAuthenticationIsRejected() {
        val result =
            ContentAwareFallbackStrategy().selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(isExplicit = true),
                    authenticated = false,
                    availablePoTokenProviders = allProviders,
                    webViewAvailable = true,
                ),
            )

        assertTrue(result.candidates.none { it.manifest?.authentication == AuthenticationPolicy.REQUIRED })
        assertTrue(result.rejected.any { it.manifest.authentication == AuthenticationPolicy.REQUIRED })
    }

    @Test
    fun manualOverrideRemainsAvailableForDiagnosticProbe() {
        val result =
            ContentAwareFallbackStrategy().selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(playbackClientOverrideId = "WEB_SABR"),
                    authenticated = true,
                    availablePoTokenProviders = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    webViewAvailable = true,
                ),
            )

        assertEquals(listOf("WEB_SABR"), result.candidates.mapNotNull { it.manifest?.id })
        assertTrue(
            result.candidates
                .single()
                .reasons
                .any { it.startsWith("ignored:") },
        )
    }

    @Test
    fun excludedAnonymousProfileFallsBackToAnotherAutomaticClient() {
        val result =
            ContentAwareFallbackStrategy().selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(),
                    authenticated = true,
                    availablePoTokenProviders = allProviders,
                    webViewAvailable = true,
                    excludedClients = setOf("VISIONOS_0_1__nopo"),
                ),
            )

        assertTrue(result.candidates.none { it.manifest?.id == "VISIONOS_0_1" })
        assertTrue(result.candidates.isNotEmpty())
    }

    @Test
    fun automaticCandidatesKeepSabrBehindAllDirectClients() {
        val candidates =
            ContentAwareFallbackStrategy()
                .selectClients(
                    ClientSelectionRequest(
                        hints = ContentHints(),
                        authenticated = true,
                        availablePoTokenProviders = allProviders,
                        webViewAvailable = true,
                    ),
                ).candidates
        val firstSabr = candidates.indexOfFirst { it.client.useSabr }

        assertTrue(firstSabr > 0)
        assertTrue(candidates.take(firstSabr).none { it.client.useSabr })
        assertTrue(candidates.drop(firstSabr).all { it.client.useSabr })
    }

    @Test
    fun kidsPlaybackTriesUntokenizedDirectClientBeforeTokenMinting() {
        val candidates =
            ContentAwareFallbackStrategy()
                .selectClients(
                    ClientSelectionRequest(
                        hints = ContentHints(isKidsContent = true),
                        authenticated = true,
                        availablePoTokenProviders = allProviders,
                        webViewAvailable = true,
                    ),
                ).candidates
        assertEquals("WEB_KIDS", candidates.first().manifest?.id)
    }

    @Test
    fun explicitAudioPrefersSustainedPlaybackOverPlayableWebResponse() {
        val strategy = ContentAwareFallbackStrategy()
        val request =
            ClientSelectionRequest(
                hints = ContentHints(isExplicit = true),
                authenticated = true,
                availablePoTokenProviders = allProviders,
                webViewAvailable = true,
            )
        assertEquals(CapabilitySupport.LIMITED, PlaybackClientCatalog.findManifest("VISIONOS_0_1")?.content?.explicit)
        assertEquals(
            "VISIONOS_0_1",
            strategy
                .selectClients(request)
                .candidates
                .first()
                .manifest
                ?.id,
        )
        assertEquals(
            "WEB_REMIX",
            strategy
                .selectClients(request.copy(excludedClients = setOf("VISIONOS_0_1__nopo")))
                .candidates
                .first()
                .manifest
                ?.id,
        )
        assertEquals(
            "WEB_REMIX",
            strategy
                .selectClients(request.copy(hints = ContentHints(isExplicit = true, isAgeRestricted = true)))
                .candidates
                .first()
                .manifest
                ?.id,
        )
        assertEquals(
            "WEB_REMIX",
            strategy
                .selectClients(request.copy(hints = ContentHints(isExplicit = true, playbackClientOverrideId = "WEB_REMIX")))
                .candidates
                .single()
                .manifest
                ?.id,
        )
    }

    @Test
    fun explicitVideoDoesNotAutoSelectAudioOnlyEvidence() {
        val strategy = ContentAwareFallbackStrategy()
        val request =
            ClientSelectionRequest(
                hints = ContentHints(isExplicit = true, wantVideo = true),
                authenticated = false,
                availablePoTokenProviders = allProviders,
                transportPreference = PlaybackTransportPreference.DIRECT,
            )
        val automatic = strategy.selectClients(request)
        assertTrue(automatic.candidates.isNotEmpty())
        assertTrue(automatic.candidates.none { it.manifest?.id == "VISIONOS_0_1" })
        assertTrue(automatic.rejected.any { it.manifest.id == "VISIONOS_0_1" })

        val forced =
            strategy.selectClients(
                request.copy(hints = request.hints.copy(playbackClientOverrideId = "VISIONOS_0_1")),
            )
        assertEquals(
            "VISIONOS_0_1",
            forced.candidates
                .single()
                .manifest
                ?.id,
        )
    }

    @Test
    fun configFreeFastPathExcludesWatchPageDependentClients() {
        val candidates =
            ContentAwareFallbackStrategy()
                .selectClients(
                    ClientSelectionRequest(
                        hints = ContentHints(),
                        authenticated = true,
                        availablePoTokenProviders = allProviders,
                        webViewAvailable = true,
                        fastPathOnly = true,
                        javaScriptRuntimeAvailable = false,
                    ),
                ).candidates
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.manifest?.request?.signatureTimestamp == false })
        assertTrue(candidates.none { it.client.useWebPoTokens })
    }

    @Test
    fun healthAdjustmentIsIncludedInSelectionReasons() {
        val monitor =
            object : ClientHealthMonitor {
                override fun scoreAdjustment(
                    clientId: String,
                    scope: ClientHealthScope?,
                ): Int = if (clientId == "WEB_REMIX") -100 else 0
            }
        val result =
            ContentAwareFallbackStrategy(monitor).selectClients(
                ClientSelectionRequest(
                    hints = ContentHints(),
                    authenticated = true,
                    availablePoTokenProviders = allProviders,
                    webViewAvailable = true,
                ),
            )

        val remix = assertNotNull(result.candidates.first { it.manifest?.id == "WEB_REMIX" })
        assertTrue(remix.reasons.contains("runtime-health=-100"))
    }
}
