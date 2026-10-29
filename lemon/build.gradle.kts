plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
}

version = "0.1.0"
group = "lemon"
base { archivesName = "MrPinoys_Lemon" }
repositories { mavenLocal() }
dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.156.0+26.2")
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
    modId = "lemon-gametest"
    enableGameTests = true
    enableClientGameTests = false
}
tasks.register<JavaExec>("lemonSpeechTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "lemon.LemonSpeechTest"
}
tasks.register<JavaExec>("isolationTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "lemon.IsolationTest"
}
tasks.test {
    dependsOn("lemonSpeechTest", "isolationTest")
    failOnNoDiscoveredTests = false
}
tasks.register<Copy>("dist") {
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs"))
    into(rootProject.projectDir.parentFile.resolve("dist"))
    exclude("*-sources.jar")
}
