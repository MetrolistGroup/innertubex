package com.metrolist.innertubex.extraction

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

internal enum class PlayerJsonInspection {
    CLEAN,
    DRM_DETECTED,
    UNBOUNDED,
}

/**
 * Single-pass, DOM-free structural bound and DRM marker scanner for `/player` JSON payloads.
 * Preserves the depth/entry limits and exact `streamingData` / format DRM semantics (including
 * duplicate-key overwrite and first-match case-insensitive precedence) without materializing a
 * `JsonElement` tree or allocating lowercase key copies on clear responses.
 */
internal object PlayerResponseJsonScanner {
    private const val MAX_DEPTH = 64
    private const val MAX_ENTRIES = 65_536

    @OptIn(ExperimentalSerializationApi::class)
    private val STRING_DECODER = Json { exceptionsWithDebugInfo = false }

    private val KNOWN_DRM_KEYS =
        arrayOf(
            "contentprotection",
            "contentprotectionids",
            "drmfamilies",
            "drmfamily",
            "drmparameters",
            "drmparams",
            "drmtracktype",
            "fairplay",
            "licenseinfo",
            "licenseinfos",
            "licenseurl",
            "playready",
            "widevine",
        )

    fun inspect(payload: String): PlayerJsonInspection = Scanner(payload).inspect()

    private class Scanner(
        private val payload: String,
    ) {
        private val length = payload.length
        private var pos = 0
        private var depth = 0
        private var entries = 0
        private var unbounded = false
        private var malformed = false

        fun inspect(): PlayerJsonInspection {
            var drmDetected = false
            skipWhitespace()
            if (pos >= length) {
                malformed = true
            } else {
                drmDetected =
                    if (payload[pos] == '{') {
                        scanRootObject()
                    } else {
                        scanIgnoredValue()
                        false
                    }
                if (!unbounded && !malformed) {
                    skipWhitespace()
                    if (pos < length) malformed = true
                }
            }
            if (unbounded) return PlayerJsonInspection.UNBOUNDED
            if (malformed) {
                return if (hasBoundedRawStructure(payload)) PlayerJsonInspection.CLEAN else PlayerJsonInspection.UNBOUNDED
            }
            return if (drmDetected) PlayerJsonInspection.DRM_DETECTED else PlayerJsonInspection.CLEAN
        }

        private fun scanRootObject(): Boolean {
            if (!enterContainer('{')) return false
            if (payload[pos] == '}') {
                pos++
                depth--
                return false
            }
            var streamingDataDrm = false
            while (true) {
                val (keyStart, keyEnd, hasEscape) = readObjectKey()
                if (malformed || unbounded) return false
                val isStreamingData = keyEqualsExact(keyStart, keyEnd, hasEscape, "streamingData")
                skipWhitespace()
                if (pos >= length) {
                    malformed = true
                    return false
                }
                if (isStreamingData) {
                    streamingDataDrm =
                        if (payload[pos] == '{') {
                            scanStreamingDataObject()
                        } else {
                            scanIgnoredValue()
                            false
                        }
                } else {
                    scanIgnoredValue()
                }
                if (unbounded || malformed) return false
                if (!advanceObjectSeparator()) return streamingDataDrm
            }
        }

        private fun scanStreamingDataObject(): Boolean {
            if (!enterContainer('{')) return false
            if (payload[pos] == '}') {
                pos++
                depth--
                return false
            }
            var ownMarkers: LinkedHashMap<String, Boolean>? = null
            var formatsDrm = false
            var adaptiveFormatsDrm = false
            while (true) {
                val (keyStart, keyEnd, hasEscape) = readObjectKey()
                if (malformed || unbounded) return false
                skipWhitespace()
                if (pos >= length) {
                    malformed = true
                    return false
                }
                when {
                    keyEqualsExact(keyStart, keyEnd, hasEscape, "formats") -> {
                        formatsDrm =
                            if (payload[pos] == '[') {
                                scanFormatArray()
                            } else {
                                scanIgnoredValue()
                                false
                            }
                    }

                    keyEqualsExact(keyStart, keyEnd, hasEscape, "adaptiveFormats") -> {
                        adaptiveFormatsDrm =
                            if (payload[pos] == '[') {
                                scanFormatArray()
                            } else {
                                scanIgnoredValue()
                                false
                            }
                    }

                    matchesKnownDrmKey(keyStart, keyEnd, hasEscape) -> {
                        val exactKey = extractExactKey(keyStart, keyEnd, hasEscape) ?: return false
                        val truthy = scanDrmValue()
                        if (unbounded || malformed) return false
                        val markers = ownMarkers ?: LinkedHashMap<String, Boolean>(4).also { ownMarkers = it }
                        markers[exactKey] = truthy
                    }

                    else -> {
                        scanIgnoredValue()
                    }
                }
                if (unbounded || malformed) return false
                if (!advanceObjectSeparator()) {
                    return markersHaveDrm(ownMarkers) || formatsDrm || adaptiveFormatsDrm
                }
            }
        }

        private fun scanFormatArray(): Boolean {
            if (!enterContainer('[')) return false
            if (payload[pos] == ']') {
                pos++
                depth--
                return false
            }
            var anyFormatDrm = false
            while (true) {
                skipWhitespace()
                if (pos >= length) {
                    malformed = true
                    return false
                }
                if (payload[pos] == '{') {
                    if (scanFormatObject()) anyFormatDrm = true
                } else {
                    scanIgnoredValue()
                }
                if (unbounded || malformed) return false
                if (!advanceArraySeparator()) return anyFormatDrm
            }
        }

        private fun scanFormatObject(): Boolean {
            if (!enterContainer('{')) return false
            if (payload[pos] == '}') {
                pos++
                depth--
                return false
            }
            var ownMarkers: LinkedHashMap<String, Boolean>? = null
            while (true) {
                val (keyStart, keyEnd, hasEscape) = readObjectKey()
                if (malformed || unbounded) return false
                skipWhitespace()
                if (pos >= length) {
                    malformed = true
                    return false
                }
                if (matchesKnownDrmKey(keyStart, keyEnd, hasEscape)) {
                    val exactKey = extractExactKey(keyStart, keyEnd, hasEscape) ?: return false
                    val truthy = scanDrmValue()
                    if (unbounded || malformed) return false
                    val markers = ownMarkers ?: LinkedHashMap<String, Boolean>(4).also { ownMarkers = it }
                    markers[exactKey] = truthy
                } else {
                    scanIgnoredValue()
                }
                if (unbounded || malformed) return false
                if (!advanceObjectSeparator()) return markersHaveDrm(ownMarkers)
            }
        }

        private fun scanDrmValue(): Boolean {
            skipWhitespace()
            if (pos >= length) {
                malformed = true
                return false
            }
            when (payload[pos]) {
                '{' -> {
                    if (!enterContainer('{')) return false
                    if (payload[pos] == '}') {
                        pos++
                        depth--
                        return false
                    }
                    // Non-empty object matches JsonObject.isNotEmpty() == true.
                    while (true) {
                        readObjectKey()
                        if (malformed || unbounded) return false
                        scanIgnoredValue()
                        if (unbounded || malformed) return false
                        if (!advanceObjectSeparator()) return true
                    }
                }

                '[' -> {
                    if (!enterContainer('[')) return false
                    if (payload[pos] == ']') {
                        pos++
                        depth--
                        return false
                    }
                    var anyTruthy = false
                    while (true) {
                        skipWhitespace()
                        if (pos >= length) {
                            malformed = true
                            return false
                        }
                        when (payload[pos]) {
                            '{', '[' -> {
                                // Matches hasDrmValue: any JsonObject or JsonArray item inside an array is truthy.
                                anyTruthy = true
                                scanIgnoredValue()
                            }

                            '"' -> {
                                if (scanStringValue(checkDrm = true)) anyTruthy = true
                            }

                            else -> {
                                if (scanPrimitiveValue(checkDrm = true)) anyTruthy = true
                            }
                        }
                        if (unbounded || malformed) return false
                        if (!advanceArraySeparator()) return anyTruthy
                    }
                }

                '"' -> {
                    return scanStringValue(checkDrm = true)
                }

                else -> {
                    return scanPrimitiveValue(checkDrm = true)
                }
            }
        }

        private fun scanIgnoredValue() {
            skipWhitespace()
            if (pos >= length) {
                malformed = true
                return
            }
            when (payload[pos]) {
                '{' -> {
                    if (!enterContainer('{')) return
                    if (payload[pos] == '}') {
                        pos++
                        depth--
                        return
                    }
                    while (true) {
                        readObjectKey()
                        if (malformed || unbounded) return
                        scanIgnoredValue()
                        if (unbounded || malformed) return
                        if (!advanceObjectSeparator()) return
                    }
                }

                '[' -> {
                    if (!enterContainer('[')) return
                    if (payload[pos] == ']') {
                        pos++
                        depth--
                        return
                    }
                    while (true) {
                        scanIgnoredValue()
                        if (unbounded || malformed) return
                        if (!advanceArraySeparator()) return
                    }
                }

                '"' -> {
                    scanStringValue(checkDrm = false)
                }

                else -> {
                    scanPrimitiveValue(checkDrm = false)
                }
            }
        }

        private fun enterContainer(openChar: Char): Boolean {
            if (payload[pos] != openChar) {
                malformed = true
                return false
            }
            if (++depth > MAX_DEPTH || ++entries > MAX_ENTRIES) {
                unbounded = true
                return false
            }
            pos++
            skipWhitespace()
            if (pos >= length) {
                malformed = true
                return false
            }
            return true
        }

        private fun advanceObjectSeparator(): Boolean {
            skipWhitespace()
            if (pos >= length) {
                malformed = true
                return false
            }
            return when (payload[pos]) {
                ',' -> {
                    if (++entries > MAX_ENTRIES) {
                        unbounded = true
                        return false
                    }
                    pos++
                    true
                }

                '}' -> {
                    pos++
                    depth--
                    false
                }

                else -> {
                    malformed = true
                    false
                }
            }
        }

        private fun advanceArraySeparator(): Boolean {
            skipWhitespace()
            if (pos >= length) {
                malformed = true
                return false
            }
            return when (payload[pos]) {
                ',' -> {
                    if (++entries > MAX_ENTRIES) {
                        unbounded = true
                        return false
                    }
                    pos++
                    true
                }

                ']' -> {
                    pos++
                    depth--
                    false
                }

                else -> {
                    malformed = true
                    false
                }
            }
        }

        private data class KeySpan(
            val start: Int,
            val end: Int,
            val hasEscape: Boolean,
        )

        private fun readObjectKey(): KeySpan {
            skipWhitespace()
            if (pos >= length || payload[pos] != '"') {
                malformed = true
                return KeySpan(0, 0, false)
            }
            val keyStart = pos
            val hasEscape = scanStringEnd()
            if (malformed) return KeySpan(0, 0, false)
            val keyEnd = pos
            skipWhitespace()
            if (pos >= length || payload[pos] != ':') {
                malformed = true
                return KeySpan(0, 0, false)
            }
            if (++entries > MAX_ENTRIES) {
                unbounded = true
                return KeySpan(0, 0, false)
            }
            pos++
            return KeySpan(keyStart, keyEnd, hasEscape)
        }

        private fun scanStringValue(checkDrm: Boolean): Boolean {
            val start = pos
            val hasEscape = scanStringEnd()
            if (malformed) return false
            return checkDrm && isDrmTruthyString(start, pos, hasEscape)
        }

        private fun scanPrimitiveValue(checkDrm: Boolean): Boolean {
            val start = pos
            while (pos < length) {
                when (payload[pos]) {
                    ' ', '\t', '\r', '\n', ',', '}', ']' -> break
                    else -> pos++
                }
            }
            val tokenLen = pos - start
            if (tokenLen == 0) {
                malformed = true
                return false
            }
            val first = payload[start]
            val validLiteral =
                when (first) {
                    't' -> tokenLen == 4 && payload.regionMatches(start, "true", 0, 4)
                    'f' -> tokenLen == 5 && payload.regionMatches(start, "false", 0, 5)
                    'n' -> tokenLen == 4 && payload.regionMatches(start, "null", 0, 4)
                    '-', in '0'..'9' -> true
                    else -> false
                }
            if (!validLiteral) {
                malformed = true
                return false
            }
            if (!checkDrm) return false
            val isFalsey = first == 'n' || first == 'f' || (tokenLen == 1 && first == '0')
            return !isFalsey
        }

        /** Advances [pos] past the closing quote of a JSON string starting at [pos]; returns true if any escape was seen. */
        private fun scanStringEnd(): Boolean {
            pos++
            var hasEscape = false
            while (pos < length) {
                when (payload[pos++]) {
                    '\\' -> {
                        hasEscape = true
                        if (pos >= length) {
                            malformed = true
                            return true
                        }
                        pos++
                    }

                    '"' -> {
                        return hasEscape
                    }
                }
            }
            malformed = true
            return hasEscape
        }

        private fun keyEqualsExact(
            quoteStart: Int,
            quoteEndExclusive: Int,
            hasEscape: Boolean,
            expected: String,
        ): Boolean {
            if (!hasEscape) {
                val contentStart = quoteStart + 1
                val contentLen = quoteEndExclusive - 1 - contentStart
                return contentLen == expected.length && payload.regionMatches(contentStart, expected, 0, contentLen)
            }
            val decoded = decodeJsonString(quoteStart, quoteEndExclusive) ?: return false
            return decoded == expected
        }

        private fun matchesKnownDrmKey(
            quoteStart: Int,
            quoteEndExclusive: Int,
            hasEscape: Boolean,
        ): Boolean {
            if (!hasEscape) {
                val contentStart = quoteStart + 1
                val contentLen = quoteEndExclusive - 1 - contentStart
                // All 13 known DRM markers have lengths between 8 ("widevine") and 20 ("contentprotectionids").
                if (contentLen !in 8..20) return false
                for (key in KNOWN_DRM_KEYS) {
                    if (contentLen == key.length && payload.regionMatches(contentStart, key, 0, contentLen, ignoreCase = true)) {
                        return true
                    }
                }
                return false
            }
            val decoded = decodeJsonString(quoteStart, quoteEndExclusive) ?: return false
            if (decoded.length !in 8..20) return false
            for (key in KNOWN_DRM_KEYS) {
                if (decoded.equals(key, ignoreCase = true)) return true
            }
            return false
        }

        private fun extractExactKey(
            quoteStart: Int,
            quoteEndExclusive: Int,
            hasEscape: Boolean,
        ): String? =
            if (!hasEscape) {
                payload.substring(quoteStart + 1, quoteEndExclusive - 1)
            } else {
                decodeJsonString(quoteStart, quoteEndExclusive)
            }

        private fun markersHaveDrm(markers: LinkedHashMap<String, Boolean>?): Boolean {
            if (markers == null || markers.isEmpty()) return false
            for (known in KNOWN_DRM_KEYS) {
                for ((key, truthy) in markers) {
                    if (key.equals(known, ignoreCase = true)) {
                        if (truthy) return true
                        break
                    }
                }
            }
            return false
        }

        private fun isDrmTruthyString(
            quoteStart: Int,
            quoteEndExclusive: Int,
            hasEscape: Boolean,
        ): Boolean {
            if (!hasEscape) {
                val contentStart = quoteStart + 1
                val contentEnd = quoteEndExclusive - 1
                val contentLen = contentEnd - contentStart
                if (contentLen == 0) return false
                if (contentLen == 1 && payload[contentStart] == '0') return false
                if (contentLen == 5 && payload.regionMatches(contentStart, "false", 0, 5)) return false
                for (i in contentStart until contentEnd) {
                    if (!payload[i].isWhitespace()) return true
                }
                return false
            }
            val decoded = decodeJsonString(quoteStart, quoteEndExclusive) ?: return false
            return decoded.isNotBlank() && decoded != "false" && decoded != "0"
        }

        private fun decodeJsonString(
            quoteStart: Int,
            quoteEndExclusive: Int,
        ): String? =
            runCatching {
                STRING_DECODER.decodeFromString<String>(payload.substring(quoteStart, quoteEndExclusive))
            }.getOrElse {
                malformed = true
                null
            }

        private fun skipWhitespace() {
            while (pos < length) {
                when (payload[pos]) {
                    ' ', '\t', '\r', '\n' -> pos++
                    else -> return
                }
            }
        }
    }

    private fun hasBoundedRawStructure(payload: String): Boolean {
        var depth = 0
        var entries = 0
        var quoted = false
        var escaped = false
        for (character in payload) {
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else {
                    when (character) {
                        '\\' -> escaped = true
                        '"' -> quoted = false
                    }
                }
            } else {
                when (character) {
                    '"' -> {
                        quoted = true
                    }

                    '{', '[' -> {
                        if (++depth > MAX_DEPTH || ++entries > MAX_ENTRIES) return false
                    }

                    '}', ']' -> {
                        depth--
                    }

                    ',', ':' -> {
                        if (++entries > MAX_ENTRIES) return false
                    }
                }
            }
        }
        return true
    }
}
