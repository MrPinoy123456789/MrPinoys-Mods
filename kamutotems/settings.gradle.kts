pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "kamutotems"

include("core")
include("fabric")
