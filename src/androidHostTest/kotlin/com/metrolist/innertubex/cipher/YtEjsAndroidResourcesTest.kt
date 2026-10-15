package com.metrolist.innertubex.cipher

import com.metrolist.innertubex.utils.sha1
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class YtEjsAndroidResourcesTest {
    @Test
    fun publishedAarContainsTheSolverScriptsUsedByFullSolveTests() {
        val expected =
            mapOf(
                "yt_ejs/yt.solver.core.min.js" to "f59cd0c507ed083a8f115e3071703d2cd5ebbd74",
                "yt_ejs/yt.solver.lib.min.js" to "fda1e5c634b4fa592983c7fdfd81d9a584b58571",
            )
        val packaged = mutableMapOf<String, String>()
        ZipFile(assertNotNull(System.getProperty("innertubex.test.aar"))).use { aar ->
            val classes = assertNotNull(aar.getEntry("classes.jar"))
            JarInputStream(aar.getInputStream(classes)).use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    if (entry.name in expected) {
                        packaged[entry.name] = sha1(jar.readBytes().decodeToString())
                    }
                }
            }
        }
        assertEquals(expected, packaged)
        for ((path, hash) in expected) {
            assertEquals(hash, sha1(readYtEjsSolverScript(path.substringAfter('/'))))
        }
    }
}
