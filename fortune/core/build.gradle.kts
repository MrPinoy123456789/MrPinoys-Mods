plugins {
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

// Deliberately empty. The roll, the paytables, the calendar, the streak and the stake maths depend on nothing but
// the JDK. A stray `import net.minecraft.*` here fails the build, which is the point of the split.
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

// Gradle 9 fails a test source set that discovers no JUnit tests. The suite is a main(), so it runs with no test
// framework and no network access.
tasks.test {
    enabled = false
}

tasks.register<JavaExec>("coreTest") {
    group = "verification"
    description = "Runs the dependency-free Fortune test suite."
    mainClass = "fortune.core.FortuneCoreTest"
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") {
    dependsOn("coreTest")
}
