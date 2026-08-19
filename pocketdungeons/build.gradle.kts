plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "MrPinoys_Pocket_Dungeons"
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

tasks.register<JavaExec>("doorMaskTest") {
    group = "verification"
    description = "Runs the pure-Java DoorMask regression test"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "pocketdungeons.DoorMaskTest"
}

tasks.register<JavaExec>("planSelectorTest") {
    group = "verification"
    description = "Runs the pure-Java RoomSelector/PlanRenderer regression test"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "pocketdungeons.PlanSelectorTest"
}

tasks.register<JavaExec>("layoutGraphTest") {
    group = "verification"
    description = "Runs LayoutGraphGenerator's own shape/role/span verification sweep"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "pocketdungeons.LayoutGraphGenerator"
}

tasks.test {
    dependsOn("doorMaskTest")
    dependsOn("planSelectorTest")
    dependsOn("pipelineProof")
    dependsOn("layoutGraphTest")
    failOnNoDiscoveredTests = false
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
    // nothing to remap) -- plain `jar` is already the deployable artifact.
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs"))
    into(distDir)
    exclude("*-sources.jar")
}

tasks.named("build") {
    finalizedBy("dist")
}

tasks.register<JavaExec>("pipelineProof") {
    group = "verification"
    description = "Proves the M3 planner pipeline against a synthetic full room library"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "pocketdungeons.PipelineProofTest"
}
