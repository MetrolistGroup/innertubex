package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AudioFormatSelectorTest {
    @Test
    fun prefersStereoAndHigherSampleRate() {
        val formats =
            listOf(
                Format(
                    itag = 251,
                    mimeType = "audio/webm; codecs=\"opus\"",
                    bitrate = 128_000,
                    audioChannels = 1,
                    audioSampleRate = 44_100,
                    url = "https://mono",
                ),
                Format(
                    itag = 250,
                    mimeType = "audio/webm; codecs=\"opus\"",
                    bitrate = 128_000,
                    audioChannels = 2,
                    audioSampleRate = 48_000,
                    url = "https://stereo",
                ),
            )

        assertEquals(250, selectBestAudioFormat(formats, AudioQuality.HIGH)?.itag)
        assertEquals(2, selectBestAudioFormat(formats, AudioQuality.HIGH)?.audioChannels)
    }

    @Test
    fun lowQualityPrefersLowestMp4ThenLowestBitrate() {
        val formats =
            listOf(
                Format(itag = 251, mimeType = "audio/webm", bitrate = 160_000, url = "https://opus"),
                Format(itag = 140, mimeType = "audio/mp4", bitrate = 128_000, url = "https://aac"),
                Format(itag = 250, mimeType = "audio/webm", bitrate = 70_000, url = "https://opus-low"),
            )

        assertEquals(140, selectBestAudioFormat(formats, AudioQuality.LOW)?.itag)
        assertEquals(250, selectBestAudioFormat(formats.filter { it.itag != 140 }, AudioQuality.LOW)?.itag)
    }

    @Test
    fun mp4RequiresAPlayerCompatibleFormat() {
        val formats =
            listOf(
                Format(itag = 251, mimeType = "audio/webm", bitrate = 160_000, url = "https://opus"),
                Format(itag = 140, mimeType = "audio/mp4", bitrate = 128_000, url = "https://aac"),
            )

        assertEquals(140, selectBestAudioFormat(formats, AudioQuality.MP4)?.itag)
        assertNull(selectBestAudioFormat(formats.take(1), AudioQuality.MP4))
    }

    @Test
    fun highQualityPrefersStereoBeforeBitrate() {
        val formats =
            listOf(
                Format(itag = 251, mimeType = "audio/webm", bitrate = 128_000, audioChannels = 1, url = "https://mono"),
                Format(itag = 250, mimeType = "audio/webm", bitrate = 96_000, audioChannels = 2, url = "https://stereo"),
            )

        assertEquals(250, selectBestAudioFormat(formats, AudioQuality.HIGH)?.itag)
    }

    @Test
    fun autoPrefersOpusAndUrlRequirementIsHonored() {
        val formats =
            listOf(
                Format(itag = 251, mimeType = "audio/webm", bitrate = 160_000),
                Format(itag = 140, mimeType = "audio/mp4", bitrate = 128_000, url = "https://aac"),
            )

        assertEquals(140, selectBestAudioFormat(formats, AudioQuality.HIGH)?.itag)
        assertEquals(251, selectBestAudioFormat(formats, AudioQuality.HIGH, requireUrl = false)?.itag)
        assertEquals(140, selectBestAudioFormat(formats, AudioQuality.AUTO)?.itag)
    }

    @Test
    fun autoFallsBackToBestNonWebmFormat() {
        val formats =
            listOf(
                Format(itag = 140, mimeType = "audio/mp4", bitrate = 128_000, url = "https://aac"),
                Format(itag = 141, mimeType = "audio/mp4", bitrate = 256_000, url = "https://aac-high"),
            )

        assertEquals(141, selectBestAudioFormat(formats, AudioQuality.AUTO)?.itag)
    }

    @Test
    fun emptyFormatsReturnNull() {
        assertNull(selectBestAudioFormat(emptyList(), AudioQuality.AUTO))
        assertNotNull(selectBestAudioFormat(listOf(Format(itag = 1, mimeType = "audio/mp4", bitrate = 1, url = "https://audio"))))
    }
}
