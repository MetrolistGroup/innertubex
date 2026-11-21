@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

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
            implementation("com.github.MetrolistGroup.quackjs:quackjs:0.1.4")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinxCoroutines.get()}")
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        val jvmCommonMain = create("jvmCommonMain") { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        getByName("desktopMain").dependsOn(jvmCommonMain)
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

// Kotlin finalizes publication artifacts after evaluating the build scripts.
gradle.projectsEvaluated {
    publishing.publications.withType<MavenPublication>().configureEach {
        if (artifacts.none { it.classifier == "sources" }) {
            artifact(tasks.named(if (name == "kotlinMultiplatform") "metadataSourcesJar" else "${name}SourcesJar"))
        }
        // JitPack strips the metadata classifier from native URLs; retain a matching copy.
        artifacts.filter { it.classifier == "metadata" }.forEach { metadata ->
            artifact(metadata.file) { builtBy(metadata.buildDependencies) }
        }
    }
}

apiValidation { ignoredProjects.add("harness") }

tasks.named("allTests") { dependsOn(":harness:test") }

tasks.withType<Test>().configureEach {
    if (name == "testAndroidHostTest") {
        val aar = tasks.named<Zip>("bundleAndroidMainAar").flatMap { it.archiveFile }
        inputs.file(aar)
        systemProperty("innertubex.test.aar", aar.get().asFile.absolutePath)
    }
}

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
    // JitPack drops classifiers from Gradle source-variant URLs; attach the platform JARs separately.
    configure(KotlinMultiplatform(sourcesJar = SourcesJar.None()))
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
