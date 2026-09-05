package com.metrolist.innertubex

import com.metrolist.innertubex.cipher.QuickJsEngine
import com.metrolist.innertubex.cipher.readYtEjsSolverScript
import com.metrolist.innertubex.utils.sha1
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeRuntimeTest {
    @Test
    fun nativeImplementationsWork() =
        runBlocking {
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc"))
            assertEquals("f59cd0c507ed083a8f115e3071703d2cd5ebbd74", sha1(readYtEjsSolverScript("yt.solver.core.min.js")))
            assertEquals("fda1e5c634b4fa592983c7fdfd81d9a584b58571", sha1(readYtEjsSolverScript("yt.solver.lib.min.js")))

            val engine = QuickJsEngine()
            try {
                engine.initialize()
                assertEquals("2", engine.evaluate("1 + 1", maxResultLength = 1))
            } finally {
                engine.dispose()
            }
        }
}
