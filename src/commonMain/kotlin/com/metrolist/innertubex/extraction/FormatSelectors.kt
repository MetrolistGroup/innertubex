package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format

public fun selectBestAudioFormat(
    formats: List<Format>,
    audioQuality: AudioQuality = AudioQuality.AUTO,
    requireUrl: Boolean = true,
): Format? {
    val validFormats = if (requireUrl) formats.filter { !it.url.isNullOrBlank() } else formats
    if (validFormats.isEmpty()) return null
    return when (audioQuality) {
        AudioQuality.LOW -> {
            validFormats.filter { it.mimeType.contains("audio/mp4") }.minByOrNull { it.bitrate }
                ?: validFormats.minByOrNull { it.bitrate }
        }

        AudioQuality.AUTO -> {
            validFormats.filter { it.mimeType.contains("audio/webm") }.maxByOrNull(::audioFormatScore)
                ?: validFormats.maxByOrNull(::audioFormatScore)
        }

        AudioQuality.HIGH -> {
            validFormats.maxByOrNull(::audioFormatScore)
        }

        AudioQuality.MP4 -> {
            validFormats.filter { it.mimeType.contains("audio/mp4") }.maxByOrNull(::audioFormatScore)
        }
    }
}

public fun selectBestVideoFormat(
    formats: List<Format>,
    requireUrl: Boolean = true,
    maxHeight: Int = 2160,
): Format? {
    val validFormats = if (requireUrl) formats.filter { !it.url.isNullOrBlank() } else formats
    val videoFormats = validFormats.filter { it.width != null && (it.height ?: 0) > 0 }
    if (videoFormats.isEmpty()) return null
    val withinCap = videoFormats.filter { it.height!! <= maxHeight }
    return withinCap.maxWithOrNull(videoFormatComparator)
        ?: videoFormats
            .groupBy { it.height!! }
            .minByOrNull { it.key }
            ?.value
            ?.maxWithOrNull(videoFormatComparator)
}

private fun audioFormatScore(format: Format): Int {
    val codecRank =
        when {
            format.mimeType.contains("audio/webm") -> 100
            format.mimeType.contains("audio/mp4") -> 50
            else -> 0
        }
    val channelBonus =
        when (format.audioChannels) {
            2 -> 50_000
            1 -> 0
            else -> 25_000
        }
    return codecRank * 1_000_000 + channelBonus + format.bitrate + (format.audioSampleRate ?: 0).coerceIn(0, 48_000) / 10
}

// AVC and VP9 both have broad native decoding support; avoid excess bitrate at the same resolution.
private val videoFormatComparator =
    compareBy<Format> { it.height ?: 0 }
        .thenBy {
            val codec = it.mimeType.lowercase()
            when {
                "av01" in codec -> 1
                "avc1" in codec || "vp09" in codec || "vp9" in codec || "video/mp4" in codec || "video/webm" in codec -> 2
                else -> 0
            }
        }.thenByDescending { it.bitrate.takeIf { bitrate -> bitrate > 0 } ?: Int.MAX_VALUE }
