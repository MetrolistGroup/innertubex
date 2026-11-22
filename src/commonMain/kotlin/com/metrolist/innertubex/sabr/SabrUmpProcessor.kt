package com.metrolist.innertubex.sabr

/** Converts framed UMP parts into complete media segments and session-control events. */
internal class SabrUmpProcessor(
    maxPartSize: Int = UmpReader.DEFAULT_MAX_PART_SIZE,
    private val shouldCollectMedia: (SabrMediaHeader) -> Boolean = { true },
    private val maxCollectedSegmentSize: Int = DEFAULT_MAX_COLLECTED_SEGMENT_SIZE,
    private val maxPendingMediaBytes: Int = DEFAULT_MAX_PENDING_MEDIA_BYTES,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val reader =
        UmpReader(maxPartSize) { type ->
            if (type == UmpPartType.SABR_CONTEXT_UPDATE || type == UmpPartType.NEXT_REQUEST_POLICY) {
                MAX_CONTROL_PART_BYTES
            } else {
                maxPartSize
            }
        }
    private val pendingSegments = mutableMapOf<Int, PendingSegment>()
    internal val retainedMediaBufferCount: Int
        get() = pendingSegments.values.count { it.bytes != null }

    private var pendingMediaBytes = 0L
    private var responseBytes = 0L
    private var responseHeaderCount = 0
    private var terminalProtection = false
    private var activeMediaSegment: PendingSegment? = null
    private var expectingMediaHeaderId = false

    internal fun resetForNextResponse() {
        reader.reset()
        pendingSegments.clear()
        pendingMediaBytes = 0L
        responseBytes = 0L
        responseHeaderCount = 0
        terminalProtection = false
        activeMediaSegment = null
        expectingMediaHeaderId = false
    }

    fun feed(chunk: ByteArray): List<SabrEvent> = feed(chunk, chunk.size)

    internal fun feed(
        chunk: ByteArray,
        length: Int,
    ): List<SabrEvent> {
        if (terminalProtection) return emptyList()
        val events = mutableListOf<SabrEvent>()
        feed(chunk, length) { events += it }
        return events
    }

    internal fun feed(
        chunk: ByteArray,
        length: Int,
        onEvent: (SabrEvent) -> Unit,
    ) {
        if (terminalProtection) return
        accountResponseBytes(length)
        reader.feedStreaming(
            chunk = chunk,
            offset = 0,
            length = length,
            streamPartType = UmpPartType.MEDIA,
            onStreamStart = { _, size ->
                if (size == 0) throw SabrProtocolException("Empty UMP MEDIA part")
                activeMediaSegment = null
                expectingMediaHeaderId = true
                true
            },
            onStreamChunk = { data, from, to ->
                appendMediaSlice(data, from, to)
                true
            },
            onStreamEnd = {
                activeMediaSegment = null
                expectingMediaHeaderId = false
                true
            },
            onPart = { type, data, from, to ->
                val event = process(type, data, from, to)
                if (event != null) {
                    onEvent(event)
                    if (event is SabrEvent.StreamProtectionStatus && event.status >= 3) {
                        terminalProtection = true
                    }
                }
                !terminalProtection
            },
        )
    }

    private fun accountResponseBytes(bytes: Int) {
        require(bytes >= 0) { "Response byte count must not be negative" }
        if (responseBytes > maxResponseBytes - bytes) {
            throw SabrProtocolException("SABR response exceeded $maxResponseBytes bytes")
        }
        responseBytes += bytes
    }

    fun finish() {
        if (terminalProtection) return
        reader.finish()
        if (pendingSegments.isNotEmpty()) {
            throw SabrProtocolException(
                "SABR response ended with incomplete media headers: ${pendingSegments.keys.sorted().joinToString()}",
            )
        }
    }

    private fun process(
        type: Int,
        data: ByteArray,
        from: Int,
        to: Int,
    ): SabrEvent? =
        when (type) {
            UmpPartType.MEDIA_HEADER -> {
                if (++responseHeaderCount > MAX_RESPONSE_HEADERS) {
                    throw SabrProtocolException("SABR response exceeded the media header limit")
                }
                val header = SabrProtoCodec.decodeMediaHeader(data, from, to)
                if (pendingSegments.containsKey(header.headerId)) {
                    throw SabrProtocolException("Duplicate SABR media header ID ${header.headerId}")
                }
                val collect = shouldCollectMedia(header)
                if (collect && header.compressionAlgorithm != COMPRESSION_NONE) {
                    throw SabrProtocolException(
                        "Unsupported compression ${header.compressionAlgorithm} for SABR media segment",
                    )
                }
                if (collect && header.contentLength > maxCollectedSegmentSize) {
                    throw SabrProtocolException("SABR segment is too large: ${header.contentLength} bytes")
                }
                pendingSegments[header.headerId] = PendingSegment(header, collect)
                SabrEvent.MediaHeader(header)
            }

            UmpPartType.MEDIA_END -> {
                finalizeMedia(data, from, to)
            }

            UmpPartType.FORMAT_INITIALIZATION_METADATA -> {
                SabrEvent.FormatInitialized(SabrProtoCodec.decodeFormatInitialization(data, from, to))
            }

            UmpPartType.NEXT_REQUEST_POLICY -> {
                SabrProtoCodec.decodeNextRequestPolicy(data, from, to)
            }

            UmpPartType.SABR_REDIRECT -> {
                SabrProtoCodec.decodeRedirect(data, from, to)?.let(SabrEvent::Redirect)
            }

            UmpPartType.SABR_ERROR -> {
                SabrProtoCodec.decodeError(data, from, to)
            }

            UmpPartType.RELOAD_PLAYER_RESPONSE -> {
                SabrEvent.ReloadPlayerResponse
            }

            UmpPartType.SABR_CONTEXT_UPDATE -> {
                SabrProtoCodec.decodeContextUpdate(data, from, to)?.let(SabrEvent::ContextUpdate)
            }

            UmpPartType.STREAM_PROTECTION_STATUS -> {
                SabrProtoCodec.decodeStreamProtectionStatus(data, from, to)
            }

            UmpPartType.SABR_CONTEXT_SENDING_POLICY -> {
                SabrProtoCodec.decodeContextSendingPolicy(data, from, to)
            }

            UmpPartType.END_OF_TRACK -> {
                SabrEvent.EndOfTrack
            }

            else -> {
                null
            }
        }

    private fun appendMediaSlice(
        data: ByteArray,
        from: Int,
        to: Int,
    ) {
        var pos = from
        if (expectingMediaHeaderId) {
            if (pos >= to) return
            val headerId = data[pos++].toInt() and 0xff
            activeMediaSegment =
                pendingSegments[headerId]
                    ?: throw SabrProtocolException("UMP MEDIA refers to unknown header ID $headerId")
            expectingMediaHeaderId = false
        }
        val segment = checkNotNull(activeMediaSegment)
        val payloadSize = to - pos
        if (payloadSize <= 0) return
        val destinationOffset = segment.byteCount.toInt()
        segment.byteCount += payloadSize
        if (segment.header.contentLength > 0 && segment.byteCount > segment.header.contentLength) {
            throw SabrProtocolException("SABR segment ${segment.header.sequenceNumber} exceeded its content length")
        }
        if (!segment.collect) return
        if (segment.byteCount > maxCollectedSegmentSize) {
            throw SabrProtocolException("SABR segment is too large: ${segment.byteCount} bytes")
        }
        var bytes = segment.bytes
        if (bytes == null || bytes.size < segment.byteCount) {
            val capacity =
                if (segment.header.contentLength > 0) {
                    segment.header.contentLength.toInt()
                } else {
                    maxOf(segment.byteCount, (bytes?.size?.toLong() ?: INITIAL_SEGMENT_CAPACITY / 2L) * 2)
                        .coerceAtMost(maxCollectedSegmentSize.toLong())
                        .toInt()
                }
            // Reserve both buffers during growth; the previous allocation is still live during the copy.
            reservePendingBytes(segment, capacity)
            val expanded = ByteArray(capacity)
            bytes?.copyInto(expanded, endIndex = destinationOffset)
            val previousCapacity = bytes?.size ?: 0
            pendingMediaBytes -= previousCapacity
            segment.reservedBytes -= previousCapacity
            bytes = expanded
            segment.bytes = expanded
        }
        data.copyInto(bytes, destinationOffset = destinationOffset, startIndex = pos, endIndex = to)
    }

    private fun reservePendingBytes(
        segment: PendingSegment,
        bytes: Int,
    ) {
        if (pendingMediaBytes + bytes > maxPendingMediaBytes) {
            throw SabrProtocolException("SABR pending media exceeded $maxPendingMediaBytes bytes")
        }
        pendingMediaBytes += bytes
        segment.reservedBytes += bytes
    }

    private fun finalizeMedia(
        data: ByteArray,
        from: Int,
        to: Int,
    ): SabrEvent.Segment? {
        if (from >= to) throw SabrProtocolException("Empty UMP MEDIA_END part")
        val headerId = data[from].toInt() and 0xff
        val pending =
            pendingSegments.remove(headerId)
                ?: throw SabrProtocolException("UMP MEDIA_END refers to unknown header ID $headerId")
        if (pending.header.contentLength > 0 && pending.byteCount != pending.header.contentLength) {
            throw SabrProtocolException(
                "SABR segment ${pending.header.sequenceNumber} has ${pending.byteCount} bytes, " +
                    "expected ${pending.header.contentLength}",
            )
        }
        if (!pending.collect) return null
        if (pending.byteCount == 0L) throw SabrProtocolException("Empty selected SABR media segment")
        val buffer = checkNotNull(pending.bytes)
        val size = pending.byteCount.toInt()
        val bytes =
            if (buffer.size == size) {
                buffer
            } else {
                reservePendingBytes(pending, size)
                buffer.copyOf(size)
            }
        pendingMediaBytes -= pending.reservedBytes
        return SabrEvent.Segment(SabrSegment(pending.header, bytes))
    }

    private data class PendingSegment(
        val header: SabrMediaHeader,
        val collect: Boolean,
        var bytes: ByteArray? = null,
        var byteCount: Long = 0,
        var reservedBytes: Long = 0,
    )

    private companion object {
        const val INITIAL_SEGMENT_CAPACITY = 4 * 1024
        const val MAX_CONTROL_PART_BYTES = 128 * 1024
        const val MAX_RESPONSE_HEADERS = 4_096
        const val COMPRESSION_NONE = 0
        const val DEFAULT_MAX_COLLECTED_SEGMENT_SIZE = 16 * 1024 * 1024
        const val DEFAULT_MAX_PENDING_MEDIA_BYTES = 32 * 1024 * 1024
        const val DEFAULT_MAX_RESPONSE_BYTES = 96L * 1024 * 1024
    }
}
