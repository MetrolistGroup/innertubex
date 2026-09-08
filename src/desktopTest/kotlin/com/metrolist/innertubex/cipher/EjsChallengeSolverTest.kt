package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.utils.sha1
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EjsChallengeSolverTest {
    @Test
    fun persistsAndRestoresPreprocessedPlayerAcrossSolvers() =
        runBlocking {
            val stored = mutableMapOf<String, String>()
            var reads = 0
            val read: suspend (String) -> String? = { key ->
                reads += 1
                stored[key]
            }
            val write: suspend (String, String?) -> Unit = { key, value ->
                if (value == null) stored.remove(key) else stored[key] = value
            }

            val firstEngine = QuickJsEngine()
            try {
                val firstSolver = EjsChallengeSolver(firstEngine, InnerTubeLogger.NONE)
                firstSolver.setPreprocessedPlayerCache(read, write)
                val challenge = "private_challenge_not_for_persistent_storage"
                val first = firstSolver.solve(PLAYER_URL, PLAYER_CODE, listOf("sig" to listOf(challenge)))
                val memoryHit = firstSolver.solve(PLAYER_URL, "", listOf("n" to listOf("value")))

                assertEquals(challenge.reversed(), first.sigByChallenge[challenge])
                assertNotNull(first.preprocessedPlayer)
                assertFalse(stored.values.single().contains(challenge))
                assertFalse(stored.values.single().contains(challenge.reversed()))
                assertEquals("value_done", memoryHit.nByChallenge["value"])
                assertEquals(1, reads)
            } finally {
                firstEngine.dispose()
            }

            val secondEngine = QuickJsEngine()
            try {
                val secondSolver = EjsChallengeSolver(secondEngine, InnerTubeLogger.NONE)
                secondSolver.setPreprocessedPlayerCache(read, write)
                val restored = secondSolver.solve(PLAYER_URL, "", listOf("n" to listOf("fresh")))

                assertEquals("fresh_done", restored.nByChallenge["fresh"])
                assertEquals(2, reads)
                assertTrue(stored.keys.single().matches(Regex("[a-f0-9]{40}")))
            } finally {
                secondEngine.dispose()
            }
        }

    @Test
    fun cacheKeysIsolatePlayerAndSolverVersions() {
        val bundleA = sha1("solver-a")
        val bundleB = sha1("solver-b")
        val key = assertNotNull(preprocessedPlayerCacheKey(PLAYER_URL, bundleA))

        assertTrue(key.matches(Regex("[a-f0-9]{40}")))
        assertFalse(key.contains("youtube"))
        assertNotEquals(key, preprocessedPlayerCacheKey(OTHER_PLAYER_URL, bundleA))
        assertNotEquals(key, preprocessedPlayerCacheKey(PLAYER_URL, bundleB))
        assertNull(preprocessedPlayerCacheKey("https://example.com/player.js", bundleA))
    }

    @Test
    fun badStoredPlayersAreDeletedBeforeFullPlayerFallback() =
        runBlocking {
            for (badValue in listOf("not javascript", "x".repeat(8 * 1024 * 1024 + 1))) {
                val engine = QuickJsEngine()
                val writes = mutableListOf<String?>()
                try {
                    val solver = EjsChallengeSolver(engine, InnerTubeLogger.NONE)
                    solver.setPreprocessedPlayerCache(
                        read = { badValue },
                        write = { _, value -> writes += value },
                    )

                    val result = solver.solve(PLAYER_URL, PLAYER_CODE, listOf("sig" to listOf("abc")))

                    assertEquals("cba", result.sigByChallenge["abc"])
                    assertNull(writes.first())
                    assertNotNull(writes.last())
                } finally {
                    engine.dispose()
                }
            }
        }

    @Test
    fun cacheCallbackFailuresDoNotBreakFullPlayerSolving() =
        runBlocking {
            val engine = QuickJsEngine()
            try {
                val solver = EjsChallengeSolver(engine, InnerTubeLogger.NONE)
                solver.setPreprocessedPlayerCache(
                    read = { error("read failed") },
                    write = { _, _ -> error("write failed") },
                )

                val result = solver.solve(PLAYER_URL, PLAYER_CODE, listOf("sig" to listOf("abc")))

                assertEquals("cba", result.sigByChallenge["abc"])
            } finally {
                engine.dispose()
            }
        }

    @Test
    fun oversizedChallengesAreRejectedBeforeInitializingQuickJs() =
        runBlocking {
            val solver = EjsChallengeSolver(QuickJsEngine(), InnerTubeLogger.NONE)

            val result =
                solver.solve(
                    playerUrl = "https://www.youtube.com/s/player/12345678/player.js",
                    fullPlayerJs = "var player = true;",
                    requestOrder = listOf("sig" to listOf("x".repeat(64 * 1024 + 1))),
                )

            assertTrue(result.sigByChallenge.isEmpty())
            assertTrue(result.nByChallenge.isEmpty())
            assertTrue(result.preprocessedPlayer == null)
        }

    private companion object {
        const val PLAYER_URL = "https://www.youtube.com/s/player/12345678/player.js"
        const val OTHER_PLAYER_URL = "https://www.youtube.com/s/player/87654321/player.js"
        val PLAYER_CODE =
            """
            (function() {
              function PlayerUrl(url, key, signature) {
                this.values = {s: signature || null, n: null};
              }
              PlayerUrl.prototype.set = function(key, value) { this.values[key] = value; };
              PlayerUrl.prototype.get = function(key) { return this.values[key]; };
              PlayerUrl.prototype.clone = function() { return this; };
              PlayerUrl.prototype.apply = function() {
                if (this.values.s) this.values.s = this.values.s.split("").reverse().join("");
                if (this.values.n) this.values.n += "_done";
              };
              function transform(url, key, signature) {
                var value = new PlayerUrl(url, key, signature);
                value.set("alr", "yes");
                return value;
              }
            }).call(this);
            """.trimIndent()
    }
}
