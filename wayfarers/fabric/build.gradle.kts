plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "MrPinoys_wayfarers"
}

repositories {
    // Loom adds the Minecraft repositories itself. sgui lives here.
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    // No mappings dependency. Minecraft ships unobfuscated since 26.1, so
    // Mojang's names ARE the names and Yarn is not used.

    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // The trade GUI. Vanilla clients see an ordinary chest window.
    implementation("eu.pb4:sgui:2.1.0+26.2")
    include("eu.pb4:sgui:2.1.0+26.2")

    // The core, shaded into the jar. Server owners drop in one file.
    implementation(project(":core"))
    include(project(":core"))
}

loom {
    // No client source set: this mod is server-side only.
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

// The build trap that has bitten eight mods: this must depend on jar, not remapJar.
// 26.2 ships unobfuscated so Loom registers no remapJar task.
tasks.register<Copy>("dist") {
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs"))
    into(distDir)
    exclude("*-sources.jar")
}

tasks.named("build") {
    finalizedBy("dist")
}
