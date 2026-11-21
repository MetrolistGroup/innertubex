package com.metrolist.innertubex

import com.metrolist.innertubex.cipher.getTextWithoutRedirects
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.GetTranscriptResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

public data class TranscriptCue(
    val text: String,
    val startMs: Long,
    val durationMs: Long,
) {
    override fun toString(): String =
        "TranscriptCue(text=${if (text.isBlank()) "missing" else "present"}, startMs=$startMs, durationMs=$durationMs)"
}

/** Decodes a bounded JSON object without relying on an unbounded content-negotiation read. */
public suspend fun HttpResponse.bodyAsJsonObjectLimited(maxBytes: Int): JsonObject =
    decodeResponseJson { RESPONSE_JSON.parseToJsonElement(bodyAsTextLimited(maxBytes)).jsonObject }

/** Requests and parses transcript cues with bounded response, cue-count, and cue-text allocations. */
public suspend fun InnerTube.transcriptCues(
    client: YouTubeClient,
    videoId: String,
): List<TranscriptCue> {
    require(SAFE_VIDEO_ID.matches(videoId)) { "Invalid video ID" }
    val response = getTranscript(client, videoId)
    // Decoding and mapping a long transcript is CPU work; keep it off the caller's (possibly main) dispatcher.
    return withContext(Dispatchers.Default) { parseTranscriptCues(response.bodyAsTextLimited(MAX_TRANSCRIPT_BYTES)) }
}

private fun parseTranscriptCues(body: String): List<TranscriptCue> {
    val transcript = decodeResponseJson { RESPONSE_JSON.decodeFromString<GetTranscriptResponse>(body) }
    val groups =
        transcript.actions
            ?.firstNotNullOfOrNull { action ->
                action.updateEngagementPanelAction
                    ?.content
                    ?.transcriptRenderer
                    ?.body
                    ?.transcriptBodyRenderer
            }?.cueGroups
            .orEmpty()
    require(groups.size <= MAX_TRANSCRIPT_CUES) { "Transcript cue count exceeded the supported limit" }
    val cues = ArrayList<TranscriptCue>()
    for (group in groups) {
        for (entry in group.transcriptCueGroupRenderer?.cues.orEmpty()) {
            val cue = entry.transcriptCueRenderer ?: continue
            val text =
                cue.cue
                    ?.simpleText
                    ?.trim('\u266a')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_TRANSCRIPT_CUE_TEXT_LENGTH }
                    ?: continue
            val startMs = cue.startOffsetMs ?: continue
            require(cues.size < MAX_TRANSCRIPT_CUES) { "Transcript cue count exceeded the supported limit" }
            cues += TranscriptCue(text = text, startMs = startMs, durationMs = cue.durationMs ?: 0L)
        }
    }
    return cues
}

/** Fetches a public YouTube caption track without redirects, cookies, or account headers. */
public suspend fun InnerTube.fetchCaptionText(url: String): String {
    val captionUrl = validatedCaptionUrl(url)
    val response =
        try {
            httpClient.getTextWithoutRedirects(captionUrl, MAX_CAPTION_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalStateException("Caption request failed")
        }
    if (!response.status.isSuccess()) {
        throw InnerTubeHttpException("fetchCaptionText", response.status)
    }
    return checkNotNull(response.body)
}

private fun validatedCaptionUrl(value: String): Url {
    val url = Url(value)
    val validEndpoint =
        when (url.host) {
            "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com" -> {
                url.encodedPath == "/api/timedtext"
            }

            "video.google.com" -> {
                url.encodedPath == "/timedtext"
            }

            else -> {
                false
            }
        }
    require(url.protocol.name == "https" && url.port == 443 && validEndpoint && url.user == null && url.password == null) {
        "Caption URL must use an approved YouTube HTTPS endpoint"
    }
    return url
}

private inline fun <T> decodeResponseJson(block: () -> T): T =
    try {
        block()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid response JSON")
    }

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val RESPONSE_JSON =
    Json {
        ignoreUnknownKeys = true
        exceptionsWithDebugInfo = false
    }
private val SAFE_VIDEO_ID = Regex("[A-Za-z0-9_-]{1,64}")
private const val MAX_TRANSCRIPT_BYTES = 4 * 1024 * 1024
private const val MAX_TRANSCRIPT_CUES = 20_000
private const val MAX_TRANSCRIPT_CUE_TEXT_LENGTH = 16 * 1024
private const val MAX_CAPTION_BYTES = 4 * 1024 * 1024
