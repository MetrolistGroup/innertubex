package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.models.Thumbnail
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.sabr.SabrBootstrap
import kotlin.time.Instant

public class ContentHints(
    public val isExplicit: Boolean? = null,
    public val isKidsContent: Boolean? = null,
    public val isAgeRestricted: Boolean? = null,
    public val isLive: Boolean? = null,
    public val isUploaded: Boolean? = null,
    public val isLocal: Boolean? = null,
    public val endpointParams: String? = null,
    public val wantVideo: Boolean = false,
    public val playbackClientOverrideId: String? = null,
    public val sabrFirst: Boolean = false,
    public val maxVideoHeight: Int? = null,
) {
    /** True only when the caller has independently confirmed YouTube Premium entitlement. */
    public var premium: Boolean = false
        private set
    public var allowHls: Boolean = true
        private set
    public var allowSabr: Boolean = true
        private set
    public var allowBoundedRange: Boolean = true
        private set

    public operator fun component1(): Boolean? = isExplicit

    public operator fun component2(): Boolean? = isKidsContent

    public operator fun component3(): Boolean? = isAgeRestricted

    public operator fun component4(): Boolean? = isLive

    public operator fun component5(): Boolean? = isUploaded

    public operator fun component6(): Boolean? = isLocal

    public operator fun component7(): String? = endpointParams

    public operator fun component8(): Boolean = wantVideo

    public operator fun component9(): String? = playbackClientOverrideId

    public operator fun component10(): Boolean = sabrFirst

    public operator fun component11(): Int? = maxVideoHeight

    public fun copy(
        isExplicit: Boolean? = this.isExplicit,
        isKidsContent: Boolean? = this.isKidsContent,
        isAgeRestricted: Boolean? = this.isAgeRestricted,
        isLive: Boolean? = this.isLive,
        isUploaded: Boolean? = this.isUploaded,
        isLocal: Boolean? = this.isLocal,
        endpointParams: String? = this.endpointParams,
        wantVideo: Boolean = this.wantVideo,
        playbackClientOverrideId: String? = this.playbackClientOverrideId,
        sabrFirst: Boolean = this.sabrFirst,
        maxVideoHeight: Int? = this.maxVideoHeight,
    ): ContentHints =
        ContentHints(
            isExplicit = isExplicit,
            isKidsContent = isKidsContent,
            isAgeRestricted = isAgeRestricted,
            isLive = isLive,
            isUploaded = isUploaded,
            isLocal = isLocal,
            endpointParams = endpointParams,
            wantVideo = wantVideo,
            playbackClientOverrideId = playbackClientOverrideId,
            sabrFirst = sabrFirst,
            maxVideoHeight = maxVideoHeight,
        ).also {
            it.premium = premium
            it.allowHls = allowHls
            it.allowSabr = allowSabr
            it.allowBoundedRange = allowBoundedRange
        }

    /** Returns a copy carrying the caller-confirmed entitlement state. */
    public fun withPremium(premium: Boolean = true): ContentHints =
        copy().also {
            it.premium = premium
        }

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentHints) return false
        return isExplicit == other.isExplicit &&
            isKidsContent == other.isKidsContent &&
            isAgeRestricted == other.isAgeRestricted &&
            isLive == other.isLive &&
            isUploaded == other.isUploaded &&
            isLocal == other.isLocal &&
            endpointParams == other.endpointParams &&
            wantVideo == other.wantVideo &&
            playbackClientOverrideId == other.playbackClientOverrideId &&
            sabrFirst == other.sabrFirst &&
            maxVideoHeight == other.maxVideoHeight &&
            premium == other.premium &&
            allowHls == other.allowHls &&
            allowSabr == other.allowSabr &&
            allowBoundedRange == other.allowBoundedRange
    }

    override fun hashCode(): Int {
        var result = isExplicit?.hashCode() ?: 0
        result = 31 * result + (isKidsContent?.hashCode() ?: 0)
        result = 31 * result + (isAgeRestricted?.hashCode() ?: 0)
        result = 31 * result + (isLive?.hashCode() ?: 0)
        result = 31 * result + (isUploaded?.hashCode() ?: 0)
        result = 31 * result + (isLocal?.hashCode() ?: 0)
        result = 31 * result + (endpointParams?.hashCode() ?: 0)
        result = 31 * result + wantVideo.hashCode()
        result = 31 * result + (playbackClientOverrideId?.hashCode() ?: 0)
        result = 31 * result + sabrFirst.hashCode()
        result = 31 * result + (maxVideoHeight ?: 0)
        result = 31 * result + premium.hashCode()
        result = 31 * result + allowHls.hashCode()
        result = 31 * result + allowSabr.hashCode()
        return 31 * result + allowBoundedRange.hashCode()
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
    val bearerAuthenticated: Boolean = false,
) {
    override fun toString(): String =
        "ClientResult(" +
            "clientName=$clientName, " +
            "profileId=$profileId, " +
            "usedPoToken=$usedPoToken, " +
            "streamingDataPoToken=${streamingDataPoToken.presence()}, " +
            "clientId=$clientId, " +
            "clientVersion=${clientVersion.presence()}, " +
            "useSabr=$useSabr, " +
            "bearerAuthenticated=$bearerAuthenticated)"
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
public class ExtractedStream(
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

    // Keep the original constructor, component and copy signatures for existing hosts.
    public operator fun component1(): String = videoId

    public operator fun component2(): String = audioUrl

    public operator fun component3(): Map<String, String> = headers

    public operator fun component4(): Double? = loudnessDb

    public operator fun component5(): Instant? = expiresAt

    public operator fun component6(): Long? = contentLengthBytes

    public operator fun component7(): Int = itag

    public operator fun component8(): String? = mimeType

    public operator fun component9(): String? = codecs

    public operator fun component10(): Int? = bitrate

    public operator fun component11(): Int? = sampleRate

    public operator fun component12(): String = clientName

    public operator fun component13(): String = profileId

    public operator fun component14(): Boolean = requireBoundedRange

    public operator fun component15(): Long = rangeChunkSizeBytes

    public operator fun component16(): Boolean = useRangeChunks

    public operator fun component17(): PlaybackTrackingData? = playbackTracking

    public operator fun component18(): StreamDiagnostics? = streamDiagnostics

    public operator fun component19(): String? = videoUrl

    public operator fun component20(): Int? = videoWidth

    public operator fun component21(): Int? = videoHeight

    public operator fun component22(): String? = videoMimeType

    public operator fun component23(): String? = videoCodecs

    public operator fun component24(): Int? = videoBitrate

    public operator fun component25(): Int? = videoItag

    public operator fun component26(): Long? = videoContentLengthBytes

    public operator fun component27(): SabrBootstrap? = sabrBootstrap

    public operator fun component28(): SabrBootstrap? = sabrVideoBootstrap

    public operator fun component29(): List<Int> = availableVideoHeights

    public fun copy(
        videoId: String = this.videoId,
        audioUrl: String = this.audioUrl,
        headers: Map<String, String> = this.headers,
        loudnessDb: Double? = this.loudnessDb,
        expiresAt: Instant? = this.expiresAt,
        contentLengthBytes: Long? = this.contentLengthBytes,
        itag: Int = this.itag,
        mimeType: String? = this.mimeType,
        codecs: String? = this.codecs,
        bitrate: Int? = this.bitrate,
        sampleRate: Int? = this.sampleRate,
        clientName: String = this.clientName,
        profileId: String = this.profileId,
        requireBoundedRange: Boolean = this.requireBoundedRange,
        rangeChunkSizeBytes: Long = this.rangeChunkSizeBytes,
        useRangeChunks: Boolean = this.useRangeChunks,
        playbackTracking: PlaybackTrackingData? = this.playbackTracking,
        streamDiagnostics: StreamDiagnostics? = this.streamDiagnostics,
        videoUrl: String? = this.videoUrl,
        videoWidth: Int? = this.videoWidth,
        videoHeight: Int? = this.videoHeight,
        videoMimeType: String? = this.videoMimeType,
        videoCodecs: String? = this.videoCodecs,
        videoBitrate: Int? = this.videoBitrate,
        videoItag: Int? = this.videoItag,
        videoContentLengthBytes: Long? = this.videoContentLengthBytes,
        sabrBootstrap: SabrBootstrap? = this.sabrBootstrap,
        sabrVideoBootstrap: SabrBootstrap? = this.sabrVideoBootstrap,
        availableVideoHeights: List<Int> = this.availableVideoHeights,
    ): ExtractedStream =
        ExtractedStream(
            videoId = videoId,
            audioUrl = audioUrl,
            headers = headers,
            loudnessDb = loudnessDb,
            expiresAt = expiresAt,
            contentLengthBytes = contentLengthBytes,
            itag = itag,
            mimeType = mimeType,
            codecs = codecs,
            bitrate = bitrate,
            sampleRate = sampleRate,
            clientName = clientName,
            profileId = profileId,
            requireBoundedRange = requireBoundedRange,
            rangeChunkSizeBytes = rangeChunkSizeBytes,
            useRangeChunks = useRangeChunks,
            playbackTracking = playbackTracking,
            streamDiagnostics = streamDiagnostics,
            videoUrl = videoUrl,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoMimeType = videoMimeType,
            videoCodecs = videoCodecs,
            videoBitrate = videoBitrate,
            videoItag = videoItag,
            videoContentLengthBytes = videoContentLengthBytes,
            sabrBootstrap = sabrBootstrap,
            sabrVideoBootstrap = sabrVideoBootstrap,
            availableVideoHeights = availableVideoHeights,
        ).also {
            it.perceptualLoudnessDb = perceptualLoudnessDb
            it.mediaMetadata = mediaMetadata
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedStream) return false
        return videoId == other.videoId &&
            audioUrl == other.audioUrl &&
            headers == other.headers &&
            (loudnessDb?.equals(other.loudnessDb) ?: (other.loudnessDb == null)) &&
            expiresAt == other.expiresAt &&
            contentLengthBytes == other.contentLengthBytes &&
            itag == other.itag &&
            mimeType == other.mimeType &&
            codecs == other.codecs &&
            bitrate == other.bitrate &&
            sampleRate == other.sampleRate &&
            clientName == other.clientName &&
            profileId == other.profileId &&
            requireBoundedRange == other.requireBoundedRange &&
            rangeChunkSizeBytes == other.rangeChunkSizeBytes &&
            useRangeChunks == other.useRangeChunks &&
            playbackTracking == other.playbackTracking &&
            streamDiagnostics == other.streamDiagnostics &&
            videoUrl == other.videoUrl &&
            videoWidth == other.videoWidth &&
            videoHeight == other.videoHeight &&
            videoMimeType == other.videoMimeType &&
            videoCodecs == other.videoCodecs &&
            videoBitrate == other.videoBitrate &&
            videoItag == other.videoItag &&
            videoContentLengthBytes == other.videoContentLengthBytes &&
            sabrBootstrap == other.sabrBootstrap &&
            sabrVideoBootstrap == other.sabrVideoBootstrap &&
            availableVideoHeights == other.availableVideoHeights &&
            (perceptualLoudnessDb?.equals(other.perceptualLoudnessDb) ?: (other.perceptualLoudnessDb == null)) &&
            mediaMetadata == other.mediaMetadata
    }

    override fun hashCode(): Int {
        var result = videoId.hashCode()
        result = 31 * result + audioUrl.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (loudnessDb?.hashCode() ?: 0)
        result = 31 * result + (expiresAt?.hashCode() ?: 0)
        result = 31 * result + (contentLengthBytes?.hashCode() ?: 0)
        result = 31 * result + itag.hashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + (codecs?.hashCode() ?: 0)
        result = 31 * result + (bitrate?.hashCode() ?: 0)
        result = 31 * result + (sampleRate?.hashCode() ?: 0)
        result = 31 * result + clientName.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + requireBoundedRange.hashCode()
        result = 31 * result + rangeChunkSizeBytes.hashCode()
        result = 31 * result + useRangeChunks.hashCode()
        result = 31 * result + (playbackTracking?.hashCode() ?: 0)
        result = 31 * result + (streamDiagnostics?.hashCode() ?: 0)
        result = 31 * result + (videoUrl?.hashCode() ?: 0)
        result = 31 * result + (videoWidth?.hashCode() ?: 0)
        result = 31 * result + (videoHeight?.hashCode() ?: 0)
        result = 31 * result + (videoMimeType?.hashCode() ?: 0)
        result = 31 * result + (videoCodecs?.hashCode() ?: 0)
        result = 31 * result + (videoBitrate?.hashCode() ?: 0)
        result = 31 * result + (videoItag?.hashCode() ?: 0)
        result = 31 * result + (videoContentLengthBytes?.hashCode() ?: 0)
        result = 31 * result + (sabrBootstrap?.hashCode() ?: 0)
        result = 31 * result + (sabrVideoBootstrap?.hashCode() ?: 0)
        result = 31 * result + availableVideoHeights.hashCode()
        result = 31 * result + (perceptualLoudnessDb?.hashCode() ?: 0)
        result = 31 * result + (mediaMetadata?.hashCode() ?: 0)
        return result
    }

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
