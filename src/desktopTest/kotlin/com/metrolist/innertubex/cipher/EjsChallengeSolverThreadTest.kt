package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.EjsPlayerFixture.OTHER_PLAYER_URL
import com.metrolist.innertubex.cipher.EjsPlayerFixture.PLAYER_CODE
import com.metrolist.innertubex.cipher.EjsPlayerFixture.PLAYER_URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EjsChallengeSolverThreadTest {
    @Test
    fun idleReleaseStopsTheThreadAndCachedOutputsSurviveWithoutRestartingIt() =
        runBlocking {
            val engine = QuickJsEngine()
            // Slow CI workers can idle-release a 100ms runtime before the thread count is sampled.
            val solver = EjsChallengeSolver(engine, InnerTubeLogger.NONE, idleTimeoutMs = 1_000)
            val threadsBefore = quickJsThreads()
            val stored = mutableMapOf<String, String>()
            var reads = 0
            solver.setPreprocessedPlayerCache(
                read = {
                    reads++
                    stored[it]
                },
                write = { key, value -> if (value == null) stored.remove(key) else stored[key] = value },
            )
            try {
                val requests = listOf("sig" to listOf("abc"), "n" to listOf("abc"))
                val first = solver.solve(PLAYER_URL, PLAYER_CODE, requests)
                assertEquals("cba", first.sigByChallenge["abc"])
                assertEquals("abc_done", first.nByChallenge["abc"])
                val createdThreads = quickJsThreads() - threadsBefore
                assertEquals(1, createdThreads.size)
                awaitReleased(engine)
                withTimeout(5_000) { while (createdThreads.any { it.isAlive }) delay(10) }

                val cached = solver.solve(PLAYER_URL, { error("Cached results must not load player JS") }, requests)
                assertEquals(first.sigByChallenge, cached.sigByChallenge)
                assertEquals(first.nByChallenge, cached.nByChallenge)
                assertNull(cached.preprocessedPlayer)
                assertFailsWith<IllegalStateException> { engine.evaluate("1", 1) }

                val next = solver.solve(PLAYER_URL, { error("Preprocessing should survive idle release") }, listOf("n" to listOf("new")))
                assertEquals("new_done", next.nByChallenge["new"])
                assertEquals(1, reads)
                assertEquals("1", engine.evaluate("__itxPlayers.size", 8))
                // Same challenge in a different player must not reuse the old player's output.
                val other = solver.solve(OTHER_PLAYER_URL, PLAYER_CODE.replace("_done", "_other"), listOf("n" to listOf("abc")))
                assertEquals("abc_other", other.nByChallenge["abc"])
            } finally {
                solver.dispose()
            }
        }

    private suspend fun awaitReleased(engine: QuickJsEngine) =
        withTimeout(5_000) {
            while (true) {
                try {
                    engine.evaluate("1", 1)
                } catch (_: IllegalStateException) {
                    break
                }
                delay(10)
            }
        }

    private fun quickJsThreads(): Set<Thread> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.name == "InnerTubeX-QuickJS" }
            .toSet()
}
