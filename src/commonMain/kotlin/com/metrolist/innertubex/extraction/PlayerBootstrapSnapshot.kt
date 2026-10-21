package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.cipher.validatedPlayerScriptUrl
import com.metrolist.innertubex.models.YouTubeLocale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The public part of a signed-out watch config, persisted so the first cipher-dependent extraction after a restart
 * can skip the watch-page round-trip. Holds no visitor data, cookies, tokens, embed flags or media URLs.
 */
@Serializable
internal data class PlayerBootstrapSnapshot(
    val version: Int = VERSION,
    val playerUrl: String,
    val signatureTimestamp: Int? = null,
    val clientVersion: String? = null,
    val hl: String,
    val gl: String,
    val savedAtMs: Long,
) {
    /** The restored config for [locale], or null when stale, from another locale, or not a valid player URL. */
    fun toPlayerConfig(
        locale: YouTubeLocale,
        nowMs: Long,
        maxAgeMs: Long,
    ): PlayerConfig? {
        if (version != VERSION || hl != locale.hl || gl != locale.gl) return null
        if (savedAtMs > nowMs || nowMs - savedAtMs > maxAgeMs) return null
        if (signatureTimestamp != null && signatureTimestamp <= 0) return null
        if (clientVersion != null && !CLIENT_VERSION_REGEX.matches(clientVersion)) return null
        if (runCatching { validatedPlayerScriptUrl(playerUrl) }.isFailure) return null
        return PlayerConfig(playerUrl, signatureTimestamp, visitorData = null, clientVersion = clientVersion)
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private const val VERSION = 1
        private const val MAX_ENCODED_LENGTH = 4 * 1024
        private val CLIENT_VERSION_REGEX = Regex("[0-9][0-9.]{0,31}")
        private val JSON = Json { ignoreUnknownKeys = true }

        fun of(
            config: PlayerConfig,
            locale: YouTubeLocale,
            nowMs: Long,
        ): PlayerBootstrapSnapshot? =
            config.playerUrl.takeIf(String::isNotBlank)?.let {
                PlayerBootstrapSnapshot(
                    playerUrl = it,
                    signatureTimestamp = config.signatureTimestamp,
                    clientVersion = config.clientVersion,
                    hl = locale.hl,
                    gl = locale.gl,
                    savedAtMs = nowMs,
                )
            }

        fun decode(value: String?): PlayerBootstrapSnapshot? =
            value
                ?.takeIf { it.length <= MAX_ENCODED_LENGTH }
                ?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
