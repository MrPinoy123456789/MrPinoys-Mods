pluginManagement {
    // The Loom version is declared here, not in fabric/build.gradle.kts: a plugins block in a build script is
    // compiled before the project exists, so it cannot read gradle.properties. A settings script can.
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

rootProject.name = "fortune"

include("core")
include("currency-api-stub")
include("fabric")
