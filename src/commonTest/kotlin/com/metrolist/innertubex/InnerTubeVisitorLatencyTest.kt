package com.metrolist.innertubex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InnerTubeVisitorLatencyTest {
    @Test
    fun callerHoldingThePrePublicationSnapshotReusesThePublishedVisitor() =
        runBlocking {
            val engine = MockEngine { respond(serviceWorkerResponse("published"), HttpStatusCode.OK) }
            val innerTube = InnerTube(HttpClient(engine))
            val before = innerTube.sessionSnapshot()
            assertEquals("published", innerTube.fetchFreshVisitorData(before))
            // Publication bumped the generation; the stale snapshot must still hit the cache.
            assertEquals("published", innerTube.fetchFreshVisitorData(before))
            assertEquals(1, engine.requestHistory.size)
            innerTube.close()
        }

    @Test
    fun failedBootstrapIsNotRepeatedImmediatelyUnlessForced() =
        runBlocking {
            val engine = MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }
            val innerTube = InnerTube(HttpClient(engine), retryDelay = {})
            val session = innerTube.sessionSnapshot()
            assertNull(innerTube.fetchFreshVisitorData(session))
            val firstAttemptRequests = engine.requestHistory.size
            assertNull(innerTube.fetchFreshVisitorData(session))
            assertEquals(firstAttemptRequests, engine.requestHistory.size)
            assertNull(innerTube.fetchFreshVisitorData())
            assertEquals(firstAttemptRequests * 2, engine.requestHistory.size)
            innerTube.close()
        }

    @Test
    fun stalledServiceWorkerStillLeavesTimeForTheHomepage() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.host == "www.youtube.com") CompletableDeferred<Unit>().await()
                    respond("""<script>ytcfg.set({"VISITOR_DATA":"homepage"})</script>""", HttpStatusCode.OK)
                }
            val innerTube = InnerTube(HttpClient(engine), retryDelay = {})
            assertEquals("homepage", withTimeout(8_000) { innerTube.fetchFreshVisitorData() })
            innerTube.close()
        }

    private fun serviceWorkerResponse(visitorData: String): String {
        val visitorFields = MutableList<String?>(14) { null }.also { it[13] = visitorData }
        return visitorFields.joinToString(prefix = ")]}'\n\n[[\"yt.sw.adr\",null,[[[", postfix = "]]]]]") { value ->
            value?.let { "\"$it\"" } ?: "null"
        }
    }
}
