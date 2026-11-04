plugins {
    // Version comes from settings.gradle.kts, which reads it from gradle.properties.
    id("net.fabricmc.fabric-loom")
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "MrPinoys_Fortune"
}

repositories {
    mavenLocal()
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }
    // Floodgate and Cumulus, compile only: the shipped server carries Floodgate, and Fortune runs without it.
    maven("https://repo.opencollab.dev/maven-releases/") { name = "OpenCollabReleases" }
    maven("https://repo.opencollab.dev/maven-snapshots/") { name = "OpenCollabSnapshots" }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // No mappings: Minecraft ships unobfuscated since 26.1.
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // The machine. Vanilla clients see an ordinary chest window, and Geyser translates it for Bedrock.
    implementation("eu.pb4:sgui:2.2.1+26.3")
    include("eu.pb4:sgui:2.2.1+26.3")

    // Bedrock forms (the join prompt). Never bundled: the server's own Floodgate provides them at run time.
    compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2")

    // Charter Currency's stake path, compile time only. The real classes come from Charter Currency when it is
    // loaded; the stub module is never shipped.
    compileOnly(project(":currency-api-stub"))

    // The pure rules, shaded into the jar.
    implementation(project(":core"))
    include(project(":core"))
}

// The gametest harness, as in charter. The source set carries its own fabric.mod.json (mod id fortune-gametest),
// so nothing test related enters the shipped jar. Every gametest class must be listed there or it never runs.
fabricApi.configureTests {
    createSourceSet = true
    modId = "fortune-gametest"
    enableGameTests = true
    enableClientGameTests = false
}

// A JUnit style report of every gametest by name; the server log only prints a count.
loom {
    runs.configureEach {
        if (name == "gameTest") {
            vmArg("-Dfabric-api.gametest.report-file=${layout.buildDirectory.get().asFile}/gametest-report.xml")
            // A fixed, small heap: this machine runs out of commit space with larger ones.
            vmArg("-Xmx1500m")
        }
    }
}

// Every gametest run starts from a fresh world and a fresh config.
tasks.matching { it.name == "runGameTest" }.configureEach {
    doFirst {
        delete(layout.buildDirectory.dir("run/gameTest/world"))
        delete(layout.buildDirectory.dir("run/gameTest/config/fortune"))
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

tasks.test {
    enabled = false
}
