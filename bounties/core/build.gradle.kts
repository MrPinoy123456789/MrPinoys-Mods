plugins {
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

// Deliberately empty. No Minecraft, no JSON library, no logging -- the core is
// pure rules, and a stray `import net.minecraft.*` here fails the build.
dependencies {
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

// Gradle 9 fails a test task that discovers nothing. The suite is a plain main().
tasks.test {
    enabled = false
}

tasks.register<JavaExec>("coreTest") {
    group = "verification"
    description = "Runs the dependency-free bounty rules test suite."
    mainClass = "bounties.core.BountiesTest"
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") {
    dependsOn("coreTest")
}
