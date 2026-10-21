package com.metrolist.innertubex.cipher

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertFalse

class CipherAndroidPackagingTest {
    @Test
    fun aarDoesNotBundleJavaScriptSolversOrNativeRuntime() {
        val aar = File(checkNotNull(System.getProperty("innertubex.test.aar")))
        ZipFile(aar).use { archive ->
            val names =
                archive
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toMutableList()
            ZipInputStream(archive.getInputStream(archive.getEntry("classes.jar"))).use { classes ->
                names += generateSequence { classes.nextEntry?.name }.toList()
            }
            assertFalse(names.any { "yt_ejs" in it || "quickjs" in it.lowercase() || it.endsWith(".so") }, names.toString())
        }
    }
}
