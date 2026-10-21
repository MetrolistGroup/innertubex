package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.models.response.PlayerResponse

internal data class PlayabilityFailure(
    val status: String?,
    val reason: String?,
    val desktopLegacyAgeGateReason: Int? = null,
    val category: ClientFailureKind = classifyPlayabilityFailure(status, desktopLegacyAgeGateReason),
)

internal fun PlayerResponse.PlayabilityStatus.toFailure(): PlayabilityFailure =
    PlayabilityFailure(status, reason, desktopLegacyAgeGateReason)

internal fun isConfirmedAgeGate(
    status: String?,
    desktopLegacyAgeGateReason: Int?,
): Boolean =
    status == "AGE_CHECK_REQUIRED" || status == "AGE_VERIFICATION_REQUIRED" ||
        (status == "LOGIN_REQUIRED" && (desktopLegacyAgeGateReason ?: 0) > 0)

internal fun PlayabilityFailure.terminalReason(): StreamResolveException.Reason? =
    when {
        status == "ERROR" -> StreamResolveException.Reason.UNAVAILABLE
        isAgeRestricted() -> StreamResolveException.Reason.AGE_RESTRICTED
        else -> null
    }

internal data class PlayerResponseBatch(
    val playableResponses: List<ClientResult>,
    val failures: List<PlayabilityFailure>,
    val requestFailures: List<Throwable>,
    val attempts: List<StreamAttemptDiagnostic>,
    val onlyAccountGateFailures: Boolean = false,
    val onlyUnavailableFailures: Boolean = false,
    val onlyBootstrapIndependentFailures: Boolean = false,
)

internal fun String?.isAccountGateStatus(): Boolean =
    this == "LOGIN_REQUIRED" || this == "AGE_CHECK_REQUIRED" ||
        this == "AGE_VERIFICATION_REQUIRED" || this == "CONTENT_CHECK_REQUIRED"

internal fun classifyPlayabilityFailures(failures: List<PlayabilityFailure>): StreamResolveException.Reason? {
    if (failures.any(PlayabilityFailure::isAgeRestricted)) {
        return StreamResolveException.Reason.AGE_RESTRICTED
    }
    if (failures.any(PlayabilityFailure::isUnavailable)) {
        return StreamResolveException.Reason.UNAVAILABLE
    }
    return null
}

private fun PlayabilityFailure.isAgeRestricted(): Boolean {
    val normalizedStatus = status.orEmpty().uppercase()
    val normalizedReason = reason.orEmpty().lowercase()
    return isConfirmedAgeGate(normalizedStatus, desktopLegacyAgeGateReason) || normalizedStatus == "CONTENT_CHECK_REQUIRED" ||
        listOf("confirm your age", "age-restricted", "age restricted", "age verification")
            .any(normalizedReason::contains)
}

private fun classifyPlayabilityFailure(
    status: String?,
    desktopLegacyAgeGateReason: Int?,
): ClientFailureKind {
    val normalizedStatus = status.orEmpty().uppercase()
    return when {
        isConfirmedAgeGate(normalizedStatus, desktopLegacyAgeGateReason) || normalizedStatus == "CONTENT_CHECK_REQUIRED" -> {
            ClientFailureKind.PLAYABILITY
        }

        normalizedStatus in setOf("UNPLAYABLE", "VIDEO_UNAVAILABLE", "LIVE_STREAM_OFFLINE") -> {
            ClientFailureKind.PLAYABILITY
        }

        normalizedStatus == "LOGIN_REQUIRED" -> {
            ClientFailureKind.TOKEN
        }

        else -> {
            ClientFailureKind.PLAYABILITY
        }
    }
}

private fun PlayabilityFailure.isUnavailable(): Boolean {
    val normalizedStatus = status.orEmpty().uppercase()
    val normalizedReason = reason.orEmpty().lowercase()
    return normalizedStatus in setOf("ERROR", "UNPLAYABLE", "VIDEO_UNAVAILABLE", "LIVE_STREAM_OFFLINE") ||
        listOf("video unavailable", "not available", "private video", "has been removed")
            .any(normalizedReason::contains)
}
