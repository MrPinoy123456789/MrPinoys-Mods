plugins {
    `java-library`
}

// A compile time stand-in for the few Charter Currency signatures Fortune calls (EconomyApi, EconomyService,
// Currency, CurrencyRegistry, TxResult). It is NEVER shipped: fabric/ uses it as compileOnly, so the real classes
// come from Charter Currency at run time, and only when that mod is loaded (see CurrencyBank). If Currency changes
// one of these signatures, change it here too.
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
