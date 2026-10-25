plugins {
    `java-library`
}

version = "0.1.0"

// Deliberately empty. The rules model depends on nothing but the JDK: no Minecraft,
// no Gson, no logging. A stray import here fails the build, which is the point.
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

// Gradle 9 fails a build whose test source set discovers no JUnit tests. There are
// none by design: the suite is a main().
tasks.test {
    enabled = false
}

tasks.register<JavaExec>("coreTest") {
    group = "verification"
    description = "Runs the dependency-free rules model tests."
    mainClass = "charter.CoreSuite"
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") {
    dependsOn("coreTest")
}
