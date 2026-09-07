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
    return withinCap.maxWithOrNull(compareBy(::videoFormatScore))
        ?: videoFormats
            .groupBy { it.height!! }
            .minByOrNull { it.key }
            ?.value
            ?.maxWithOrNull(compareBy(::videoFormatScore))
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

private fun videoFormatScore(format: Format): Long {
    val codec = format.mimeType.lowercase()
    val codecRank =
        when {
            "vp09" in codec || "vp9" in codec || "video/webm" in codec -> 3L
            "avc1" in codec || "video/mp4" in codec -> 2L
            "av01" in codec -> 1L
            else -> 0L
        }
    return (format.height ?: 0).toLong() * 1_000_000_000_000L + codecRank * 1_000_000_000L + format.bitrate
}
