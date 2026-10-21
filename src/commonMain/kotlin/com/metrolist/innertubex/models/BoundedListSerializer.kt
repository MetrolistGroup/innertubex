package com.metrolist.innertubex.models

import com.metrolist.innertubex.models.response.GetTranscriptResponse
import com.metrolist.innertubex.models.response.PlayerResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure

/**
 * Rejects a list as soon as it grows past [maxSize] while decoding, so a body that fits the byte limit cannot
 * still allocate millions of tiny elements (`[{},{},...]`) before a post-decode count check runs.
 */
internal open class BoundedListSerializer<T>(
    private val element: KSerializer<T>,
    private val maxSize: Int,
) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)
    override val descriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<T>,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> =
        decoder.decodeStructure(descriptor) {
            val result = ArrayList<T>()
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (result.size >= maxSize) throw SerializationException("List exceeded $maxSize elements")
                result += decodeSerializableElement(descriptor, index, element)
            }
            result
        }
}

/** Callers reject more than 2,048 formats in total; each list stops at that size while decoding. */
internal object PlayerFormatListSerializer :
    BoundedListSerializer<PlayerResponse.StreamingData.Format>(PlayerResponse.StreamingData.Format.serializer(), 2_048)

internal object TranscriptActionListSerializer :
    BoundedListSerializer<GetTranscriptResponse.Action>(GetTranscriptResponse.Action.serializer(), 64)

/** Matches the 20,000-cue limit applied by transcript parsing. */
internal object TranscriptCueGroupListSerializer :
    BoundedListSerializer<GetTranscriptResponse.CueGroup>(GetTranscriptResponse.CueGroup.serializer(), 20_000)

internal object TranscriptCueListSerializer :
    BoundedListSerializer<GetTranscriptResponse.Cue>(GetTranscriptResponse.Cue.serializer(), 64)
