package com.metrolist.innertubex.cipher

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class CipherDispatcherTest {
    private fun depth(remaining: Int): Int {
        val padding = LongArray(1) { remaining.toLong() }
        return if (remaining == 0) padding.size else depth(remaining - 1) + padding.size
    }

    @Test
    fun cipherWorkHasMoreStackThanAGcdWorker() =
        runBlocking {
            // Roughly 100 bytes or more per frame, well past a 512 KB stack and within the dispatcher's.
            assertEquals(30_001, withContext(cipherDispatcher) { depth(30_000) })
        }
}
