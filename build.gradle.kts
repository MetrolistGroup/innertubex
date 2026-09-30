@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

val generateYtEjsNativeScripts =
    tasks.register("generateYtEjsNativeScripts") {
        val scripts = layout.projectDirectory.dir("src/commonMain/resources/yt_ejs")
        val out = layout.buildDirectory.dir("generated/ytEjsNative")
        inputs.dir(scripts)
        outputs.dir(out)
        doLast {
            val names = listOf("yt.solver.core.min.js", "yt.solver.lib.min.js")
            val entries =
                names.joinToString(",\n") { name ->
                    val b64 = Base64.getEncoder().encodeToString(scripts.file(name).asFile.readBytes())
                    "        \"$name\" to \"\"\"\n" + b64.chunked(100).joinToString("\n") + "\"\"\".replace(\"\\n\", \"\")"
                }
            val dir =
                out
                    .get()
                    .asFile
                    .resolve("com/metrolist/innertubex/cipher")
                    .apply { mkdirs() }
            dir.resolve("YtEjsScriptLoader.native.kt").writeText(
                listOf(
                    "package com.metrolist.innertubex.cipher",
                    "",
                    "import kotlin.io.encoding.Base64",
                    "import kotlin.io.encoding.ExperimentalEncodingApi",
                    "",
                    "@OptIn(ExperimentalEncodingApi::class)",
                    "internal actual fun readYtEjsSolverScript(fileName: String): String =",
                    "    Base64.decode(EMBEDDED[fileName] ?: error(\"Missing embedded script: \$fileName\")).decodeToString()",
                    "",
                    "private val EMBEDDED =",
                    "    mapOf(",
                    entries,
                    "    )",
                    "",
                ).joinToString("\n"),
            )
        }
    }

kotlin {
    android {
        namespace = "com.metrolist.innertubex"
        compileSdk =
            libs.versions.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        withHostTest {}
    }

    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(21)
    applyDefaultHierarchyTemplate()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.quickjs)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        val jvmCommonMain = create("jvmCommonMain") { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        getByName("desktopMain").dependsOn(jvmCommonMain)
        nativeMain {
            kotlin.srcDir(generateYtEjsNativeScripts)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit4)
        }
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
}

apiValidation { ignoredProjects.add("harness") }

tasks.named("allTests") { dependsOn(":harness:test") }

configure<KtlintExtension> {
    version.set("1.8.0")
    additionalEditorconfig.set(
        mapOf(
            "ktlint_standard_kdoc" to "disabled",
        ),
    )
    filter {
        exclude { element -> element.file.invariantSeparatorsPath.contains("/build/generated/") }
    }
}

mavenPublishing {
    pom {
        name.set("InnerTubeX")
        description.set(
            "Extended Android and JVM Kotlin Multiplatform client for YouTube's InnerTube APIs, SABR audio/video, and player cipher deobfuscation.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/MetrolistGroup/innertubex")

        licenses {
            license {
                name.set("GNU General Public License, version 3")
                url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("metrolistgroup")
                name.set("Metrolist contributors")
                url.set("https://github.com/MetrolistGroup")
            }
        }

        scm {
            url.set("https://github.com/MetrolistGroup/innertubex")
            connection.set("scm:git:git://github.com/MetrolistGroup/innertubex.git")
            developerConnection.set("scm:git:ssh://git@github.com/MetrolistGroup/innertubex.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/MetrolistGroup/innertubex/issues")
        }
    }
}
