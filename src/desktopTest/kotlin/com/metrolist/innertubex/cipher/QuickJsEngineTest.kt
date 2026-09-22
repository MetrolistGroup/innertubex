package com.metrolist.innertubex.cipher

import com.dokar.quickjs.QuickJsException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QuickJsEngineTest {
    @Test
    fun recursiveJavascriptFailsWithoutOverflowingTheNativeStack() =
        runBlocking {
            val engine = QuickJsEngine()
            try {
                engine.initialize()

                assertFailsWith<QuickJsException> {
                    engine.evaluate("(function recurse() { return recurse(); })()", maxResultLength = 1)
                }
                Unit
            } finally {
                engine.dispose()
            }
        }

    @Test
    fun evaluatePreservesMultilineWhitespaceAndBoundsResults() =
        runBlocking {
            val engine = QuickJsEngine()
            try {
                engine.initialize()
                engine.execute("function oversized() { return 'x'.repeat(300000); }")

                val source = "  `  leading\n    \\\\ \" escaped`"
                assertEquals("  leading\n    \\ \" escaped", engine.evaluate(source, maxResultLength = 1024))
                assertNull(engine.callFunction("oversized", "input"))
                assertEquals("", engine.evaluate("'x'.repeat(300000)", maxResultLength = 1024))
            } finally {
                engine.dispose()
            }
        }
}
