plugins {
    `java-library`
}

// A compile-time stand-in for Charter's published `charter.api` package, written from
// the integration contract. It is NEVER shipped: fabric/ uses it as compileOnly, so the
// real classes come from Charter at runtime. When Charter's api lands on its branch,
// check these three interfaces still match it and delete this module in favour of the
// real artifact.
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
