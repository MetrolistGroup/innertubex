package com.metrolist.innertubex.models.response

import com.metrolist.innertubex.models.PlayerFormatListSerializer
import com.metrolist.innertubex.models.Thumbnails
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PlayerResponse(
    val playabilityStatus: PlayabilityStatus,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails? = null,
    val playbackTracking: PlaybackTracking? = null,
    val playerConfig: PlayerConfig? = null,
) {
    // Keep this wire-only signal out of the public constructor and copy contract.
    internal var adPlacements: List<AdPlacement>? = null

    @Serializable
    internal data class AdPlacement(
        val adPlacementRenderer: Renderer? = null,
    ) {
        @Serializable
        data class Renderer(
            val config: Config? = null,
            val renderer: Content? = null,
        )

        @Serializable
        data class Config(
            val adPlacementConfig: PlacementConfig? = null,
        )

        @Serializable
        data class PlacementConfig(
            val kind: String? = null,
        )

        @Serializable
        data class Content(
            val instreamVideoAdRenderer: VideoAd? = null,
        )

        @Serializable
        data class VideoAd(
            val playerVars: String? = null,
            val skipOffsetMilliseconds: Long? = null,
        )
    }

    /**
     * Anonymous TV media answers 403 until the pre-roll ads would have finished.
     * shortcut: reads adPlacements only, a response using adSlots fails with 403; handle adSlots if seen live.
     */
    internal fun preRollWaitMs(): Long =
        adPlacements.orEmpty().fold(0L) { total, placement ->
            val renderer = placement.adPlacementRenderer
            val ad = renderer?.renderer?.instreamVideoAdRenderer
            if (renderer?.config?.adPlacementConfig?.kind != "AD_PLACEMENT_KIND_START" || ad == null) {
                total
            } else {
                val duration =
                    ad.skipOffsetMilliseconds?.takeIf { it >= 0 }
                        ?: AD_LENGTH_SECONDS
                            .find(ad.playerVars.orEmpty())
                            ?.groupValues
                            ?.get(1)
                            ?.takeUnless { it.startsWith('-') }
                            ?.let { (it.toLongOrNull() ?: Long.MAX_VALUE).coerceAtMost(MAX_PRE_ROLL_WAIT_MS / 1_000L) * 1_000L }
                        ?: 0L
                (total + duration.coerceAtMost(MAX_PRE_ROLL_WAIT_MS)).coerceAtMost(MAX_PRE_ROLL_WAIT_MS)
            }
        }

    @Serializable
    data class PlayabilityStatus(
        val status: String,
        val reason: String? = null,
    ) {
        // Keep this wire-only signal out of the public constructor and copy contract.
        internal var desktopLegacyAgeGateReason: Int? = null
    }

    @Serializable
    data class StreamingData(
        @Serializable(with = PlayerFormatListSerializer::class)
        val formats: List<Format>? = null,
        @Serializable(with = PlayerFormatListSerializer::class)
        val adaptiveFormats: List<Format> = emptyList(),
        val expiresInSeconds: Int? = null,
        val hlsManifestUrl: String? = null,
        val dashManifestUrl: String? = null,
        val serverAbrStreamingUrl: String? = null,
    ) {
        @Serializable
        data class Format(
            val itag: Int = -1,
            val url: String? = null,
            val mimeType: String = "",
            val bitrate: Int = 0,
            val averageBitrate: Int? = null,
            val width: Int? = null,
            val height: Int? = null,
            val contentLength: Long? = null,
            val quality: String? = null,
            val audioQuality: String? = null,
            val approxDurationMs: String? = null,
            val audioSampleRate: Int? = null,
            val audioChannels: Int? = null,
            val loudnessDb: Double? = null,
            val lastModified: String? = null,
            val xtags: String? = null,
            val audioTrack: AudioTrack? = null,
            val isDrc: Boolean = false,
            @JsonNames("signature_cipher")
            val signatureCipher: String? = null,
            val cipher: String? = null,
        ) {
            val isAudio: Boolean
                get() = width == null

            @Serializable
            data class AudioTrack(
                val id: String? = null,
                val displayName: String? = null,
                val audioIsDefault: Boolean? = null,
            )
        }
    }

    @Serializable
    class PlayerConfig(
        val mediaCommonConfig: MediaCommonConfig? = null,
    ) {
        var audioConfig: AudioConfig? = null
            private set

        operator fun component1(): MediaCommonConfig? = mediaCommonConfig

        fun copy(mediaCommonConfig: MediaCommonConfig? = this.mediaCommonConfig): PlayerConfig =
            PlayerConfig(mediaCommonConfig).also { it.audioConfig = audioConfig }

        override fun equals(other: Any?): Boolean =
            other is PlayerConfig && mediaCommonConfig == other.mediaCommonConfig && audioConfig == other.audioConfig

        override fun hashCode(): Int = 31 * (mediaCommonConfig?.hashCode() ?: 0) + (audioConfig?.hashCode() ?: 0)

        override fun toString(): String = "PlayerConfig(mediaCommonConfig=$mediaCommonConfig, audioConfig=$audioConfig)"

        @Serializable
        data class AudioConfig(
            val loudnessDb: Double? = null,
            val perceptualLoudnessDb: Double? = null,
        )

        @Serializable
        data class MediaCommonConfig(
            val mediaUstreamerRequestConfig: MediaUstreamerRequestConfig? = null,
        ) {
            @Serializable
            data class MediaUstreamerRequestConfig(
                val videoPlaybackUstreamerConfig: String? = null,
            )
        }
    }

    @Serializable
    data class VideoDetails(
        val videoId: String = "",
        val title: String? = null,
        val author: String? = null,
        val channelId: String? = null,
        val lengthSeconds: String? = null,
        val musicVideoType: String? = null,
        val viewCount: String? = null,
        val thumbnail: Thumbnails? = null,
        val isLiveContent: Boolean? = null,
    )

    @Serializable
    data class PlaybackTracking(
        val videostatsPlaybackUrl: VideostatsUrl? = null,
        val videostatsWatchtimeUrl: VideostatsUrl? = null,
        val atrUrl: VideostatsUrl? = null,
        val videostatsScheduledFlushWalltimeSeconds: List<Int>? = null,
        val videostatsDefaultFlushIntervalSeconds: Int? = null,
    ) {
        @Serializable
        data class VideostatsUrl(
            val baseUrl: String,
        )
    }
}

internal const val MAX_PRE_ROLL_WAIT_MS = 35_000L
private val AD_LENGTH_SECONDS = Regex("(?:^|&)length_seconds=(-?\\d+)(?:&|$)")
