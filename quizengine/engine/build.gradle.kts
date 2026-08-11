plugins {
    `java-library`
}

version = "0.1.0"

// Deliberately empty. The engine depends on nothing but the JDK — no Minecraft,
// no JSON library, no logging framework. A stray import fails the build, which is
// the whole point of keeping this in its own module.
dependencies {
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

// Gradle 9 fails a test task that discovers nothing. There are no JUnit tests
// here by design — the suite below is a plain main().
tasks.test {
    enabled = false
}

// The test suite is a plain main() so it runs with no network and no test
// framework on the classpath. Swap in JUnit here when convenient; the assertions
// map across mechanically.
tasks.register<JavaExec>("engineTest") {
    group = "verification"
    description = "Runs the dependency-free engine test suite."
    mainClass = "quizengine.EngineTest"
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") {
    dependsOn("engineTest")
}
