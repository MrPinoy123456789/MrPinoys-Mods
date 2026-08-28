plugins {
    // Literal, not property(): the plugins block is evaluated before the project exists.
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "MrPinoys_thingy"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // The API, shaded in. Server owners drop in one file; consuming mods find
    // the classes at runtime without needing a second jar.
    implementation(project(":api"))
    include(project(":api"))
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

tasks.test {
    failOnNoDiscoveredTests = false
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// Regenerates data/wondrous/suite_items/*.json from Definitions.ALL and
// SuiteMetadata (PLAN.md Phase 1, hard constraint 3: no drift window between
// Phase 1 and Phase 2). Depends on compileJava, not the mod jar, so the
// generator sees the same class files processResources is about to package.
val generateSuiteItems = tasks.register<JavaExec>("generateSuiteItems") {
    group = "build"
    description = "Regenerates the suite_items datapack from Definitions.ALL."
    dependsOn("compileJava")
    mainClass = "thingy.SuiteItemsGenerator"
    // compileClasspath, not runtimeClasspath: runtimeClasspath pulls in this
    // source set's own resources output, which processResources produces.
    // Depending on it here makes processResources -> generateSuiteItems ->
    // classes -> processResources a cycle.
    classpath = sourceSets["main"].compileClasspath + files(sourceSets["main"].output.classesDirs)
    args(layout.projectDirectory.dir("src/main/resources").asFile.absolutePath)
}

tasks.named("processResources") {
    dependsOn(generateSuiteItems)
}

val distDir = rootProject.projectDir.parentFile.resolve("dist")

tasks.register<Copy>("dist") {
    // 26.2 ships unobfuscated, so Loom doesn't register a remapJar task: there's
    // nothing to remap, so plain `jar` is already the deployable artifact.
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs"))
    into(distDir)
    exclude("*-sources.jar")
}

tasks.named("build") {
    finalizedBy("dist")
}
