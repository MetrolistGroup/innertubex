package com.metrolist.innertubex.extraction.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val WEB_PAGE_LIMIT = 4 * 1024 * 1024
private const val IDENTIFIER_LIMIT = 1024
private const val JSON_FIELD_LIMIT = 256

private val YT_CONFIG_SET = Regex("ytcfg\\s*\\.\\s*set")

private val YT_AT_N = Regex("window\\s*\\.\\s*ytAtN")

private val CHALLENGE_KEY = Regex("(?:[\\\"']R[\\\"']|\\bR)\\s*:")

private val YT_AT_R = Regex("window\\s*\\.\\s*ytAtR")

/**
 * Sensitive BotGuard page data. Do not log this value: it contains an event ID and raw challenge data.
 */
public class WebPageAttestationContext(
    public val challenge: String,
    public val eventId: String,
) {
    override fun toString(): String = "WebPageAttestationContext(challenge=${challenge.length} chars, eventId=${eventId.length} chars)"
}

/** Returns the paired page challenge and event ID, or null when the page is not usable. */
public fun extractWebPageAttestationContext(webPage: String): WebPageAttestationContext? {
    requireSize(webPage, WEB_PAGE_LIMIT, "web page")
    val challenge = extractWebPageAttestationChallenge(webPage) ?: return null
    val eventId = extractYtConfigEventId(webPage) ?: return null
    return WebPageAttestationContext(challenge = challenge, eventId = eventId)
}

/** Returns the BotGuard challenge from a page, or null when it cannot be extracted. */
public fun extractWebPageAttestationChallenge(webPage: String): String? {
    requireSize(webPage, WEB_PAGE_LIMIT, "web page")
    val rawChallengeData = extractYtAtN(webPage) ?: extractYtAtR(webPage) ?: return null
    val challenge =
        runCatching {
            val root = Json.parseToJsonElement(rawChallengeData).jsonObject
            requireFieldCount(root)
            root["bgChallenge"] ?: return@runCatching null
        }.getOrNull() ?: return null
    return Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("bgChallenge" to challenge)))
}

private fun extractYtConfigEventId(webPage: String): String? {
    var position = 0
    while (position < webPage.length) {
        val call = YT_CONFIG_SET.find(webPage, position) ?: break
        val openParenthesis = webPage.indexOf('(', call.range.last + 1)
        if (openParenthesis < 0) break
        val scan = extractJavaScriptObject(webPage, openParenthesis + 1)
        position = scan.end
        val rawConfig = scan.value ?: continue
        val eventId =
            runCatching {
                val config = Json.parseToJsonElement(rawConfig).jsonObject
                requireFieldCount(config)
                config["EVENT_ID"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        if (!eventId.isNullOrBlank() && eventId.length <= IDENTIFIER_LIMIT) return eventId
    }
    return null
}

internal fun extractYtAtN(webPage: CharSequence): String? {
    var position = 0
    while (position < webPage.length) {
        val call = YT_AT_N.find(webPage, position) ?: break
        val scan = extractYtAtNCall(webPage, call.range.last + 1)
        scan.value?.let { return it }
        // Never retry a prefix inside the suffix we have already scanned.
        position = scan.end
    }
    return null
}

private data class JavaScriptScan(
    val value: String?,
    val end: Int,
)

private fun extractYtAtNCall(
    webPage: CharSequence,
    callEnd: Int,
): JavaScriptScan {
    val openParenthesis = webPage.indexOf('(', callEnd)
    if (openParenthesis < 0) return JavaScriptScan(null, webPage.length)
    var position = openParenthesis + 1
    var quote: Char? = null
    var escaped = false
    while (position < webPage.length) {
        val char = webPage[position]
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                quote = null
            }
        } else {
            when (char) {
                '\'', '"' -> quote = char
                '{' -> break
                ')' -> return JavaScriptScan(null, position + 1)
            }
        }
        position++
    }
    if (position >= webPage.length) return JavaScriptScan(null, webPage.length)
    val scan = extractJavaScriptObject(webPage, position)
    return scan.copy(value = scan.value?.let(::extractYtAtNRawChallenge))
}

private fun extractJavaScriptObject(
    source: CharSequence,
    start: Int,
): JavaScriptScan {
    var position = start
    while (position < source.length && source[position].isWhitespace()) position++
    if (position >= source.length || source[position] != '{') return JavaScriptScan(null, position)
    val objectStart = position
    var depth = 0
    var quote: Char? = null
    var escaped = false
    while (position < source.length) {
        val char = source[position++]
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                quote = null
            }
            continue
        }
        when (char) {
            '\'', '"' -> {
                quote = char
            }

            '{' -> {
                depth++
            }

            '}' -> {
                depth--
                if (depth == 0) return JavaScriptScan(source.subSequence(objectStart, position).toString(), position)
            }
        }
    }
    return JavaScriptScan(null, source.length)
}

private fun extractYtAtNRawChallenge(wrapper: String): String? {
    val key = CHALLENGE_KEY.find(wrapper) ?: return null
    var position = key.range.last + 1
    while (position < wrapper.length && wrapper[position].isWhitespace()) position++
    return when (wrapper.getOrNull(position)) {
        '\'', '"' -> decodeJavaScriptString(wrapper, position)
        '{' -> extractJavaScriptObject(wrapper, position).value
        else -> null
    }
}

private fun extractYtAtR(webPage: String): String? {
    val assignment = YT_AT_R.find(webPage) ?: return null
    val equals = webPage.indexOf('=', assignment.range.last + 1)
    if (equals < 0) return null
    var position = equals + 1
    while (position < webPage.length && webPage[position].isWhitespace()) position++
    return decodeJavaScriptString(webPage, position)
}

private fun decodeJavaScriptString(
    source: String,
    start: Int,
): String? {
    var position = start
    if (position >= source.length || source[position] !in charArrayOf('\'', '"')) return null
    val quote = source[position++]
    val decoded = StringBuilder()
    while (position < source.length) {
        val char = source[position++]
        if (char == quote) return decoded.toString()
        if (char != '\\') {
            decoded.append(char)
            continue
        }
        if (position >= source.length) return null
        when (val escaped = source[position++]) {
            '\'', '"', '\\', '/' -> {
                decoded.append(escaped)
            }

            'b' -> {
                decoded.append('\b')
            }

            'f' -> {
                decoded.append('\u000c')
            }

            'n' -> {
                decoded.append('\n')
            }

            'r' -> {
                decoded.append('\r')
            }

            't' -> {
                decoded.append('\t')
            }

            'u', 'x' -> {
                val digits = if (escaped == 'u') 4 else 2
                if (position + digits > source.length) return null
                val codePoint = source.substring(position, position + digits).toIntOrNull(16) ?: return null
                decoded.append(codePoint.toChar())
                position += digits
            }

            '\n' -> {
                continue
            }

            else -> {
                decoded.append(escaped)
            }
        }
    }
    return null
}

internal fun requireSize(
    value: String,
    limit: Int,
    name: String,
) {
    if (value.length > limit) throw PoTokenException("$name exceeds its size limit")
}

internal fun requireFieldCount(value: JsonObject) {
    if (value.size > JSON_FIELD_LIMIT) throw PoTokenException("JSON object has too many fields")
}
