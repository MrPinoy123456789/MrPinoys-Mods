plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "0.1.0"
group = "foundry"
base { archivesName = "MrPinoys_Foundry" }
repositories {
    mavenLocal()
    maven("https://maven.nucleoid.xyz") { name = "Nucleoid" }
}
dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.156.0+26.2")
    // The server ships polymer-bundled; compile against it, never shade it.
    implementation("eu.pb4:polymer-bundled:0.17.5+26.2")
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
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
fabricApi.configureTests {
    createSourceSet = true
    modId = "foundry-gametest"
    enableGameTests = true
    enableClientGameTests = false
}
// Pure JDK tests: foundry.core has no Minecraft imports, so these run without a server.
tasks.register<JavaExec>("coreTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "foundry.CoreSuite"
}
tasks.test {
    dependsOn("coreTest")
    failOnNoDiscoveredTests = false
}
tasks.register<Copy>("dist") {
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs"))
    into(rootProject.projectDir.parentFile.resolve("dist"))
    exclude("*-sources.jar")
}
