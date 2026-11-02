plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "MrPinoys_CharterMaps"
}

// Xaero's World Map and Minimap are "All Rights Reserved": they are compiled against, never committed, never put in
// our jar, never redistributed. Download them from Modrinth into a folder outside the repository (README.md lists the
// exact files and SHA-512 hashes) and point the build at it with -PxaeroJars=<folder> or the XAERO_JARS variable.
val xaeroJarsPath: String = (findProperty("xaeroJars") as String?) ?: System.getenv("XAERO_JARS")
    ?: throw GradleException(
        "Charter Maps compiles against Xaero's World Map and Minimap, which are not in the repository. " +
            "Download xaeroworldmap-fabric-26.3-1.47.2.jar and xaerominimap-fabric-26.3-26.6.2.jar from Modrinth " +
            "(see charter-xaero/README.md) into a folder outside the repository and build with -PxaeroJars=<that folder>."
    )
val xaeroDir = file(xaeroJarsPath)
val worldMapJar = xaeroDir.listFiles { f -> f.name.startsWith("xaeroworldmap-fabric-26.3-") && f.name.endsWith(".jar") }?.firstOrNull()
    ?: throw GradleException("No xaeroworldmap-fabric-26.3-*.jar in $xaeroDir")
val minimapJar = xaeroDir.listFiles { f -> f.name.startsWith("xaerominimap-fabric-26.3-") && f.name.endsWith(".jar") }?.firstOrNull()
    ?: throw GradleException("No xaerominimap-fabric-26.3-*.jar in $xaeroDir")
// XaeroLib ships inside both jars (META-INF/jars); javac needs it beside them for the types their methods name.
val xaeroLib = zipTree(worldMapJar).matching { include("META-INF/jars/xaerolib-*.jar") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // No mappings: Minecraft ships unobfuscated since 26.1.
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    compileOnly(files(worldMapJar, minimapJar))
    compileOnly(xaeroLib)
}

// The wire format is Charter's own package, copied here with its package renamed, so the server and this mod read and
// write the same bytes from one source and never load the same class name twice in one game.
val wireOut = layout.buildDirectory.dir("generated/mapwire")
val copyMapWire = tasks.register<Copy>("copyMapWire") {
    from(rootProject.projectDir.resolve("../charter/fabric/src/main/java/charter/mapwire"))
    into(wireOut.map { it.dir("charterxaero/wire") })
    filter { line: String -> line.replace("charter.mapwire", "charterxaero.wire") }
}
sourceSets.main {
    java.srcDir(wireOut)
}
tasks.compileJava { dependsOn(copyMapWire) }
tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(copyMapWire) }

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

// Plain Java tests of the pure parts (the wire, the index, colours, borders, tooltips); no game, no Xaero.
tasks.test {
    enabled = false
}

tasks.register<JavaExec>("pureTest") {
    group = "verification"
    description = "Runs the plain Java tests of Charter Maps."
    dependsOn(tasks.testClasses)
    mainClass = "charterxaero.ClientSuite"
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") {
    dependsOn("pureTest")
}

// 26.x is unobfuscated, so the plain jar is the shipped jar.
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
