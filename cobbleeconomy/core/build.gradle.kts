plugins {
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

// Deliberately empty. The economy depends on nothing but the JDK -- no Minecraft,
// no Gson, no logging framework. Persistence is an interface here and is implemented
// in the fabric module, which already has Gson via Minecraft.
//
// A stray `import net.minecraft.*` in this module fails the build. That is the whole
// point of the split: the money rules are testable without a game running.
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

// Gradle 9 fails a build whose test source set discovers no JUnit tests. There are
// none by design -- the suite is a main(), so it runs with no test framework on the
// classpath and no network access.
tasks.test {
    enabled = false
}

tasks.register<JavaExec>("coreTest") {
    group = "verification"
    description = "Runs the dependency-free economy test suite."
    mainClass = "cobbleeconomy.core.EconomyTest"
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") {
    dependsOn("coreTest")
}
