package com.metrolist.innertubex

import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFieldMaskTest {
    @Test
    fun maskRequestsEveryFieldThePlayerModelDecodes() {
        val missing = mutableListOf<String>()

        fun walk(
            descriptor: SerialDescriptor,
            selection: Map<String, Map<String, *>?>,
            path: String,
        ) {
            val target = if (descriptor.kind == StructureKind.LIST) descriptor.getElementDescriptor(0) else descriptor
            if (target.kind != StructureKind.CLASS) return
            target.elementNames.zip(target.elementDescriptors.toList()).forEach { (name, child) ->
                // Legacy JSON alias with no server-side field; the server rejects it in a mask.
                if (name == "cipher") return@forEach
                when {
                    name !in selection -> {
                        missing += "$path.$name"
                    }

                    // Selected whole, which includes every child.
                    selection[name] == null -> {
                        Unit
                    }

                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        walk(child, selection.getValue(name) as Map<String, Map<String, *>?>, "$path.$name")
                    }
                }
            }
        }
        walk(PlayerResponse.serializer().descriptor, parseMask(PLAYER_RESPONSE_TV_FIELD_MASK), "PlayerResponse")
        assertEquals(emptyList(), missing)
    }

    @Test
    fun preRollWaitCountsStartAdsAndPrefersTheSkipOffset() {
        fun ad(
            kind: String,
            fields: String,
        ) = """{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"$kind"}},""" +
            """"renderer":{"instreamVideoAdRenderer":{$fields}}}}"""
        val placements =
            listOf(
                ad("AD_PLACEMENT_KIND_START", """"playerVars":"a=1&length_seconds=30&b=2""""),
                ad("AD_PLACEMENT_KIND_START", """"playerVars":"length_seconds=6","skipOffsetMilliseconds":5000"""),
                ad("AD_PLACEMENT_KIND_MILLISECONDS", """"playerVars":"length_seconds=15""""),
            ).joinToString(",")
        val response =
            Json { ignoreUnknownKeys = true }
                .decodeFromString<PlayerResponse>("""{"playabilityStatus":{"status":"OK"},"adPlacements":[$placements]}""")

        assertEquals(35_000L, response.preRollWaitMs())
        assertEquals(0L, PlayerResponse(PlayerResponse.PlayabilityStatus("OK")).preRollWaitMs())
    }

    @Test
    fun preRollWaitRejectsNegativesAndBoundsWireDurationsAndTotals() {
        fun wait(vararg fields: String): Long {
            val placements =
                fields.joinToString(",") {
                    """{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"AD_PLACEMENT_KIND_START"}},""" +
                        """"renderer":{"instreamVideoAdRenderer":{$it}}}}"""
                }
            return Json
                .decodeFromString<PlayerResponse>(
                    """{"playabilityStatus":{"status":"OK"},"adPlacements":[$placements]}""",
                ).preRollWaitMs()
        }
        assertEquals(0L, wait(""""skipOffsetMilliseconds":-1"""))
        assertEquals(0L, wait(""""playerVars":"length_seconds=-9223372036854775809""""))
        assertEquals(6_000L, wait(""""skipOffsetMilliseconds":-1,"playerVars":"length_seconds=6""""))
        assertEquals(35_000L, wait(""""skipOffsetMilliseconds":9223372036854775807"""))
        assertEquals(35_000L, wait(""""playerVars":"length_seconds=9223372036854775807""""))
        assertEquals(35_000L, wait(""""playerVars":"length_seconds=9223372036854775808""""))
        assertEquals(35_000L, wait(*Array(10) { """"skipOffsetMilliseconds":30000""" }))
    }

    @Test
    fun maskPreservesDrmMarkersBeforeFormatSelection() {
        val streaming = parseMask(PLAYER_RESPONSE_FIELD_MASK)["streamingData"] as Map<*, *>
        assertTrue("drmParams" in streaming)
        assertTrue("licenseInfos" in streaming)
        for (selection in listOf(streaming["formats"] as Map<*, *>, streaming["adaptiveFormats"] as Map<*, *>)) {
            assertTrue("drmFamilies" in selection)
            assertTrue("drmTrackType" in selection)
        }
    }

    @Test
    fun maskOmitsDrmPathsTheServerRejects() {
        // Each of these returned HTTP 400 live, which would disable the whole mask.
        val streaming = parseMask(PLAYER_RESPONSE_FIELD_MASK)["streamingData"] as Map<*, *>
        val format = streaming["adaptiveFormats"] as Map<*, *>
        for (path in listOf("drmFamilies", "drmTrackType", "widevine", "contentProtection")) assertTrue(path !in streaming)
        for (path in listOf("drmParams", "licenseInfos", "widevine", "contentProtection")) assertTrue(path !in format)
    }

    @Test
    fun maskParserKeepsSelectionsScopedToTheirParent() {
        val parsed = parseMask("a(x,y(z)),b")
        assertEquals(setOf("a", "b"), parsed.keys)
        assertNull(parsed["b"])
        assertEquals(setOf("x", "y"), (parsed["a"] as Map<*, *>).keys)
    }

    /** Parses `a(b,c(d)),e` into nested maps; a null value means the field is selected whole. */
    private fun parseMask(mask: String): Map<String, Map<String, *>?> {
        var index = 0

        fun parseList(): Map<String, Map<String, *>?> {
            val fields = LinkedHashMap<String, Map<String, *>?>()
            while (index < mask.length && mask[index] != ')') {
                val start = index
                while (index < mask.length && mask[index] !in "(),") index++
                val name = mask.substring(start, index)
                val children =
                    if (index < mask.length && mask[index] == '(') {
                        index++
                        parseList().also { index++ }
                    } else {
                        null
                    }
                fields[name] = children
                if (index < mask.length && mask[index] == ',') index++
            }
            return fields
        }
        return parseList()
    }

    @Test
    fun rejectedMaskFallsBackToAFullResponseAndIsDisabled() =
        runBlocking {
            val masks = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    val mask = request.headers["X-Goog-FieldMask"]
                    masks += mask
                    if (mask != null) {
                        respond("""{"error":{"code":400}}""", HttpStatusCode.BadRequest)
                    } else {
                        respond("""{"playabilityStatus":{"status":"OK"}}""", HttpStatusCode.OK)
                    }
                }
            val innerTube = InnerTube(HttpClient(engine) { install(ContentNegotiation) { json() } }, retryDelay = {})
            repeat(2) {
                val body =
                    innerTube.playerTextWithSessionBound(
                        YouTubeClient.VISIONOS,
                        "video",
                        null,
                        null,
                        innerTube.sessionSnapshot(),
                        null,
                        4096,
                    )
                assertTrue(body.contains("OK"))
            }
            assertEquals(listOf(PLAYER_RESPONSE_FIELD_MASK, null, null), masks)
            assertNull(masks.last())
            innerTube.close()
        }
}
