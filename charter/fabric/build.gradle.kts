plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "MrPinoys_Charter"
}

repositories {
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // No mappings: Minecraft ships unobfuscated since 26.1.
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Server-side GUIs (the granary screen, M9). Vanilla clients see an ordinary
    // chest window. Plain implementation, as in ballot: 26.x is unobfuscated.
    implementation("eu.pb4:sgui:2.1.0+26.2")
    include("eu.pb4:sgui:2.1.0+26.2")

    implementation(project(":core"))
    include(project(":core"))
}

// The gametest harness, as in pocketdungeons. Loom creates the `gametest` source
// set and the `runGameTest` task. The source set carries its own fabric.mod.json
// (mod id charter-gametest), so nothing test related enters the shipped jar.
// Every gametest class must be listed there or it never runs.
fabricApi.configureTests {
    createSourceSet = true
    modId = "charter-gametest"
    enableGameTests = true
    enableClientGameTests = false
}

// Write a JUnit-style report of every gametest by name, so each milestone can
// report exactly which tests ran (the server log only prints a count).
loom {
    runs.configureEach {
        if (name == "gameTest") {
            vmArg("-Dfabric-api.gametest.report-file=${layout.buildDirectory.get().asFile}/gametest-report.xml")
            // A fixed heap: the default share of RAM ran the machine out of commit
            // space while other sessions were open. 2G ran out once the NPC site tests were added.
            vmArg("-Xmx3G")
        }
    }
}

// Every gametest run starts from a fresh world. The world folder would otherwise keep
// blocks (chests with food in them) and Charter's saved state from earlier runs, and a
// test that lands on the same chunks as an earlier one inherits what it left behind.
tasks.matching { it.name == "runGameTest" }.configureEach {
    doFirst {
        delete(layout.buildDirectory.dir("run/gameTest/world"))
    }
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

// Plain Java tests (config, later the store) run without a server.
tasks.test {
    enabled = false
}

tasks.register<JavaExec>("fabricTest") {
    group = "verification"
    description = "Runs the plain Java tests of the fabric module."
    mainClass = "charter.mc.FabricSuite"
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") {
    dependsOn("fabricTest")
}

// 26.x is unobfuscated, so the plain jar is the shipped jar (as in ballot).
val distDir = rootProject.projectDir.parentFile.resolve("dist")

tasks.register<Copy>("dist") {
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs"))
    into(distDir)
    exclude("*-sources.jar")
}

tasks.named("build") {
    finalizedBy("dist")
}
