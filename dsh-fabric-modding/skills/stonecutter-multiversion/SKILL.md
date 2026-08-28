---
name: stonecutter-multiversion
description: Use when a mod must support more than one Minecraft version from one source tree. Covers Stonecutter conditional compilation, version switching, multi version build setup, and when to branch instead. Load this before adding version specific code or setting up Stonecutter.
---

# Stonecutter multi version development

Stonecutter lets one source tree compile against several Minecraft versions by conditionally including or excluding code per target version. This workspace currently targets a single version (MC 26.x unobfuscated), so this skill is for the moment you need to widen support. It covers when to use Stonecutter versus a branch, how to set it up, and the conditional syntax.

## When to use Stonecutter versus a branch

The choice depends on the shape of the differences, not on personal taste.

- Structural differences (a class hierarchy or method set changes between major versions): use a git branch per major version. Stonecutter conditional blocks get ugly fast when whole subsystems move. Each branch evolves independently; cross version shared logic stays minimal.
- Point differences (a signature, a method name, a constant value differs between point releases): use Stonecutter conditional compilation in one source tree. One `if (mcVersion >= X)` block per difference keeps the divergence visible and the shared code the default.

This workspace is single version today. If you add a second MC version, first check whether the differences are structural or point. Most Fabric mod multi version work is point differences, so Stonecutter is usually the right call.

## How Stonecutter works

Stonecutter is a gradle plugin that generates a virtual source set per target version by applying `// if` / `// else` conditional comments in your Java sources. At build time, for the active version, it strips the inactive branches before compilation, so the compiler only sees the code that applies to that version. The same physical file produces different compiled output per version.

It also lets you swap `build.gradle.kts` / `gradle.properties` content per version, so version pins and dependency declarations can vary without a branch.

## Setup sketch

Add the Stonecutter plugin to the root `settings.gradle.kts` and declare the versions:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.5.+"
}

stonecutter {
    kotlinController = true
    create("pocketdungeons") {
        versions("26.2", "26.1")
        vcsVersion = "26.2"
    }
}
```

Each entry in `versions(...)` becomes a buildable subproject named `<mod>-<version>`. `vcsVersion` is the version whose source you edit directly; the others are generated views. Switch the active version with `./gradlew setActiveVersion 26.1` (or the Stonecutter provided task).

In the mod's `build.gradle.kts`, read the active version from the Stonecutter extension and pin `minecraft_version` accordingly. The `gradle.properties` per version is generated, so you stop hardcoding `minecraft_version` there.

## Conditional syntax

In Java sources, use the comment directives. The active version is compared by the order you declared in `versions(...)`, so newer versions are "greater".

```java
// if >= 26.2
import net.minecraft.core.registries.Registries;
// else
import net.minecraft.core.Registry;
// endif
```

```java
public static void register() {
    // if true
    Registry.register(Registries.ITEM, id, item);
    // else
    Registry.register(Registry.ITEM, id, item);
    // endif
}
```

Rules:
- The directives must be on their own line, in a `//` comment, exactly `// if`, `// else`, `// elif`, `// endif`.
- The condition is a boolean expression over the version. `>= 26.2`, `< 26.1`, `== 26.2` are the common forms.
- Keep the conditional blocks small. If a whole method differs, prefer two small conditional blocks over one large one, or extract a version specific helper.
- Never put a conditional inside a single expression. Wrap the whole statement.

## Version specific dependencies

In `build.gradle.kts`, gate a dependency on the active version:

```kotlin
// if >= 26.2
implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
// else
implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version_old")}")
// endif
```

The `gradle.properties` keys can differ per generated version, so `fabric_api_version` resolves to the right pin for each.

## Build and verify

- Build a specific version: `./gradlew :pocketdungeons-26.2:build` (the generated subproject name).
- Build all versions: `./gradlew build` from the root builds every active version.
- With the `build_mod` tool, pass the version specific subproject as the `mod` argument, e.g. `mod: "pocketdungeons-26.2"`. The tool runs the gradle wrapper at the workspace root, so the generated subproject path resolves.
- Run the version's test task too: `build_mod` with `task: "test"` and the version subproject.

## When NOT to use Stonecutter

- Single version mod: do not add Stonecutter. It is build complexity for no gain. This workspace is single version today.
- Structural divergence across major versions: branch instead. Stonecutter conditional blocks that span hundreds of lines are harder to read than two branches.
- You need different mod behavior (not just different signatures) per version: that is product logic, not a mappings difference. Put it behind a runtime check or a config, not a compile time conditional.

## Migration path for this workspace

If you decide to support MC 26.1 alongside 26.2:
1. Add Stonecutter to `settings.gradle.kts` with `versions("26.2", "26.1")` and `vcsVersion = "26.2"`.
2. Run `./gradlew setActiveVersion 26.1` and fix the compile errors that appear (these are the point differences).
3. Wrap each difference in `// if >= 26.2` / `// else` / `// endif`.
4. Build both with `build_mod` (`mod: "pocketdungeons-26.2"` then `mod: "pocketdungeons-26.1"`).
5. Run both test suites.
