package com.metrolist.innertubex.sabr

internal data class UmpPart(
    val type: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is UmpPart && type == other.type && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * type + data.contentHashCode()
}

/** Incremental reader for YouTube's UMP framing. */
internal class UmpReader(
    private val maxPartSize: Int = DEFAULT_MAX_PART_SIZE,
    private val maxPartSizeForType: (Int) -> Int = { maxPartSize },
) {
    private var buffer = ByteArray(0)
    private var start = 0
    private var end = 0
    private var streamingPartType = -1
    private var streamingRemainingBytes = 0

    fun reset() {
        start = 0
        end = 0
        streamingPartType = -1
        streamingRemainingBytes = 0
        if (buffer.size > MAX_RETAINED_BUFFER_SIZE) {
            buffer = ByteArray(0)
        }
    }

    fun feed(chunk: ByteArray): List<UmpPart> = feed(chunk, offset = 0, length = chunk.size)

    internal fun feed(
        chunk: ByteArray,
        offset: Int,
        length: Int,
    ): List<UmpPart> {
        val parts = mutableListOf<UmpPart>()
        feed(chunk, offset, length) { type, data, from, to ->
            parts += UmpPart(type, data.copyOfRange(from, to))
            true
        }
        return parts
    }

    /**
     * Calls [onPart] with each complete part's payload as `data[from, to)`, without copying it.
     * The range is only valid during the callback. Return false to stop before the next part.
     */
    internal fun feed(
        chunk: ByteArray,
        offset: Int,
        length: Int,
        onPart: (type: Int, data: ByteArray, from: Int, to: Int) -> Boolean,
    ) {
        feedStreaming(
            chunk = chunk,
            offset = offset,
            length = length,
            streamPartType = -1,
            onStreamStart = { _, _ -> true },
            onStreamChunk = { _, _, _ -> true },
            onStreamEnd = { true },
            onPart = onPart,
        )
    }

    /**
     * Parses UMP parts from `chunk[offset, offset + length)`, streaming parts of [streamPartType]
     * directly from input chunks without staging their full payload in [buffer].
     */
    internal fun feedStreaming(
        chunk: ByteArray,
        offset: Int,
        length: Int,
        streamPartType: Int,
        onStreamStart: (type: Int, size: Int) -> Boolean,
        onStreamChunk: (data: ByteArray, from: Int, to: Int) -> Boolean,
        onStreamEnd: () -> Boolean,
        onPart: (type: Int, data: ByteArray, from: Int, to: Int) -> Boolean,
    ) {
        require(offset >= 0 && length >= 0 && offset <= chunk.size - length) { "Invalid UMP input range" }
        var chunkPos = offset
        val chunkEnd = offset + length

        while (true) {
            // Drain an active streamed part from pending buffer bytes first, then directly from chunk.
            if (streamingPartType >= 0) {
                if (streamingRemainingBytes == 0) {
                    streamingPartType = -1
                    if (!onStreamEnd()) return
                    continue
                }
                if (pendingSize > 0) {
                    val take = minOf(streamingRemainingBytes, pendingSize)
                    val payloadStart = start
                    start += take
                    if (start == end) {
                        start = 0
                        end = 0
                    }
                    streamingRemainingBytes -= take
                    if (!onStreamChunk(buffer, payloadStart, payloadStart + take)) return
                    if (streamingRemainingBytes == 0) {
                        streamingPartType = -1
                        if (!onStreamEnd()) return
                    }
                    continue
                }
                val available = chunkEnd - chunkPos
                if (available == 0) return
                val take = minOf(streamingRemainingBytes, available)
                val payloadStart = chunkPos
                chunkPos += take
                streamingRemainingBytes -= take
                if (!onStreamChunk(chunk, payloadStart, payloadStart + take)) return
                if (streamingRemainingBytes == 0) {
                    streamingPartType = -1
                    if (!onStreamEnd()) return
                }
                continue
            }

            if (pendingSize == 0) {
                if (chunkPos >= chunkEnd) return
                val packedType = readUmpVarint(chunk, chunkPos, chunkEnd)
                if (packedType == INCOMPLETE_VARINT) {
                    appendToBuffer(chunk, chunkPos, chunkEnd - chunkPos)
                    return
                }
                val type = varintValue(packedType)
                val typeNext = varintNextOffset(packedType)
                val packedSize = readUmpVarint(chunk, typeNext, chunkEnd)
                if (packedSize == INCOMPLETE_VARINT) {
                    appendToBuffer(chunk, chunkPos, chunkEnd - chunkPos)
                    return
                }
                val size = varintValue(packedSize)
                val sizeNext = varintNextOffset(packedSize)
                validatePartSize(type, size)
                if (type == streamPartType) {
                    chunkPos = sizeNext
                    streamingPartType = type
                    streamingRemainingBytes = size
                    if (!onStreamStart(type, size)) return
                    if (size == 0) {
                        streamingPartType = -1
                        if (!onStreamEnd()) return
                    }
                    continue
                }
                val partEnd = sizeNext.toLong() + size
                if (partEnd <= chunkEnd) {
                    chunkPos = partEnd.toInt()
                    if (!onPart(type, chunk, sizeNext, partEnd.toInt())) return
                    continue
                }
                appendToBuffer(chunk, chunkPos, chunkEnd - chunkPos)
                return
            }

            // pendingSize > 0: finish parsing the straddling header or buffered part from buffer.
            val packedType = readUmpVarint(buffer, start, end)
            if (packedType == INCOMPLETE_VARINT) {
                if (chunkPos >= chunkEnd) return
                val copy = minOf(MAX_HEADER_BYTES - pendingSize.coerceAtMost(MAX_HEADER_BYTES - 1), chunkEnd - chunkPos)
                appendToBuffer(chunk, chunkPos, copy)
                chunkPos += copy
                continue
            }
            val type = varintValue(packedType)
            val typeNext = varintNextOffset(packedType)
            val packedSize = readUmpVarint(buffer, typeNext, end)
            if (packedSize == INCOMPLETE_VARINT) {
                if (chunkPos >= chunkEnd) return
                val copy = minOf(MAX_HEADER_BYTES - pendingSize.coerceAtMost(MAX_HEADER_BYTES - 1), chunkEnd - chunkPos)
                appendToBuffer(chunk, chunkPos, copy)
                chunkPos += copy
                continue
            }
            val size = varintValue(packedSize)
            val sizeNext = varintNextOffset(packedSize)
            validatePartSize(type, size)

            if (type == streamPartType) {
                start = sizeNext
                if (start == end) {
                    start = 0
                    end = 0
                }
                streamingPartType = type
                streamingRemainingBytes = size
                if (!onStreamStart(type, size)) return
                if (size == 0) {
                    streamingPartType = -1
                    if (!onStreamEnd()) return
                }
                continue
            }

            val partEnd = sizeNext.toLong() + size
            if (partEnd <= end) {
                val payloadEnd = partEnd.toInt()
                start = payloadEnd
                if (start == end) {
                    start = 0
                    end = 0
                }
                if (!onPart(type, buffer, sizeNext, payloadEnd)) return
                continue
            }

            if (chunkPos >= chunkEnd) return
            val needed = (partEnd - end).toInt()
            val copy = minOf(needed, chunkEnd - chunkPos)
            appendToBuffer(chunk, chunkPos, copy)
            chunkPos += copy
        }
    }

    fun finish() {
        val remaining = pendingSize + streamingRemainingBytes
        if (remaining > 0 || streamingPartType >= 0) {
            throw SabrProtocolException("Truncated UMP response ($remaining bytes remain)")
        }
    }

    private val pendingSize: Int
        get() = end - start

    private fun validatePartSize(
        type: Int,
        size: Int,
    ) {
        if (size > minOf(maxPartSize, maxPartSizeForType(type))) {
            throw SabrProtocolException("UMP part is too large: $size bytes")
        }
    }

    private fun appendToBuffer(
        source: ByteArray,
        offset: Int,
        count: Int,
    ) {
        if (count <= 0) return
        ensureCapacity(count)
        source.copyInto(buffer, destinationOffset = end, startIndex = offset, endIndex = offset + count)
        end += count
    }

    private fun ensureCapacity(additionalBytes: Int) {
        val required = pendingSize.toLong() + additionalBytes
        if (required > MAX_PENDING_BYTES) {
            throw SabrProtocolException("Incomplete UMP part exceeded the pending byte limit")
        }
        if (buffer.size - end >= additionalBytes) return
        if (start > 0 && buffer.size.toLong() >= required) {
            buffer.copyInto(buffer, destinationOffset = 0, startIndex = start, endIndex = end)
            end = pendingSize
            start = 0
            return
        }

        val doubledCapacity = buffer.size.coerceAtLeast(DEFAULT_BUFFER_SIZE).toLong() * 2
        val newCapacity = maxOf(required, doubledCapacity).coerceAtMost(MAX_PENDING_BYTES).toInt()
        val expanded = ByteArray(newCapacity)
        buffer.copyInto(expanded, destinationOffset = 0, startIndex = start, endIndex = end)
        end = pendingSize
        start = 0
        buffer = expanded
    }

    private fun readUmpVarint(
        data: ByteArray,
        offset: Int,
        limit: Int,
    ): Long {
        if (offset >= limit) return INCOMPLETE_VARINT
        val first = data[offset].toInt() and 0xff
        val byteLength =
            when {
                first < 0x80 -> 1
                first < 0xc0 -> 2
                first < 0xe0 -> 3
                first < 0xf0 -> 4
                else -> 5
            }
        if (limit - offset < byteLength) return INCOMPLETE_VARINT

        val value =
            when (byteLength) {
                1 -> {
                    first
                }

                2 -> {
                    (first and 0x3f) + 64 * (data[offset + 1].toInt() and 0xff)
                }

                3 -> {
                    (first and 0x1f) + 32 * ((data[offset + 1].toInt() and 0xff) + 256 * (data[offset + 2].toInt() and 0xff))
                }

                4 -> {
                    (first and 0x0f) +
                        16 * (
                            (data[offset + 1].toInt() and 0xff) +
                                256 * ((data[offset + 2].toInt() and 0xff) + 256 * (data[offset + 3].toInt() and 0xff))
                        )
                }

                else -> {
                    (data[offset + 1].toInt() and 0xff) +
                        256 * (
                            (data[offset + 2].toInt() and 0xff) +
                                256 * ((data[offset + 3].toInt() and 0xff) + 256 * (data[offset + 4].toInt() and 0xff))
                        )
                }
            }
        if (value < 0) throw SabrProtocolException("UMP varint exceeds Int.MAX_VALUE")
        return ((offset + byteLength).toLong() shl 32) or value.toLong()
    }

    private fun varintValue(packed: Long): Int = (packed and 0xffff_ffffL).toInt()

    private fun varintNextOffset(packed: Long): Int = (packed ushr 32).toInt()

    companion object {
        const val DEFAULT_MAX_PART_SIZE: Int = 8 * 1024 * 1024
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024
        private const val MAX_RETAINED_BUFFER_SIZE = 128 * 1024
        private const val MAX_HEADER_BYTES = 10
        private const val MAX_PENDING_BYTES: Long = 16L * 1024 * 1024
        private const val INCOMPLETE_VARINT = -1L
    }
}

internal object UmpPartType {
    const val MEDIA_HEADER = 20
    const val MEDIA = 21
    const val MEDIA_END = 22
    const val NEXT_REQUEST_POLICY = 35
    const val FORMAT_INITIALIZATION_METADATA = 42
    const val SABR_REDIRECT = 43
    const val SABR_ERROR = 44
    const val RELOAD_PLAYER_RESPONSE = 46
    const val SABR_CONTEXT_UPDATE = 57
    const val STREAM_PROTECTION_STATUS = 58
    const val SABR_CONTEXT_SENDING_POLICY = 59
    const val END_OF_TRACK = 62
}
