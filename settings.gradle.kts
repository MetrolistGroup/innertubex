@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MetrolistGroup.quackjs") }
        }
    }
}

rootProject.name = "innertubex"
if (providers.gradleProperty("useLocalQuackJS").isPresent) {
    includeBuild("../quackjs") {
        dependencySubstitution {
            substitute(module("com.github.MetrolistGroup.quackjs:quackjs")).using(project(":"))
        }
    }
}
include(":harness")
