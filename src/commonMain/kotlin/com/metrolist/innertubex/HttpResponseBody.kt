package com.metrolist.innertubex

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable

/** Reads a response body while enforcing a byte limit before and during streaming. */
public suspend fun HttpResponse.bodyAsTextLimited(maxBytes: Int): String = bodyAsTextLimited(maxBytes, stopWhen = null)

/**
 * Like [bodyAsTextLimited], but returns the prefix read so far and cancels the rest of the body once
 * [stopWhen] accepts it. [stopWhen] is checked at doubling intervals so a full read stays linear.
 * Only effective on streaming responses; a non-streaming call has already buffered the whole body.
 */
internal suspend fun HttpResponse.bodyAsTextLimited(
    maxBytes: Int,
    stopWhen: (suspend (String) -> Boolean)?,
): String {
    require(maxBytes > 0) { "Maximum response size must be positive" }
    val channel = bodyAsChannel()
    val declaredLength = contentLength()
    if (declaredLength != null && declaredLength > maxBytes) {
        channel.cancel(null)
        throw ResponseTooLargeException(maxBytes)
    }

    val readBuffer = ByteArray(minOf(DEFAULT_READ_BUFFER_SIZE, maxBytes))
    // An early-exit read usually stops well before a declared full-page length, so grow into it instead.
    val initialCapacity = declaredLength?.toInt()?.let { if (stopWhen != null) minOf(it, FIRST_STOP_CHECK_BYTES * 2) else it }
    var bytes = ByteArray(minOf(initialCapacity ?: DEFAULT_READ_BUFFER_SIZE, maxBytes))
    var size = 0
    var nextCheck = FIRST_STOP_CHECK_BYTES
    try {
        while (true) {
            val count = channel.readAvailable(readBuffer, 0, readBuffer.size)
            if (count == -1) break
            if (count == 0) continue
            if (size > maxBytes - count) {
                throw ResponseTooLargeException(maxBytes)
            }
            if (size + count > bytes.size) {
                bytes = bytes.copyOf(minOf(maxBytes, maxOf(size + count, bytes.size * 2)))
            }
            readBuffer.copyInto(bytes, destinationOffset = size, endIndex = count)
            size += count
            if (stopWhen != null && size >= nextCheck) {
                nextCheck = size * 2
                val prefix = bytes.decodeToString(endIndex = size)
                if (stopWhen(prefix)) {
                    channel.cancel(null)
                    return prefix
                }
            }
        }
    } catch (error: Throwable) {
        channel.cancel(error)
        throw error
    }
    return bytes.decodeToString(endIndex = size)
}

/** A bounded body exceeded its limit; deterministic, so callers should not retry it. */
internal class ResponseTooLargeException(
    maxBytes: Int,
) : IllegalStateException("Response exceeded the $maxBytes byte limit")

private const val DEFAULT_READ_BUFFER_SIZE = 8 * 1024
private const val FIRST_STOP_CHECK_BYTES = 32 * 1024
