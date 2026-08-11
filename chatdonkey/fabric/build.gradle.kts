plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "MrPinoys_chatdonkey"
}

repositories {
    // Loom adds the Minecraft repositories itself. No extra mod dependencies.
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    // No mappings dependency. Minecraft ships unobfuscated since 26.1, so
    // Mojang's names ARE the names and Yarn is not used.

    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

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

tasks.register<Copy>("dist") {
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs"))
    into(distDir)
    exclude("*-sources.jar")
}

tasks.named("build") {
    finalizedBy("dist")
}
