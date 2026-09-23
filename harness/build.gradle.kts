plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(21) }
application { mainClass.set("com.metrolist.innertubex.harness.MainKt") }

dependencies {
    implementation(project(":", configuration = "desktopRuntimeElements"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("io.ktor:ktor-client-okhttp:${libs.versions.ktor.get()}")
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.ktor.client.mock)
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
