package com.metrolist.innertubex.sabr

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalSabrApi::class)
class SabrAudioStreamTest {
    @Test
    fun lateTokenStartsWithoutWaitingAndIsSentOnLaterRequests() =
        runTest {
            val firstRequest = CompletableDeferred<Unit>()
            val supplied = CompletableDeferred<ByteArray?>()
            val token = byteArrayOf(9, 8, 7)
            val requests = mutableListOf<ByteArray>()
            val client =
                HttpClient(MockEngine) {
                    engine {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            requests += (request.body as OutgoingContent.ByteArrayContent).bytes()
                            val response =
                                when (requests.size) {
                                    1 -> {
                                        assertEquals(null, streamingToken(requests.last()))
                                        firstRequest.complete(Unit)
                                        supplied.await()
                                        initializationAndSegmentResponse(2, durationMs = 3000)
                                    }

                                    2 -> {
                                        mediaResponseSegment(3, 1, 1000, byteArrayOf(8, 9, 10))
                                    }

                                    else -> {
                                        mediaResponseSegment(4, 2, 2000, byteArrayOf(11, 12, 13)) +
                                            umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
                                    }
                                }
                            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                        }
                    }
                }
            try {
                val result =
                    async {
                        SabrAudioStream(
                            client,
                            bootstrap().copy(durationMs = 3000, contentLengthBytes = 13),
                            tokenSupplier = { supplied.await() },
                        ).chunks()
                            .toList()
                    }
                firstRequest.await()
                assertFalse(result.isCompleted)
                supplied.complete(token)
                withTimeout(5000) { result.await() }
                assertEquals(3, requests.size)
                requests.drop(1).forEach { assertContentEquals(token, streamingToken(it)) }
            } finally {
                client.close()
            }
        }

    @Test
    fun wholeFileRequestsWaitAtEightySecondsOfMediaForTheToken() =
        runTest {
            val supplied = CompletableDeferred<ByteArray?>()
            val atLimit = CompletableDeferred<Unit>()
            val token = byteArrayOf(8, 9)
            var requests = 0
            val client =
                HttpClient(MockEngine) {
                    engine {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            val response =
                                when (++requests) {
                                    1 -> {
                                        initializationResponsePrefix(2, durationMs = 120_000) +
                                            mediaResponseSegment(2, 0, 0, byteArrayOf(5, 6, 7), durationMs = 40_000)
                                    }

                                    2 -> {
                                        atLimit.complete(Unit)
                                        mediaResponseSegment(3, 1, 40_000, byteArrayOf(8, 9, 10), durationMs = 40_000)
                                    }

                                    else -> {
                                        assertContentEquals(
                                            token,
                                            streamingToken((request.body as OutgoingContent.ByteArrayContent).bytes()),
                                        )
                                        mediaResponseSegment(4, 2, 80_000, byteArrayOf(11, 12, 13), durationMs = 40_000) +
                                            umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
                                    }
                                }
                            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                        }
                    }
                }
            try {
                val result =
                    async {
                        SabrAudioStream(
                            client,
                            bootstrap().copy(durationMs = 120_000, contentLengthBytes = 13),
                            tokenSupplier = { supplied.await() },
                        ).chunks().toList()
                    }
                atLimit.await()
                runCurrent()
                assertEquals(2, requests)
                assertFalse(result.isCompleted)
                supplied.complete(token)
                result.await()
                assertEquals(3, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun pendingAndRequiredWaitForLateTokenWithoutSpendingRetryAllowance() =
        runBlocking {
            for (status in listOf(2, 3)) {
                val token = byteArrayOf(5, 6)
                val pending = CompletableDeferred<Unit>()
                val supplied = CompletableDeferred<ByteArray?>()
                var requests = 0
                val client =
                    HttpClient(
                        MockEngine { request ->
                            val response =
                                if (++requests == 1) {
                                    pending.complete(Unit)
                                    umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status, maxRetries = 0))
                                } else {
                                    assertContentEquals(token, streamingToken((request.body as OutgoingContent.ByteArrayContent).bytes()))
                                    umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(1)) + completeResponse(0)
                                }
                            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                        },
                    )
                try {
                    val result = async { SabrAudioStream(client, bootstrap(), tokenSupplier = { supplied.await() }).chunks().toList() }
                    pending.await()
                    assertFalse(result.isCompleted)
                    supplied.complete(token)
                    withTimeout(5000) { result.await() }
                    assertEquals(2, requests)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun failedOrEmptyLateTokenKeepsTheAttestationFailure() =
        runBlocking {
            for (fails in listOf(false, true)) {
                var requests = 0
                val client =
                    HttpClient(
                        MockEngine {
                            requests++
                            respond(
                                umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(3)),
                                headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                            )
                        },
                    )
                try {
                    val error =
                        assertFailsWith<SabrProtocolException> {
                            SabrAudioStream(
                                client,
                                bootstrap(),
                                tokenSupplier = { if (fails) error("Mint failed") else null },
                            ).chunks().toList()
                        }
                    assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
                    assertEquals(1, requests)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun supplierTimeoutKeepsTheAttestationFailure() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(3)),
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    },
                )
            try {
                val error =
                    assertFailsWith<SabrProtocolException> {
                        SabrAudioStream(client, bootstrap(), tokenSupplier = {
                            withTimeout(10) { awaitCancellation() }
                        }).chunks().toList()
                    }
                assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            } finally {
                client.close()
            }
        }

    @Test
    fun tokenRetryAfterTrackEndReadsTheNextResponseWithoutADrainDeadline() =
        runBlocking {
            val firstRequested = CompletableDeferred<Unit>()
            val secondRequested = CompletableDeferred<Unit>()
            val body = ByteChannel(autoFlush = true)
            val token = byteArrayOf(4, 5)
            var requests = 0
            val writer =
                launch {
                    secondRequested.await()
                    body.writeFully(umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(1)))
                    delay(250)
                    body.writeFully(finalSegmentResponse())
                    body.close()
                }
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (++requests == 1) {
                            firstRequested.complete(Unit)
                            respond(
                                completeResponse(1) + umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(3)),
                                headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                            )
                        } else {
                            assertContentEquals(token, streamingToken((request.body as OutgoingContent.ByteArrayContent).bytes()))
                            secondRequested.complete(Unit)
                            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                        }
                    },
                )
            try {
                val chunks =
                    withTimeout(2_000) {
                        SabrAudioStream(client, bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10), tokenSupplier = {
                            firstRequested.await()
                            delay(25)
                            token
                        }).chunks().toList()
                    }
                assertEquals(3, chunks.size)
                assertEquals(2, requests)
            } finally {
                writer.cancelAndJoin()
                body.cancel(null)
                client.close()
            }
        }

    @Test
    fun absentSupplierKeepsRequestBytesAndExistingTokensNeverMint() =
        runBlocking {
            val requests = mutableListOf<ByteArray>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += (request.body as OutgoingContent.ByteArrayContent).bytes()
                        respond(completeResponse(0), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    },
                )
            try {
                SabrAudioStream(client, bootstrap()).chunks().toList()
                SabrAudioStream(client, bootstrap(), tokenSupplier = null).chunks().toList()
                assertContentEquals(requests[0], requests[1])
                val token = byteArrayOf(1, 2)
                SabrAudioStream(client, bootstrap().copy(poToken = token), tokenSupplier = { error("Must not mint") }).chunks().toList()
                assertContentEquals(token, streamingToken(requests.last()))
            } finally {
                client.close()
            }
        }

    @Test
    fun lateTokenWaitHasABoundAndCancelsTheMinter() =
        runTest {
            var cancelled = false
            val client =
                HttpClient(MockEngine) {
                    engine {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler {
                            respond(
                                umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(3)),
                                headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                            )
                        }
                    }
                }
            try {
                val error =
                    assertFailsWith<SabrProtocolException> {
                        SabrAudioStream(client, bootstrap(), tokenSupplier = {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        }).chunks().toList()
                    }
                assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
                assertTrue(cancelled)
                assertEquals(30_000L, testScheduler.currentTime)
            } finally {
                client.close()
            }
        }

    private fun streamingToken(request: ByteArray): ByteArray? {
        val reader = ProtoReader(request)
        while (reader.hasRemaining) {
            val tag = reader.tag()
            if (tag.field == 19) {
                val context = ProtoReader(reader.bytes())
                while (context.hasRemaining) {
                    val field = context.tag()
                    if (field.field == 2) return context.bytes()
                    context.skip(field)
                }
                return null
            }
            reader.skip(tag)
        }
        return null
    }

    @Test
    fun transportCancellationRemainsCancellation() =
        runBlocking {
            val engine = MockEngine { throw kotlinx.coroutines.CancellationException("Request cancelled") }
            val client = HttpClient(engine)
            try {
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    SabrAudioStream(client, bootstrap()).chunks().toList()
                }
            } finally {
                client.close()
            }
            Unit
        }

    @Test
    fun strictHttpErrorsAreTypedWithoutWaitingForTheErrorBody() =
        runBlocking {
            for (video in listOf(false, true)) {
                val body = ByteChannel(autoFlush = true)
                val engine = MockEngine { respond(body, status = HttpStatusCode.Forbidden) }
                val client = HttpClient(engine) { expectSuccess = true }
                try {
                    val selected = bootstrap().copy(selectedVideoFormat = SabrFormatId(160, 200), selectedVideoHeight = 144)
                    val error =
                        withTimeout(2_000) {
                            assertFailsWith<SabrProtocolException> {
                                if (video) {
                                    SabrVideoStream(client, selected).chunks().toList()
                                } else {
                                    SabrAudioStream(client, selected).chunks().toList()
                                }
                            }
                        }
                    assertEquals(403, error.httpStatusCode)
                    assertTrue(body.isClosedForRead)
                    assertEquals(null, error.cause)
                } finally {
                    body.cancel(null)
                    client.close()
                }
            }
        }

    @Test
    fun endOfTrackDrainsAProtectionStatusFromTheResponseTail() =
        runBlocking {
            val engine =
                MockEngine {
                    val body = ByteChannel(autoFlush = true)
                    launch {
                        body.writeFully(completeResponse(0))
                        delay(25)
                        body.writeFully(umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(3)))
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val client = HttpClient(engine)
            try {
                val error =
                    withTimeout(2_000) {
                        assertFailsWith<SabrProtocolException> { SabrAudioStream(client, bootstrap()).chunks().toList() }
                    }
                assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            } finally {
                client.close()
            }
        }

    @Test
    fun consecutivePacingWaitsShareTheRequestHeartbeatDeadline() =
        runBlocking {
            val requests = mutableListOf<kotlin.time.TimeMark>()
            val engine =
                MockEngine {
                    requests += TimeSource.Monotonic.markNow()
                    val response =
                        when (requests.size) {
                            1 -> {
                                initializationResponsePrefix(1, durationMs = 11_000) +
                                    umpPart(
                                        UmpPartType.MEDIA_HEADER,
                                        mediaHeader(2, false, 0, 3) + ProtoWriter().apply { int64(12, 10_000) }.toByteArray(),
                                    ) +
                                    umpPart(UmpPartType.MEDIA, byteArrayOf(2, 5, 6, 7)) + umpPart(UmpPartType.MEDIA_END, byteArrayOf(2)) +
                                    umpPart(
                                        UmpPartType.NEXT_REQUEST_POLICY,
                                        ProtoWriter()
                                            .apply {
                                                int32(1, 5_000)
                                                int32(3, 500)
                                                int32(4, 500)
                                            }.toByteArray(),
                                    )
                            }

                            2 -> {
                                byteArrayOf()
                            }

                            else -> {
                                mediaResponseSegment(3, 1, 10_000, byteArrayOf(8, 9, 10)) + umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
                            }
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val client = HttpClient(engine)
            try {
                SabrAudioStream(
                    client,
                    bootstrap().copy(durationMs = 11_000, contentLengthBytes = 10),
                    playbackPositionMs = { 0L },
                ).chunks().toList()
                val secondIntervalMs = requests[1].elapsedNow().inWholeMilliseconds - requests[2].elapsedNow().inWholeMilliseconds
                assertTrue(secondIntervalMs < 850, "Both pacing waits must share the 500 ms heartbeat budget, got $secondIntervalMs ms")
            } finally {
                client.close()
            }
        }

    @Test
    fun continuationRequestsApplyContextPoliciesAndCookieUpdates() =
        runBlocking {
            fun update(
                type: Int,
                value: Int,
                send: Boolean = false,
                keep: Boolean = false,
            ) = umpPart(
                UmpPartType.SABR_CONTEXT_UPDATE,
                ProtoWriter()
                    .apply {
                        int32(1, type)
                        bytes(3, byteArrayOf(value.toByte()))
                        bool(4, send)
                        int32(5, if (keep) 2 else 0)
                    }.toByteArray(),
            )

            fun policy(
                start: List<Int> = emptyList(),
                stop: List<Int> = emptyList(),
                discard: List<Int> = emptyList(),
            ) = umpPart(
                UmpPartType.SABR_CONTEXT_SENDING_POLICY,
                ProtoWriter()
                    .apply {
                        packedInt32(1, start)
                        packedInt32(2, stop)
                        packedInt32(3, discard)
                    }.toByteArray(),
            )

            fun cookie(value: Int) =
                umpPart(
                    UmpPartType.NEXT_REQUEST_POLICY,
                    ProtoWriter().apply { bytes(7, byteArrayOf(value.toByte())) }.toByteArray(),
                )

            fun fields(
                data: ByteArray,
                field: Int,
            ): List<ByteArray> {
                val result = mutableListOf<ByteArray>()
                val reader = ProtoReader(data)
                while (reader.hasRemaining) {
                    val tag = reader.tag()
                    if (tag.field == field) result += reader.bytes() else reader.skip(tag)
                }
                return result
            }

            fun checkRequest(
                data: ByteArray,
                expectedCookie: Int,
                active: Map<Int, Int>,
                inactive: Set<Int>,
            ) {
                val state = fields(data, 19).single()
                assertContentEquals(byteArrayOf(expectedCookie.toByte()), fields(state, 3).single())
                val actual =
                    fields(state, 5).associate { context ->
                        val reader = ProtoReader(context)
                        reader.tag()
                        val type = reader.varint().toInt()
                        reader.tag()
                        type to reader.bytes().single().toInt()
                    }
                assertEquals(active, actual)
                val actualInactive =
                    fields(state, 6)
                        .flatMap { packed ->
                            val reader = ProtoReader(packed)
                            buildList { while (reader.hasRemaining) add(reader.varint().toInt()) }
                        }.toSet()
                assertEquals(inactive, actualInactive)
            }
            var requests = 0
            val engine =
                MockEngine { request ->
                    val data = (request.body as OutgoingContent.ByteArrayContent).bytes()
                    val response =
                        when (requests++) {
                            0 -> {
                                initializationAndSegmentResponse(3, durationMs = 4_000) +
                                    update(1, 11, send = true) + update(2, 22) + update(3, 33, send = true) + update(4, 44) +
                                    policy(start = listOf(2), stop = listOf(3)) + cookie(1)
                            }

                            1 -> {
                                checkRequest(data, 1, mapOf(1 to 11, 2 to 22), setOf(3, 4))
                                mediaResponseSegment(3, 1, 1_000, byteArrayOf(8)) +
                                    update(1, 99, keep = true) + update(2, 99, send = true) +
                                    policy(start = listOf(3), stop = listOf(1), discard = listOf(2)) + cookie(2)
                            }

                            2 -> {
                                checkRequest(data, 2, mapOf(3 to 33), setOf(1, 4))
                                mediaResponseSegment(4, 2, 2_000, byteArrayOf(9)) +
                                    update(3, 99, keep = true) + policy(start = listOf(1), discard = listOf(4)) + cookie(3)
                            }

                            else -> {
                                checkRequest(data, 3, mapOf(1 to 11, 3 to 33), emptySet())
                                mediaResponseSegment(5, 3, 3_000, byteArrayOf(10)) + umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
                            }
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            SabrAudioStream(HttpClient(engine), bootstrap().copy(durationMs = 4_000, contentLengthBytes = null)).chunks().toList()
            assertEquals(4, requests)
        }

    @Test
    fun seekAcceptsOneMillisecondRoundingGapAfterPredecessor() =
        runBlocking {
            val response =
                initializationResponsePrefix(6, 61_001) +
                    mediaResponseSegment(2, 5, 59_000, byteArrayOf(5)) +
                    mediaResponseSegment(3, 6, 60_001, byteArrayOf(6)) +
                    umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
            val engine = MockEngine { respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump")) }
            val chunks =
                SabrAudioStream(
                    HttpClient(engine),
                    bootstrap().copy(durationMs = 61_001),
                    initialPlayerTimeMs = 60_000,
                ).chunks().toList()
            assertEquals(listOf(null, 6), chunks.map(SabrChunk::sequenceNumber))
        }

    @Test
    fun eofNeverEmitsMediaBeforeInitialization() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    val response =
                        if (requests++ == 0) {
                            umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(1, 2_000)) +
                                mediaResponseSegment(2, 0, 0, byteArrayOf(5, 6, 7))
                        } else {
                            initializationResponsePrefix(1, 2_000) + finalSegmentResponse()
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val chunks = mutableListOf<SabrChunk>()
            assertFailsWith<SabrProtocolException> {
                SabrAudioStream(HttpClient(engine), bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10)).chunks().collect {
                    chunks +=
                        it
                }
            }
            assertTrue(chunks.isEmpty())
        }

    @Test
    fun trackEndDrainDeadlineDoesNotReportAPartialControlPartAsTruncated() =
        runBlocking {
            for (serverCloses in listOf(false, true)) {
                val body = ByteChannel(autoFlush = true)
                val tail = umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(1))
                val writer =
                    launch {
                        body.writeFully(completeResponse(0) + tail.copyOf(tail.size - 1))
                        if (!serverCloses) {
                            delay(500)
                            body.writeFully(tail.copyOfRange(tail.size - 1, tail.size))
                        }
                        body.close()
                    }
                val client =
                    HttpClient(
                        MockEngine {
                            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                        },
                    )
                try {
                    withTimeout(2_000) {
                        if (serverCloses) {
                            assertFailsWith<SabrProtocolException> { SabrAudioStream(client, bootstrap()).chunks().toList() }
                        } else {
                            assertEquals(2, SabrAudioStream(client, bootstrap()).chunks().toList().size)
                            assertTrue(body.isClosedForRead)
                        }
                    }
                } finally {
                    writer.cancelAndJoin()
                    body.cancel(null)
                    client.close()
                }
            }
        }

    @Test
    fun endOfTrackCompletesWithAnOpenHttpBody() =
        runBlocking {
            val body = ByteChannel(autoFlush = true)
            val engine =
                MockEngine {
                    launch { body.writeFully(completeResponse(0)) }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            try {
                val chunks = withTimeout(2_000) { SabrAudioStream(HttpClient(engine), bootstrap()).chunks().toList() }
                assertEquals(2, chunks.size)
                assertTrue(body.isClosedForRead)
            } finally {
                body.cancel(null)
            }
        }

    @Test
    fun transportFailuresDoNotExposeRequestUrls() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    throw io.ktor.client.plugins
                        .HttpRequestTimeoutException(request)
                }
            for (video in listOf(false, true)) {
                val selected = bootstrap().copy(selectedVideoFormat = SabrFormatId(160, 200), selectedVideoHeight = 144)
                val error =
                    assertFailsWith<SabrProtocolException> {
                        if (video) {
                            SabrVideoStream(HttpClient(engine), selected).chunks().toList()
                        } else {
                            SabrAudioStream(HttpClient(engine), selected).chunks().toList()
                        }
                    }
                assertFalse(error.toString().contains("googlevideo"))
                assertEquals(null, error.cause)
            }
        }

    @Test
    fun boundsCompletedSegmentsWaitingForTheOpeningSequence() =
        runBlocking {
            val response =
                initializationResponsePrefix(1_025) +
                    (1..1_025)
                        .flatMap { sequence ->
                            // None covers the track start, so all of them wait for the opening sequence.
                            mediaResponseSegment(2, sequence, sequence * 1_000L, byteArrayOf(1)).asIterable()
                        }.toByteArray()
            val engine =
                MockEngine {
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(HttpClient(engine), bootstrap().copy(contentLengthBytes = null)).chunks().toList()
                }
            assertEquals("SABR pending segment count exceeded the memory limit", error.message)
        }

    @Test
    fun samplesHeaderDiagnosticsWithManyDuplicateSegments() =
        runBlocking {
            val response =
                initializationResponsePrefix(0) +
                    (1..200)
                        .flatMap {
                            mediaResponseSegment(2, 0, 0, byteArrayOf(5, 6, 7)).asIterable()
                        }.toByteArray()
            val engine =
                MockEngine {
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val diagnostics = mutableListOf<SabrResponseDiagnostics>()
            val chunks = SabrAudioStream(HttpClient(engine), bootstrap(), onResponse = diagnostics::add).chunks().toList()
            assertEquals(2, chunks.size)
            assertEquals(128, diagnostics.single().receivedSequences.size)
            assertEquals(128, diagnostics.single().receivedStartRanges.size)
            assertEquals(128, diagnostics.single().receivedContentLengths.size)
            assertEquals(1, diagnostics.single().selectedSegmentCount)
        }

    @Test
    fun rejectsMissingOpeningMediaWithoutDeclaredContentLength() =
        runBlocking {
            for (sequence in listOf(0, 5)) {
                val engine =
                    MockEngine {
                        respond(
                            initializationResponsePrefix(sequence, durationMs = 2_000) +
                                mediaResponseSegment(2, sequence, 1_000, byteArrayOf(5, 6, 7)),
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    }
                val error =
                    assertFailsWith<SabrProtocolException> {
                        SabrAudioStream(HttpClient(engine), bootstrap().copy(durationMs = 2_000, contentLengthBytes = null))
                            .chunks()
                            .toList()
                    }
                assertEquals("SABR first media segment does not cover the requested start", error.message)
            }
        }

    @Test
    fun acceptsNonzeroSequenceNumberWhenOpeningTimestampCoversStart() =
        runBlocking {
            for (startMs in listOf(0L, 1L)) {
                val engine =
                    MockEngine {
                        respond(
                            initializationResponsePrefix(5) + mediaResponseSegment(2, 5, startMs, byteArrayOf(5, 6, 7)),
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    }
                val chunks = SabrAudioStream(HttpClient(engine), bootstrap()).chunks().toList()
                assertEquals(listOf(null, 5), chunks.map(SabrChunk::sequenceNumber))
            }
        }

    @Test
    fun nonzeroOpeningWaitsForTheNextReadWhileInitializationStreams() =
        runBlocking {
            val firstChunk = CompletableDeferred<SabrChunk>()
            val chunks = mutableListOf<SabrChunk>()
            val engine =
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    channel.writeFully(initializationResponsePrefix(5) + mediaResponseSegment(3, 5, 0L, byteArrayOf(8, 9)))
                    val body =
                        object : ByteReadChannel by channel {
                            override suspend fun awaitContent(min: Int): Boolean {
                                assertContentEquals(byteArrayOf(1, 2, 3, 4), firstChunk.await().data)
                                assertEquals(listOf(null), chunks.map(SabrChunk::sequenceNumber), "only init precedes the next read")
                                channel.writeFully(mediaResponseSegment(2, 4, 0L, byteArrayOf(5, 6, 7)))
                                channel.close()
                                return channel.awaitContent(min)
                            }
                        }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val client = HttpClient(engine)
            try {
                withTimeout(2_000) {
                    SabrAudioStream(client, bootstrap().copy(contentLengthBytes = 9)).chunks().collect { chunk ->
                        chunks += chunk
                        if (chunk.isInitialization) firstChunk.complete(chunk)
                    }
                }
                assertEquals(listOf(null, 4, 5), chunks.map(SabrChunk::sequenceNumber))
                assertContentEquals(byteArrayOf(5, 6, 7), chunks[1].data)
                assertContentEquals(byteArrayOf(8, 9), chunks[2].data)
            } finally {
                client.close()
            }
        }

    @Test
    fun nonzeroOpeningStreamsWhenResponseStallsAndCancellationClosesTheBody() =
        runBlocking {
            for (startMs in listOf(0L, 1L)) {
                val releaseResponseTail = CompletableDeferred<Unit>()
                val channel = ByteChannel(autoFlush = true)
                val engine =
                    MockEngine {
                        launch {
                            channel.writeFully(
                                initializationResponsePrefix(5) + mediaResponseSegment(2, 5, startMs, byteArrayOf(5, 6, 7)),
                            )
                            releaseResponseTail.await()
                            channel.close()
                        }
                        respond(channel, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    }
                val client = HttpClient(engine)
                try {
                    val chunks = withTimeout(5_000) { SabrAudioStream(client, bootstrap()).chunks().take(2).toList() }
                    assertEquals(listOf(null, 5), chunks.map(SabrChunk::sequenceNumber))
                    assertContentEquals(byteArrayOf(5, 6, 7), chunks.last().data)
                    assertFalse(releaseResponseTail.isCompleted, "the response is still open when media arrives")
                    assertTrue(channel.isClosedForRead, "take must cancel the stalled response body")
                } finally {
                    releaseResponseTail.complete(Unit)
                    client.close()
                }
            }
        }

    @Test
    fun nonzeroOpeningWaitIsBoundedWhileControlPartsArriveAndPreservesTheTail() =
        runBlocking {
            val firstMedia = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    launch {
                        channel.writeFully(
                            initializationResponsePrefix(6, durationMs = 2_000) +
                                mediaResponseSegment(2, 5, 0L, byteArrayOf(5, 6, 7)),
                        )
                        while (!firstMedia.isCompleted) {
                            channel.writeFully(umpPart(UmpPartType.NEXT_REQUEST_POLICY, nextRequestPolicy(0)))
                            delay(25)
                        }
                        channel.writeFully(mediaResponseSegment(3, 6, 1_000L, byteArrayOf(8, 9, 10)))
                        channel.close()
                    }
                    respond(channel, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val client = HttpClient(engine)
            try {
                val chunks = mutableListOf<SabrChunk>()
                withTimeout(5_000) {
                    SabrAudioStream(client, bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10)).chunks().collect { chunk ->
                        chunks += chunk
                        if (chunk.sequenceNumber == 5) firstMedia.complete(Unit)
                    }
                }
                assertEquals(listOf(null, 5, 6), chunks.map(SabrChunk::sequenceNumber))
                assertContentEquals(byteArrayOf(8, 9, 10), chunks.last().data)
            } finally {
                firstMedia.complete(Unit)
                client.close()
            }
        }

    @Test
    fun sequenceZeroAndConsecutiveSegmentsStreamBeforeResponseCompletion() =
        runBlocking {
            val releaseResponseTail = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    launch {
                        channel.writeFully(
                            initializationAndSegmentResponse(endSegmentNumber = 1) +
                                mediaResponseSegment(3, 1, 1_000L, byteArrayOf(8, 9, 10)),
                        )
                        releaseResponseTail.await()
                        channel.close()
                    }
                    respond(channel, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val client = HttpClient(engine)
            try {
                val chunks =
                    withTimeout(2_000) {
                        SabrAudioStream(client, bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10)).chunks().take(3).toList()
                    }
                assertEquals(listOf(null, 0, 1), chunks.map(SabrChunk::sequenceNumber))
                assertFalse(releaseResponseTail.isCompleted)
            } finally {
                releaseResponseTail.complete(Unit)
                client.close()
            }
        }

    @Test
    fun openingUsesTheLowestBufferedSequenceWithoutDroppingIt() =
        runBlocking {
            for (initFirst in listOf(false, true)) {
                val initialization = initializationResponsePrefix(5)
                val media =
                    mediaResponseSegment(3, 5, 0L, byteArrayOf(8, 9)) +
                        mediaResponseSegment(2, 4, 0L, byteArrayOf(5, 6, 7))
                val engine =
                    MockEngine {
                        respond(
                            if (initFirst) initialization + media else media + initialization,
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    }
                val client = HttpClient(engine)
                try {
                    val chunks = SabrAudioStream(client, bootstrap().copy(contentLengthBytes = 9)).chunks().toList()
                    assertEquals(listOf(null, 4, 5), chunks.map(SabrChunk::sequenceNumber))
                    assertContentEquals(byteArrayOf(5, 6, 7), chunks[1].data)
                    assertContentEquals(byteArrayOf(8, 9), chunks[2].data)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun requiredProtectionStopsReadingAStalledResponse() =
        runBlocking {
            val engine =
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    launch {
                        channel.writeFully(
                            initializationResponsePrefix(5) +
                                umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 3)),
                        )
                        // The body never finishes; the stream must not wait for it.
                    }
                    respond(channel, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val error =
                withTimeout(2_000) {
                    assertFailsWith<SabrProtocolException> { SabrAudioStream(HttpClient(engine), bootstrap()).chunks().toList() }
                }
            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
        }

    @Test
    fun recollectionRestartsRequestNumbersAfterCompletionAndCancellation() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertEquals("0", request.url.parameters["rn"])
                    respond(completeResponse(0), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val flow = SabrAudioStream(HttpClient(engine), bootstrap()).chunks()
            val first = flow.toList()
            assertEquals(first, flow.toList())
            assertEquals(first.take(1), flow.take(1).toList())
            assertEquals(first, flow.toList())
            assertEquals(4, engine.requestHistory.size)
        }

    @Test
    fun concurrentCollectorsHaveIndependentRequestNumbers() =
        runBlocking {
            val bothStarted = CompletableDeferred<Unit>()
            val started = kotlinx.coroutines.channels.Channel<Unit>(2)
            val engine =
                MockEngine { request ->
                    assertEquals("0", request.url.parameters["rn"])
                    started.send(Unit)
                    bothStarted.await()
                    respond(completeResponse(0), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }
            val flow = SabrAudioStream(HttpClient(engine), bootstrap()).chunks()
            withTimeout(5_000) {
                val first = async { flow.toList() }
                val second = async { flow.toList() }
                repeat(2) { started.receive() }
                bothStarted.complete(Unit)
                assertEquals(first.await(), second.await())
                assertEquals(2, first.await().size)
            }
        }

    @Test
    fun videoStreamEmitsOnlySelectedVideoSegments() =
        runBlocking {
            val response =
                umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(0, itag = 248, lastModified = 300)) +
                    mediaSegment(headerId = 1, itag = 140, lastModified = 100, isInit = true, data = byteArrayOf(1)) +
                    mediaSegment(headerId = 2, itag = 248, lastModified = 300, isInit = true, data = byteArrayOf(2, 3)) +
                    mediaSegment(headerId = 3, itag = 140, lastModified = 100, data = byteArrayOf(4)) +
                    mediaSegment(headerId = 4, itag = 248, lastModified = 300, data = byteArrayOf(5, 6, 7)) +
                    umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
            val engine =
                MockEngine {
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }
            val bootstrap =
                bootstrap().copy(
                    selectedVideoFormat = SabrFormatId(248, 300),
                    selectedVideoWidth = 1920,
                    selectedVideoHeight = 1080,
                    selectedVideoContentLengthBytes = 5,
                    selectedVideoMimeType = "video/webm",
                )

            val chunks = SabrVideoStream(HttpClient(engine), bootstrap).bytes().toList()

            assertEquals(2, chunks.size)
            assertContentEquals(byteArrayOf(2, 3), chunks[0])
            assertContentEquals(byteArrayOf(5, 6, 7), chunks[1])
        }

    @Test
    fun emitsCompleteInitializationAndMediaSequence() =
        runBlocking {
            val response = completeResponse(endSegmentNumber = 0)
            val engine =
                MockEngine { request ->
                    assertEquals("0", request.url.parameters["rn"])
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks = SabrAudioStream(HttpClient(engine), bootstrap()).bytes().toList()

            assertEquals(2, chunks.size)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), chunks[0])
            assertContentEquals(byteArrayOf(5, 6, 7), chunks[1])
        }

    @Test
    fun doesNotFollowHttpRedirects() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertEquals("preserved", request.headers["X-Caller-Policy"])
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://example.com/steal-sabr"),
                    )
                }

            assertFailsWith<SabrProtocolException> {
                SabrAudioStream(
                    HttpClient(engine) {
                        defaultRequest { header("X-Caller-Policy", "preserved") }
                    },
                    bootstrap(),
                ).bytes().toList()
            }
            assertEquals(1, engine.requestHistory.size)
            Unit
        }

    @Test
    fun rejectsCompletedStreamWithMissingBytes() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = completeResponse(endSegmentNumber = 0),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            assertFailsWith<SabrProtocolException> {
                SabrAudioStream(
                    HttpClient(engine),
                    bootstrap().copy(contentLengthBytes = 8),
                ).bytes().toList()
            }
            Unit
        }

    @Test
    fun rejectsExcessRetainedContexts() =
        runBlocking {
            val response =
                (1..65).fold(byteArrayOf()) { data, type ->
                    data + umpPart(UmpPartType.SABR_CONTEXT_UPDATE, contextUpdate(type))
                }
            val engine =
                MockEngine {
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            assertFailsWith<SabrProtocolException> {
                SabrAudioStream(HttpClient(engine), bootstrap()).bytes().toList()
            }
            Unit
        }

    @Test
    fun emitsInitializationBeforeSabrResponseCompletes() =
        runBlocking {
            val releaseResponseTail = CompletableDeferred<Unit>()
            val firstChunk = CompletableDeferred<ByteArray>()
            val engine =
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    launch {
                        channel.writeFully(initializationResponsePrefix(endSegmentNumber = 0))
                        releaseResponseTail.await()
                        channel.writeFully(mediaResponseSuffix())
                        channel.close()
                    }
                    respond(
                        content = channel,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }
            val chunks = mutableListOf<ByteArray>()
            val collection =
                async {
                    SabrAudioStream(HttpClient(engine), bootstrap()).bytes().collect { chunk ->
                        chunks += chunk
                        if (!firstChunk.isCompleted) firstChunk.complete(chunk)
                    }
                }

            assertContentEquals(byteArrayOf(1, 2, 3, 4), withTimeout(1_000) { firstChunk.await() })
            assertFalse(collection.isCompleted)

            releaseResponseTail.complete(Unit)
            collection.await()
            assertEquals(2, chunks.size)
            assertContentEquals(byteArrayOf(5, 6, 7), chunks.last())
        }

    @Test
    fun seekedHighBitrateVideoEmitsInitializationBeforeOutOfOrderMedia() =
        runBlocking {
            val releaseResponseTail = CompletableDeferred<Unit>()
            val firstMedia = CompletableDeferred<ByteArray>()
            val mediaBeforeTail = CompletableDeferred<List<Int?>>()
            val chunks = mutableListOf<SabrChunk>()
            var requestBody: ByteArray? = null
            val engine =
                MockEngine { request ->
                    requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
                    val channel = ByteChannel(autoFlush = true)
                    launch {
                        channel.writeFully(
                            umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(7, 62_000, 401, 400)) +
                                mediaSegment(headerId = 1, itag = 248, lastModified = 300, isInit = true, data = byteArrayOf(9)) +
                                mediaResponseSegment(
                                    headerId = 3,
                                    sequenceNumber = 6,
                                    startMs = 60_000,
                                    data = byteArrayOf(3, 4, 5),
                                    itag = 248,
                                    lastModified = 300,
                                ) +
                                mediaResponseSegment(
                                    headerId = 4,
                                    sequenceNumber = 7,
                                    startMs = 61_000,
                                    data = byteArrayOf(6, 7, 8),
                                    itag = 401,
                                    lastModified = 400,
                                ) +
                                mediaResponseSegment(
                                    headerId = 5,
                                    sequenceNumber = 6,
                                    startMs = 60_000,
                                    data = byteArrayOf(9, 10, 11),
                                    itag = 401,
                                    lastModified = 400,
                                ) +
                                mediaSegment(headerId = 2, itag = 401, lastModified = 400, isInit = true, data = byteArrayOf(1, 2)),
                        )
                        releaseResponseTail.await()
                        channel.writeFully(umpPart(UmpPartType.END_OF_TRACK, byteArrayOf()))
                        channel.close()
                    }
                    respond(
                        content = channel,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }
            val bootstrap =
                bootstrap().copy(
                    selectedVideoFormat = SabrFormatId(401, 400, "x"),
                    selectedVideoWidth = 3840,
                    selectedVideoHeight = 2160,
                    selectedVideoContentLengthBytes = 8,
                    selectedVideoMimeType = "video/webm",
                    selectedVideoBitrate = 12_000_000,
                    durationMs = 62_000,
                )
            val collection =
                async {
                    SabrVideoStream(HttpClient(engine), bootstrap, initialPlayerTimeMs = 60_000).chunks().collect { chunk ->
                        chunks += chunk
                        if (!chunk.isInitialization && !firstMedia.isCompleted) firstMedia.complete(chunk.data)
                        if (chunk.sequenceNumber == 7) mediaBeforeTail.complete(chunks.map(SabrChunk::sequenceNumber))
                    }
                }

            assertContentEquals(byteArrayOf(9, 10, 11), withTimeout(1_000) { firstMedia.await() })
            assertEquals(listOf(null, 6, 7), withTimeout(1_000) { mediaBeforeTail.await() })
            assertEquals(60_000, decodePlayerTimeMs(requestBody ?: error("No SABR request")))
            assertFalse(collection.isCompleted)

            releaseResponseTail.complete(Unit)
            collection.await()
        }

    @Test
    fun rejectsEndOfTrackBeforeInitializationMetadataEndSegment() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = completeResponse(endSegmentNumber = 1),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            assertFailsWith<SabrProtocolException> {
                SabrAudioStream(HttpClient(engine), bootstrap()).bytes().toList()
            }
            Unit
        }

    @Test
    fun retriesTransientSabrHttpFailureBeforePlaybackStarts() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    if (requests == 1) {
                        respond("temporary", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond(
                            content = completeResponse(endSegmentNumber = 0),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    }
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine) { expectSuccess = true },
                    bootstrap = bootstrap(),
                    requestRetryDelay = {},
                ).bytes().toList()

            assertEquals(2, requests)
            assertEquals(2, chunks.size)
        }

    @Test
    fun followupRequestUsesDownloadedBoundaryInsteadOfPlaybackPosition() =
        runBlocking {
            var requestCount = 0
            var followupBody: ByteArray? = null
            val engine =
                MockEngine { request ->
                    requestCount++
                    val response =
                        if (requestCount == 1) {
                            initializationAndSegmentResponse(endSegmentNumber = 1)
                        } else {
                            followupBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
                            finalSegmentResponse()
                        }
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10),
                    playbackPositionMs = { 250 },
                ).bytes().toList()

            assertEquals(2, requestCount)
            assertEquals(3, chunks.size)
            assertEquals(1_000, decodePlayerTimeMs(followupBody ?: error("No follow-up request")))
        }

    @Test
    fun followupRetainsBufferedTimeSpanAcrossEmptyResponseAndTimestampRounding() =
        runBlocking {
            val diagnostics = mutableListOf<SabrResponseDiagnostics>()
            val requests = mutableListOf<ByteArray>()
            val engine =
                MockEngine { request ->
                    requests += (request.body as OutgoingContent.ByteArrayContent).bytes()
                    respond(
                        content =
                            when (requests.size) {
                                1 -> {
                                    initializationAndSegmentResponse(endSegmentNumber = 2, durationMs = 3_005)
                                }

                                2 -> {
                                    mediaResponseSegment(
                                        headerId = 3,
                                        sequenceNumber = 1,
                                        startMs = 1_010,
                                        data = byteArrayOf(8, 9, 10),
                                    )
                                }

                                3 -> {
                                    umpPart(UmpPartType.NEXT_REQUEST_POLICY, nextRequestPolicy(0))
                                }

                                else -> {
                                    mediaResponseSegment(
                                        headerId = 4,
                                        sequenceNumber = 2,
                                        startMs = 2_005,
                                        data = byteArrayOf(11, 12, 13),
                                    ) +
                                        umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
                                }
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    HttpClient(engine),
                    bootstrap().copy(durationMs = 3_005, contentLengthBytes = 13),
                    onResponse = diagnostics::add,
                ).chunks().toList()

            assertEquals(listOf(null, 0, 1, 2), chunks.map(SabrChunk::sequenceNumber))
            assertEquals(4, requests.size)
            assertEquals(SabrBufferedRange(SabrFormatId(140, 100, "x"), 0, 2_010, 0, 1), diagnostics[1].bufferedRanges.single())
            assertEquals(diagnostics[1].bufferedRanges, diagnostics[2].bufferedRanges)
            assertEquals(SabrBufferedRange(SabrFormatId(140, 100, "x"), 0, 3_005, 0, 2), diagnostics[3].bufferedRanges.single())
            assertEquals(2_010, decodePlayerTimeMs(requests[3]))
            val range =
                ProtoReader(requests[3]).let { reader ->
                    while (reader.hasRemaining) {
                        val tag = reader.tag()
                        if (tag.field == 3) {
                            val value = reader.bytes()
                            val fields = ProtoReader(value)
                            while (fields.hasRemaining) {
                                val field = fields.tag()
                                if (field.field == 5) return@let fields.varint()
                                fields.skip(field)
                            }
                        } else {
                            reader.skip(tag)
                        }
                    }
                    null
                }
            assertEquals(1L, range)
        }

    @Test
    fun multiSegmentResponsePreservesBufferedRangeDiagnostics() =
        runBlocking {
            val diagnostics = mutableListOf<SabrResponseDiagnostics>()
            val engine =
                MockEngine {
                    respond(
                        content = initializationAndSegmentResponse(endSegmentNumber = 1) + finalSegmentResponse(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10),
                    onResponse = diagnostics::add,
                ).chunks().toList()

            assertEquals(3, chunks.size)
            assertEquals(200, diagnostics.single().httpStatus)
            assertEquals(6, diagnostics.single().selectedMediaBytes)
            assertEquals(2, diagnostics.single().selectedSegmentCount)
            assertEquals(SabrBufferedRange(SabrFormatId(140, 100, "x"), 0, 2_000, 0, 1), diagnostics.single().bufferedRanges.single())
        }

    @Test
    fun duplicateOldAndOutOfOrderNewSegmentsRemainOrdered() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(
                        content =
                            if (requests == 1) {
                                initializationAndSegmentResponse(endSegmentNumber = 2, durationMs = 3_000)
                            } else {
                                mediaResponseSegment(headerId = 3, sequenceNumber = 0, startMs = 0, data = byteArrayOf(5, 6, 7)) +
                                    mediaResponseSegment(
                                        headerId = 4,
                                        sequenceNumber = 2,
                                        startMs = 2_000,
                                        data = byteArrayOf(11, 12, 13),
                                    ) +
                                    mediaResponseSegment(headerId = 5, sequenceNumber = 1, startMs = 1_000, data = byteArrayOf(8, 9, 10)) +
                                    umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 3_000, contentLengthBytes = 13),
                ).chunks().toList()

            assertEquals(2, requests)
            assertEquals(listOf(null, 0, 1, 2), chunks.map(SabrChunk::sequenceNumber))
        }

    @Test
    fun rejectsUnseenSequenceBelowEmittedRange() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(
                        content =
                            if (requests == 1) {
                                initializationResponsePrefix(endSegmentNumber = 2, durationMs = 3_000) +
                                    mediaResponseSegment(headerId = 2, sequenceNumber = 1, startMs = 0, data = byteArrayOf(8, 9, 10))
                            } else {
                                mediaResponseSegment(headerId = 3, sequenceNumber = 0, startMs = 0, data = byteArrayOf(5, 6, 7)) +
                                    mediaResponseSegment(
                                        headerId = 4,
                                        sequenceNumber = 2,
                                        startMs = 2_000,
                                        data = byteArrayOf(11, 12, 13),
                                    ) +
                                    umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(
                        httpClient = HttpClient(engine),
                        bootstrap = bootstrap().copy(durationMs = 3_000, contentLengthBytes = null),
                    ).bytes().toList()
                }

            assertEquals("SABR segment sequence gap: expected 3, received 0", error.message)
            assertEquals(2, requests)
        }

    @Test
    fun finalSegmentCompletesWithoutEndOfTrackPart() =
        runBlocking {
            var requestCount = 0
            val engine =
                MockEngine {
                    requestCount++
                    respond(
                        content =
                            if (requestCount == 1) {
                                initializationAndSegmentResponse(endSegmentNumber = 1, durationMs = 2_100)
                            } else {
                                finalSegmentResponse(includeEndOfTrack = false)
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 2_100, contentLengthBytes = 10),
                ).bytes().toList()

            assertEquals(2, requestCount)
            assertEquals(3, chunks.size)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun waitsForPlaybackBeforeExceedingServerReadaheadTarget() =
        runTest {
            var requestCount = 0
            var playbackPosition = 0L
            val readaheadChecked = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestCount++
                    val response =
                        if (requestCount == 1) {
                            initializationAndSegmentResponse(endSegmentNumber = 1, targetAudioReadaheadMs = 500)
                        } else {
                            finalSegmentResponse()
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                }.apply { config.dispatcher = StandardTestDispatcher(testScheduler) }
            val client = HttpClient(engine)
            try {
                val chunks =
                    async {
                        SabrAudioStream(
                            httpClient = client,
                            bootstrap = bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10),
                            playbackPositionMs = {
                                if (requestCount == 1) readaheadChecked.complete(Unit)
                                playbackPosition
                            },
                        ).bytes().toList()
                    }
                readaheadChecked.await()
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(1, requestCount)
                playbackPosition = 600
                advanceTimeBy(100)
                runCurrent()
                assertEquals(3, chunks.await().size)
                assertEquals(2, requestCount)
            } finally {
                client.close()
            }
        }

    @Test
    fun initialMediaTimeStartsNearSeekTargetAndAcceptsNonzeroFirstSegment() =
        runBlocking {
            var requestBody: ByteArray? = null
            val engine =
                MockEngine { request ->
                    requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
                    respond(
                        content = seekResponse(sequenceNumber = 6, startMs = 60_000),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 61_000),
                    initialPlayerTimeMs = 60_000,
                ).chunks().toList()

            assertEquals(60_000, decodePlayerTimeMs(requestBody ?: error("No SABR request")))
            assertEquals(listOf(true, false), chunks.map(SabrChunk::isInitialization))
            assertEquals(6, chunks.last().sequenceNumber)
            assertEquals(60_000, chunks.last().startMs)
        }

    @Test
    fun lateSegmentBeforeEstablishedSeekIsIgnored() =
        runBlocking {
            val response =
                umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(7, durationMs = 61_000)) +
                    mediaSegment(headerId = 1, itag = 140, lastModified = 100, isInit = true, data = byteArrayOf(1, 2)) +
                    mediaResponseSegment(
                        headerId = 2,
                        sequenceNumber = 7,
                        startMs = 60_000,
                        data = byteArrayOf(8, 9, 10),
                    ) +
                    mediaResponseSegment(
                        headerId = 3,
                        sequenceNumber = 6,
                        startMs = 59_000,
                        data = byteArrayOf(5, 6, 7),
                    ) +
                    umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
            val engine =
                MockEngine {
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 61_000),
                    initialPlayerTimeMs = 60_500,
                ).chunks().toList()

            assertEquals(listOf(null, 7), chunks.map(SabrChunk::sequenceNumber))
            assertContentEquals(byteArrayOf(8, 9, 10), chunks.last().data)
        }

    @Test
    fun initialSeekWaitsForTargetWhenSequenceZeroArrivesFirst() =
        runBlocking {
            val response =
                umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(6, durationMs = 13_000)) +
                    mediaSegment(headerId = 1, itag = 140, lastModified = 100, isInit = true, data = byteArrayOf(1, 2)) +
                    mediaResponseSegment(
                        headerId = 2,
                        sequenceNumber = 0,
                        startMs = 0,
                        data = byteArrayOf(3, 4, 5),
                    ) +
                    mediaResponseSegment(
                        headerId = 3,
                        sequenceNumber = 6,
                        startMs = 12_000,
                        data = byteArrayOf(8, 9, 10),
                    ) +
                    umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
            val engine =
                MockEngine {
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 13_000),
                    initialPlayerTimeMs = 12_000,
                ).chunks().toList()

            assertEquals(listOf(null, 6), chunks.map(SabrChunk::sequenceNumber))
            assertContentEquals(byteArrayOf(8, 9, 10), chunks.last().data)
        }

    @Test
    fun initialSeekStartsAtContainingSegmentWhenPredecessorArrivesFirst() =
        runBlocking {
            val response =
                umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(7, durationMs = 62_000)) +
                    mediaSegment(headerId = 1, itag = 140, lastModified = 100, isInit = true, data = byteArrayOf(1, 2)) +
                    mediaResponseSegment(
                        headerId = 2,
                        sequenceNumber = 6,
                        startMs = 59_000,
                        data = byteArrayOf(5, 6, 7),
                    ) +
                    mediaResponseSegment(
                        headerId = 3,
                        sequenceNumber = 7,
                        startMs = 60_000,
                        data = byteArrayOf(8, 9, 10),
                    ) +
                    umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())
            val engine =
                MockEngine {
                    respond(
                        content = response,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 62_000),
                    initialPlayerTimeMs = 60_500,
                ).chunks().toList()

            assertEquals(listOf(null, 7), chunks.map(SabrChunk::sequenceNumber))
            assertContentEquals(byteArrayOf(8, 9, 10), chunks.last().data)
        }

    @Test
    fun omittedProtectionAllowanceContinuesWithProgressUntilAccepted() =
        runBlocking {
            val diagnostics = mutableListOf<SabrResponseDiagnostics>()
            var requests = 0
            val engine =
                MockEngine { request ->
                    assertEquals(requests.toString(), request.url.parameters["rn"])
                    requests++
                    respond(
                        content =
                            if (requests == 1) {
                                initializationAndSegmentResponse(endSegmentNumber = 1) +
                                    umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 2))
                            } else {
                                assertEquals(1_000, decodePlayerTimeMs((request.body as OutgoingContent.ByteArrayContent).bytes()))
                                finalSegmentResponse() +
                                    umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 1))
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    HttpClient(engine),
                    bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10),
                    onResponse = diagnostics::add,
                ).chunks().toList()

            assertEquals(listOf(null, 0, 1), chunks.map(SabrChunk::sequenceNumber))
            assertEquals(2, requests)
            assertEquals(2, diagnostics.first().protectionStatus)
            assertEquals(null, diagnostics.first().protectionMaxRetries)
            assertEquals(1, diagnostics.last().protectionStatus)
        }

    @Test
    fun statuslessProgressAfterOmittedPendingCanBeAccepted() =
        runBlocking {
            var requests = 0
            val diagnostics = mutableListOf<SabrResponseDiagnostics>()
            val engine =
                MockEngine {
                    val sequence = requests++
                    respond(
                        content =
                            when (sequence) {
                                0 -> {
                                    initializationAndSegmentResponse(endSegmentNumber = 2, durationMs = 3_000) +
                                        umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 2))
                                }

                                1 -> {
                                    mediaResponseSegment(3, 1, 1_000, byteArrayOf(8, 9, 10))
                                }

                                else -> {
                                    mediaResponseSegment(3, 2, 2_000, byteArrayOf(11, 12, 13)) +
                                        umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 1))
                                }
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val chunks =
                SabrAudioStream(
                    HttpClient(engine),
                    bootstrap().copy(durationMs = 3_000, contentLengthBytes = 13),
                    onResponse = diagnostics::add,
                ).chunks().toList()

            assertEquals(listOf(null, 0, 1, 2), chunks.map(SabrChunk::sequenceNumber))
            assertEquals(3, requests)
            assertEquals(listOf(2, null, 1), diagnostics.map(SabrResponseDiagnostics::protectionStatus))
        }

    @Test
    fun videoContinuesThroughOmittedProtectionAllowance() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(
                        content =
                            if (requests == 1) {
                                umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(1, 2_000, 248, 300)) +
                                    mediaSegment(headerId = 1, itag = 248, lastModified = 300, isInit = true, data = byteArrayOf(1)) +
                                    mediaSegment(headerId = 2, itag = 248, lastModified = 300, data = byteArrayOf(2)) +
                                    umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 2))
                            } else {
                                mediaResponseSegment(3, 1, 1_000, byteArrayOf(3), itag = 248, lastModified = 300) +
                                    umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 1))
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }
            val bootstrap =
                bootstrap().copy(
                    durationMs = 2_000,
                    selectedVideoFormat = SabrFormatId(248, 300),
                    selectedVideoWidth = 1920,
                    selectedVideoHeight = 1080,
                    selectedVideoContentLengthBytes = 3,
                )

            assertEquals(
                listOf(null, 0, 1),
                SabrVideoStream(HttpClient(engine), bootstrap).chunks().toList().map(SabrChunk::sequenceNumber),
            )
            assertEquals(2, requests)
        }

    @Test
    fun repeatedOmittedProtectionAllowanceHasFiniteTotalBudget() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    val sequence = requests++
                    respond(
                        content =
                            (
                                if (sequence == 0) {
                                    initializationAndSegmentResponse(endSegmentNumber = 6, durationMs = 7_000)
                                } else {
                                    mediaResponseSegment(3, sequence, sequence * 1_000L, byteArrayOf(8, 9, 10))
                                }
                            ) +
                                umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 2)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(HttpClient(engine), bootstrap().copy(durationMs = 7_000, contentLengthBytes = null)).chunks().toList()
                }

            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            assertEquals(6, requests)
        }

    @Test
    fun absentOrUnknownProtectionStatusCannotEvadeOmittedPendingBudget() =
        runBlocking {
            for (status in listOf(null, 0)) {
                var requests = 0
                val diagnostics = mutableListOf<SabrResponseDiagnostics>()
                val engine =
                    MockEngine {
                        val sequence = requests++
                        respond(
                            content =
                                if (sequence == 0) {
                                    initializationAndSegmentResponse(endSegmentNumber = 6, durationMs = 7_000) +
                                        umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 2))
                                } else {
                                    mediaResponseSegment(3, sequence, sequence * 1_000L, byteArrayOf(8, 9, 10)) +
                                        (
                                            status?.let {
                                                umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = it))
                                            } ?: byteArrayOf()
                                        )
                                },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    }

                val error =
                    assertFailsWith<SabrProtocolException> {
                        SabrAudioStream(
                            HttpClient(engine),
                            bootstrap().copy(durationMs = 7_000, contentLengthBytes = null),
                            onResponse = diagnostics::add,
                        ).chunks().toList()
                    }

                assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
                assertEquals(6, requests)
                assertEquals(1, diagnostics[5].selectedSegmentCount)
                assertEquals(status, diagnostics[5].protectionStatus)
            }
        }

    @Test
    fun completionWithoutAcceptedProtectionStatusFails() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(
                        content =
                            if (requests == 1) {
                                initializationAndSegmentResponse(endSegmentNumber = 1) +
                                    umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 2))
                            } else {
                                finalSegmentResponse()
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(HttpClient(engine), bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10)).bytes().toList()
                }
            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            assertEquals(2, requests)
        }

    @Test
    fun protectionPendingWithoutMediaIsTypedAsAttestationFailure() =
        runBlocking {
            val diagnostics = mutableListOf<SabrResponseDiagnostics>()
            val engine =
                MockEngine {
                    respond(
                        content = umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 2)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(
                        httpClient = HttpClient(engine),
                        bootstrap = bootstrap(),
                        onResponse = diagnostics::add,
                    ).bytes().toList()
                }

            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            assertEquals(2, assertNotNull(diagnostics.firstNotNullOfOrNull(SabrResponseDiagnostics::protectionStatus)))
        }

    @Test
    fun protectionPendingPersistsUntilLaterMediaStarvation() =
        runBlocking {
            var requests = 0
            val diagnostics = mutableListOf<SabrResponseDiagnostics>()
            val engine =
                MockEngine {
                    requests++
                    respond(
                        content =
                            if (requests == 1) {
                                initializationAndSegmentResponse(endSegmentNumber = 1) +
                                    umpPart(
                                        UmpPartType.STREAM_PROTECTION_STATUS,
                                        streamProtectionStatus(status = 2, maxRetries = 3),
                                    )
                            } else {
                                umpPart(UmpPartType.NEXT_REQUEST_POLICY, nextRequestPolicy(0))
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(
                        httpClient = HttpClient(engine),
                        bootstrap = bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10),
                        onResponse = diagnostics::add,
                    ).bytes().toList()
                }

            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            assertEquals(2, requests)
            assertEquals(
                1_000L,
                diagnostics
                    .first()
                    .bufferedRanges
                    .single()
                    .durationMs,
            )
        }

    @Test
    fun repeatedPendingStatusHonorsServerRetryBudgetEvenWithMedia() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    val media =
                        if (requests == 1) {
                            initializationAndSegmentResponse(endSegmentNumber = 2)
                        } else {
                            finalSegmentResponse(includeEndOfTrack = false)
                        }
                    respond(
                        content =
                            media +
                                umpPart(
                                    UmpPartType.STREAM_PROTECTION_STATUS,
                                    streamProtectionStatus(status = 2, maxRetries = 1),
                                ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(
                        httpClient = HttpClient(engine),
                        bootstrap = bootstrap().copy(durationMs = 3_000, contentLengthBytes = 13),
                    ).bytes().toList()
                }

            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            assertEquals(2, requests)
        }

    @Test
    fun statuslessProgressCannotEvadeExplicitPendingBudget() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(
                        content =
                            if (requests == 1) {
                                initializationAndSegmentResponse(endSegmentNumber = 2, durationMs = 3_000) +
                                    umpPart(UmpPartType.STREAM_PROTECTION_STATUS, streamProtectionStatus(status = 2, maxRetries = 1))
                            } else {
                                finalSegmentResponse(includeEndOfTrack = false)
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(HttpClient(engine), bootstrap().copy(durationMs = 3_000, contentLengthBytes = 13)).bytes().toList()
                }
            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            assertEquals(2, requests)
        }

    @Test
    fun zeroProtectionRetryBudgetFailsOnFirstPendingResponse() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(
                        content =
                            initializationAndSegmentResponse(endSegmentNumber = 1) +
                                umpPart(
                                    UmpPartType.STREAM_PROTECTION_STATUS,
                                    streamProtectionStatus(status = 2, maxRetries = 0),
                                ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(
                        httpClient = HttpClient(engine),
                        bootstrap = bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10),
                    ).bytes().toList()
                }

            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
            assertEquals(1, requests)
        }

    @Test
    fun protectionRequiredIsImmediateEvenWhenResponseContainsMedia() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content =
                            initializationAndSegmentResponse(endSegmentNumber = 1) +
                                umpPart(
                                    UmpPartType.STREAM_PROTECTION_STATUS,
                                    streamProtectionStatus(status = 3),
                                ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(
                        httpClient = HttpClient(engine),
                        bootstrap = bootstrap().copy(durationMs = 2_000, contentLengthBytes = 10),
                    ).bytes().toList()
                }

            assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
        }

    @Test
    fun segmentOvershootStopsBeforeAnotherRequestAndFailsValidation() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(
                        content =
                            initializationAndSegmentResponse(endSegmentNumber = 0, durationMs = 3_000) +
                                finalSegmentResponse(includeEndOfTrack = false),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                    )
                }

            assertFailsWith<SabrProtocolException> {
                SabrAudioStream(
                    httpClient = HttpClient(engine),
                    bootstrap = bootstrap().copy(durationMs = 3_000, contentLengthBytes = 13),
                ).bytes().toList()
            }

            assertEquals(1, requests)
        }

    private fun completeResponse(endSegmentNumber: Int): ByteArray = initializationResponsePrefix(endSegmentNumber) + mediaResponseSuffix()

    private fun initializationResponsePrefix(
        endSegmentNumber: Int,
        durationMs: Long = 1_000,
    ): ByteArray =
        umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(endSegmentNumber, durationMs = durationMs)) +
            umpPart(UmpPartType.MEDIA_HEADER, mediaHeader(headerId = 1, isInit = true, sequenceNumber = 0, contentLength = 4)) +
            umpPart(UmpPartType.MEDIA, byteArrayOf(1, 1, 2, 3, 4)) +
            umpPart(UmpPartType.MEDIA_END, byteArrayOf(1))

    private fun mediaResponseSuffix(): ByteArray =
        umpPart(UmpPartType.MEDIA_HEADER, mediaHeader(headerId = 2, isInit = false, sequenceNumber = 0, contentLength = 3)) +
            umpPart(UmpPartType.MEDIA, byteArrayOf(2, 5, 6, 7)) +
            umpPart(UmpPartType.MEDIA_END, byteArrayOf(2)) +
            umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())

    private fun initializationAndSegmentResponse(
        endSegmentNumber: Int,
        targetAudioReadaheadMs: Int? = null,
        durationMs: Long = 2_000,
    ): ByteArray =
        (targetAudioReadaheadMs?.let { umpPart(UmpPartType.NEXT_REQUEST_POLICY, nextRequestPolicy(it)) } ?: byteArrayOf()) +
            umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(endSegmentNumber, durationMs = durationMs)) +
            umpPart(UmpPartType.MEDIA_HEADER, mediaHeader(headerId = 1, isInit = true, sequenceNumber = 0, contentLength = 4)) +
            umpPart(UmpPartType.MEDIA, byteArrayOf(1, 1, 2, 3, 4)) +
            umpPart(UmpPartType.MEDIA_END, byteArrayOf(1)) +
            umpPart(UmpPartType.MEDIA_HEADER, mediaHeader(headerId = 2, isInit = false, sequenceNumber = 0, contentLength = 3)) +
            umpPart(UmpPartType.MEDIA, byteArrayOf(2, 5, 6, 7)) +
            umpPart(UmpPartType.MEDIA_END, byteArrayOf(2))

    private fun finalSegmentResponse(includeEndOfTrack: Boolean = true): ByteArray =
        mediaResponseSegment(headerId = 3, sequenceNumber = 1, startMs = 1_000, data = byteArrayOf(8, 9, 10)) +
            if (includeEndOfTrack) umpPart(UmpPartType.END_OF_TRACK, byteArrayOf()) else byteArrayOf()

    private fun mediaResponseSegment(
        headerId: Int,
        sequenceNumber: Int,
        startMs: Long,
        data: ByteArray,
        itag: Int = 140,
        lastModified: Long = 100,
        durationMs: Long = 1_000,
    ): ByteArray =
        umpPart(
            UmpPartType.MEDIA_HEADER,
            mediaHeader(
                headerId = headerId,
                isInit = false,
                sequenceNumber = sequenceNumber,
                contentLength = data.size,
                startMs = startMs,
                itag = itag,
                lastModified = lastModified,
                durationMs = durationMs,
            ),
        ) +
            umpPart(UmpPartType.MEDIA, byteArrayOf(headerId.toByte()) + data) +
            umpPart(UmpPartType.MEDIA_END, byteArrayOf(headerId.toByte()))

    private fun seekResponse(
        sequenceNumber: Int,
        startMs: Long,
    ): ByteArray =
        umpPart(UmpPartType.FORMAT_INITIALIZATION_METADATA, initialization(sequenceNumber, durationMs = startMs + 1_000)) +
            umpPart(UmpPartType.MEDIA_HEADER, mediaHeader(headerId = 1, isInit = true, sequenceNumber = 0, contentLength = 4)) +
            umpPart(UmpPartType.MEDIA, byteArrayOf(1, 1, 2, 3, 4)) +
            umpPart(UmpPartType.MEDIA_END, byteArrayOf(1)) +
            umpPart(
                UmpPartType.MEDIA_HEADER,
                mediaHeader(
                    headerId = 2,
                    isInit = false,
                    sequenceNumber = sequenceNumber,
                    contentLength = 3,
                    startMs = startMs,
                ),
            ) +
            umpPart(UmpPartType.MEDIA, byteArrayOf(2, 5, 6, 7)) +
            umpPart(UmpPartType.MEDIA_END, byteArrayOf(2)) +
            umpPart(UmpPartType.END_OF_TRACK, byteArrayOf())

    private fun streamProtectionStatus(
        status: Int,
        maxRetries: Int? = null,
    ): ByteArray =
        ProtoWriter()
            .apply {
                int32(1, status)
                maxRetries?.let { int32(2, it) }
            }.toByteArray()

    private fun contextUpdate(type: Int): ByteArray =
        ProtoWriter()
            .apply {
                int32(1, type)
                bytes(3, byteArrayOf(type.toByte()))
            }.toByteArray()

    private fun nextRequestPolicy(targetAudioReadaheadMs: Int): ByteArray =
        ProtoWriter()
            .apply {
                int32(1, targetAudioReadaheadMs)
                int32(3, 5_000)
            }.toByteArray()

    private fun initialization(
        endSegmentNumber: Int,
        durationMs: Long = 1_000,
        itag: Int = 140,
        lastModified: Long = 100,
    ): ByteArray =
        ProtoWriter()
            .apply {
                message(2) {
                    int32(1, itag)
                    int64(2, lastModified)
                    string(3, "x")
                }
                int64(3, durationMs)
                int64(4, endSegmentNumber.toLong())
            }.toByteArray()

    private fun mediaHeader(
        headerId: Int,
        isInit: Boolean,
        sequenceNumber: Int,
        contentLength: Int,
        startMs: Long = 0,
        itag: Int = 140,
        lastModified: Long = 100,
        durationMs: Long = 1_000,
    ): ByteArray =
        ProtoWriter()
            .apply {
                int32(1, headerId)
                int32(3, itag)
                int64(4, lastModified)
                string(5, "x")
                if (isInit) bool(8, true)
                int32(9, sequenceNumber)
                if (!isInit) {
                    if (startMs != 0L) int64(11, startMs)
                    int64(12, durationMs)
                }
                int64(14, contentLength.toLong())
            }.toByteArray()

    private fun mediaSegment(
        headerId: Int,
        itag: Int,
        lastModified: Long,
        isInit: Boolean = false,
        data: ByteArray,
    ): ByteArray =
        umpPart(
            UmpPartType.MEDIA_HEADER,
            mediaHeader(
                headerId = headerId,
                isInit = isInit,
                sequenceNumber = 0,
                contentLength = data.size,
                itag = itag,
                lastModified = lastModified,
            ),
        ) +
            umpPart(UmpPartType.MEDIA, byteArrayOf(headerId.toByte()) + data) +
            umpPart(UmpPartType.MEDIA_END, byteArrayOf(headerId.toByte()))

    private fun decodePlayerTimeMs(request: ByteArray): Long {
        val requestReader = ProtoReader(request)
        var clientAbr = byteArrayOf()
        while (requestReader.hasRemaining) {
            val tag = requestReader.tag()
            if (tag.field == 1) {
                clientAbr = requestReader.bytes()
                break
            }
            requestReader.skip(tag)
        }
        val abrReader = ProtoReader(clientAbr)
        while (abrReader.hasRemaining) {
            val tag = abrReader.tag()
            if (tag.field == 28) return abrReader.varint()
            abrReader.skip(tag)
        }
        return 0
    }

    private fun bootstrap() =
        SabrBootstrap(
            videoId = "video-1",
            serverAbrStreamingUrl = "https://example.googlevideo.com/videoplayback?sabr=1",
            videoPlaybackUstreamerConfig = byteArrayOf(1, 2, 3),
            clientName = 3,
            clientVersion = "21.02.35",
            audioFormat = SabrFormatId(140, 100, "x"),
            discardVideoFormat = SabrFormatId(160, 200),
            discardVideoHeight = 144,
            durationMs = 1_000,
            contentLengthBytes = 7,
            mimeType = "audio/mp4",
        )

    private fun umpPart(
        type: Int,
        data: ByteArray,
    ): ByteArray = umpVarint(type) + umpVarint(data.size) + data

    private fun umpVarint(value: Int): ByteArray =
        when {
            value < 128 -> byteArrayOf(value.toByte())
            value < 16_384 -> byteArrayOf(((value and 0x3f) or 0x80).toByte(), (value ushr 6).toByte())
            else -> error("Test payload too large")
        }
}
