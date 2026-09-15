package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.models.Thumbnail
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.sabr.SabrBootstrap
import kotlin.time.Instant

public data class ContentHints(
    val isExplicit: Boolean? = null,
    val isKidsContent: Boolean? = null,
    val isAgeRestricted: Boolean? = null,
    val isLive: Boolean? = null,
    val isUploaded: Boolean? = null,
    val isLocal: Boolean? = null,
    val endpointParams: String? = null,
    val wantVideo: Boolean = false,
    val playbackClientOverrideId: String? = null,
    val sabrFirst: Boolean = false,
    val maxVideoHeight: Int? = null,
    /** True only when the caller has independently confirmed YouTube Premium entitlement. */
    val premium: Boolean = false,
) {
    public var allowHls: Boolean = true
        private set
    public var allowSabr: Boolean = true
        private set
    public var allowBoundedRange: Boolean = true
        private set

    public fun withStreamCapabilities(
        allowHls: Boolean = true,
        allowSabr: Boolean = true,
        allowBoundedRange: Boolean = true,
    ): ContentHints =
        copy().also {
            it.allowHls = allowHls
            it.allowSabr = allowSabr
            it.allowBoundedRange = allowBoundedRange
        }

    override fun toString(): String = diagnosticSummary()
}

public fun ContentHints.diagnosticSummary(): String =
    "{explicit=$isExplicit, kids=$isKidsContent, ageRestricted=$isAgeRestricted, " +
        "live=$isLive, uploaded=$isUploaded, local=$isLocal, " +
        "endpointParamsPresent=${endpointParams != null}, wantVideo=$wantVideo, " +
        "clientOverride=$playbackClientOverrideId, sabrFirst=$sabrFirst, maxVideoHeight=$maxVideoHeight, " +
        "premium=$premium, allowHls=$allowHls, allowSabr=$allowSabr, allowBoundedRange=$allowBoundedRange}"

public data class PlayerConfig(
    val playerUrl: String,
    val signatureTimestamp: Int?,
    val visitorData: String?,
    val clientVersion: String?,
    val encryptedHostFlags: String? = null,
) {
    override fun toString(): String =
        "PlayerConfig(" +
            "playerUrl=${playerUrl.presence()}, " +
            "signatureTimestamp=${signatureTimestamp != null}, " +
            "visitorData=${visitorData.presence()}, " +
            "clientVersion=${clientVersion.presence()}, " +
            "encryptedHostFlags=${encryptedHostFlags.presence()})"
}

internal data class ClientResult(
    val clientName: String,
    val profileId: String,
    val userAgent: String,
    val response: PlayerResponse,
    val usedPoToken: Boolean = false,
    val streamingDataPoToken: String? = null,
    val clientId: Int = 0,
    val clientVersion: String = "",
    val useSabr: Boolean = false,
) {
    override fun toString(): String =
        "ClientResult(" +
            "clientName=$clientName, " +
            "profileId=$profileId, " +
            "usedPoToken=$usedPoToken, " +
            "streamingDataPoToken=${streamingDataPoToken.presence()}, " +
            "clientId=$clientId, " +
            "clientVersion=${clientVersion.presence()}, " +
            "useSabr=$useSabr)"
}

/** Playback-statistics URLs are session data and must not be logged or serialized unintentionally. */
public data class PlaybackTrackingData(
    val clientPlaybackNonce: String,
    val playbackUrl: String?,
    val watchtimeUrl: String?,
    val scheduledFlushWalltimeSeconds: List<Int>?,
    val defaultFlushIntervalSeconds: Int?,
    val resolvedAtEpochMs: Long,
) {
    override fun toString(): String =
        "PlaybackTrackingData(" +
            "clientPlaybackNonce=${clientPlaybackNonce.presence()}, " +
            "playbackUrl=${playbackUrl.presence()}, " +
            "watchtimeUrl=${watchtimeUrl.presence()}, " +
            "scheduledFlushWalltimeSeconds=${scheduledFlushWalltimeSeconds?.size ?: 0}, " +
            "defaultFlushIntervalSeconds=$defaultFlushIntervalSeconds, " +
            "resolvedAtEpochMs=$resolvedAtEpochMs)"
}

/** Media metadata is sensitive and intentionally redacted from [toString]. */
public data class ExtractedMediaMetadata(
    val title: String?,
    val author: String?,
    val channelId: String?,
    val durationSeconds: Long?,
    val musicVideoType: String?,
    val viewCount: String?,
    val thumbnails: List<Thumbnail>,
    val isLive: Boolean,
) {
    override fun toString(): String =
        "ExtractedMediaMetadata(" +
            "title=${title.presence()}, " +
            "author=${author.presence()}, " +
            "channelId=${channelId.presence()}, " +
            "durationSeconds=$durationSeconds, " +
            "musicVideoType=$musicVideoType, " +
            "viewCount=${viewCount.presence()}, " +
            "thumbnailCount=${thumbnails.size}, " +
            "isLive=$isLive)"
}

/** Signed media URLs and request headers are sensitive and intentionally redacted from [toString]. */
public data class ExtractedStream(
    val videoId: String,
    val audioUrl: String,
    val headers: Map<String, String>,
    val loudnessDb: Double?,
    val expiresAt: Instant?,
    val contentLengthBytes: Long?,
    val itag: Int,
    val mimeType: String?,
    val codecs: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val clientName: String,
    val profileId: String,
    val requireBoundedRange: Boolean,
    val rangeChunkSizeBytes: Long,
    val useRangeChunks: Boolean = false,
    val playbackTracking: PlaybackTrackingData? = null,
    val streamDiagnostics: StreamDiagnostics? = null,
    val videoUrl: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoMimeType: String? = null,
    val videoCodecs: String? = null,
    val videoBitrate: Int? = null,
    val videoItag: Int? = null,
    val videoContentLengthBytes: Long? = null,
    val sabrBootstrap: SabrBootstrap? = null,
    val sabrVideoBootstrap: SabrBootstrap? = null,
    val availableVideoHeights: List<Int> = emptyList(),
) {
    public var perceptualLoudnessDb: Double? = null
        internal set
    public var mediaMetadata: ExtractedMediaMetadata? = null
        internal set

    override fun toString(): String =
        "ExtractedStream(" +
            "videoId=${videoId.presence()}, " +
            "audioUrl=${audioUrl.presence()}, " +
            "headerCount=${headers.size}, " +
            "contentLengthBytes=$contentLengthBytes, " +
            "itag=$itag, " +
            "mimeType=$mimeType, " +
            "clientName=$clientName, " +
            "profileId=$profileId, " +
            "mediaMetadata=${mediaMetadata != null}, " +
            "videoUrl=${videoUrl.presence()}, " +
            "videoItag=$videoItag, " +
            "sabr=${sabrBootstrap != null}, " +
            "sabrVideo=${sabrVideoBootstrap != null})"
}

public data class StreamAttemptDiagnostic(
    val clientName: String,
    val profileId: String?,
    val userAgent: String,
    val outcome: String,
)

public data class StreamDiagnostics(
    val attempts: List<StreamAttemptDiagnostic>,
    val usedAuthenticatedWatchPage: Boolean,
)

public class StreamResolveException(
    public val reason: Reason,
    message: String,
    cause: Throwable? = null,
    public val diagnostics: StreamDiagnostics? = null,
) : IllegalStateException(message, cause) {
    public enum class Reason {
        NO_PLAYABLE_STREAM,
        EXPLICIT_UNSUPPORTED,
        UNAVAILABLE,
        AGE_RESTRICTED,
        NO_MUSIC_VIDEO,
        NETWORK,
        UNKNOWN,
    }
}

private fun String?.presence(): String = if (isNullOrBlank()) "missing" else "present"
