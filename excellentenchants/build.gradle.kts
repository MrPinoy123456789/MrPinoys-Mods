plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "MrPinoys_ExcellentEnchants"
}

repositories {
    mavenLocal()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // No mappings: Minecraft ships unobfuscated since 26.1.
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

val distDir = rootProject.projectDir.parentFile.resolve("dist")

tasks.register<Copy>("dist") {
    // 26.2 ships unobfuscated, so Loom doesn't register a remapJar task (there's
    // nothing to remap); plain `jar` is already the deployable artifact.
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs"))
    into(distDir)
    exclude("*-sources.jar")
}

tasks.named("build") {
    finalizedBy("dist")
}

// The gametest harness, as in charter. Loom creates the `gametest` source set and the
// `runGameTest` task; its own fabric.mod.json keeps test code out of the shipped jar.
// Every gametest class must be listed there or it never runs.
fabricApi.configureTests {
    createSourceSet = true
    modId = "excellentenchants-gametest"
    enableGameTests = true
    enableClientGameTests = false
}

loom {
    runs.configureEach {
        if (name == "gameTest") {
            vmArg("-Dfabric-api.gametest.report-file=${layout.buildDirectory.get().asFile}/gametest-report.xml")
            vmArg("-Xmx3G")
        }
    }
}

tasks.matching { it.name == "runGameTest" }.configureEach {
    doFirst {
        delete(layout.buildDirectory.dir("run/gameTest/world"))
    }
}
