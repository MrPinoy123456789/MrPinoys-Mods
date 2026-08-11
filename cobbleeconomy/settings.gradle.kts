pluginManagement {
    // The version lives here rather than in fabric/build.gradle.kts because a
    // `plugins {}` block in a build script is a restricted scope -- it is compiled
    // before the project exists, so property("loom_version") cannot resolve in it.
    // Settings scripts CAN read gradle.properties, via the `by settings` delegate,
    // so declaring the version here keeps gradle.properties the single place any
    // version is written down.
    val loom_version: String by settings

    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("net.fabricmc.fabric-loom") version loom_version
    }
}

rootProject.name = "cobbleeconomy"

include("core")
include("fabric")
