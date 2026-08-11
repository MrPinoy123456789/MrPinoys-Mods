// Unlike quizengine's engine module, this one needs Loom -- it hands out ItemStack
// and Component, so Minecraft has to be on the compile classpath.
//
// It takes minecraft() and NOTHING ELSE. No Fabric API, no config, no logging.
// If an import of net.fabricmc.fabric.* appears in this module, the design has drifted.
//
// 26.2 ships unobfuscated, so there is no remapJar step and the published artifact
// is the same jar the fabric module compiles against.
//
// The Loom version is a literal: the plugins block is evaluated before the project
// exists, so property() is not available inside it.
plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `java-library`
    `maven-publish`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base {
    archivesName = "wondrous-api"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // No mappings dependency: 26.2 is unobfuscated and Mojang's names ARE the names.
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

// ./gradlew :api:publishToMavenLocal
// Then in dailyquests / quizengine / ballot:
//   repositories { mavenLocal() }
//   dependencies { modCompileOnly("wondrous:wondrous-api:0.1.0") }
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "wondrous-api"
        }
    }
}
