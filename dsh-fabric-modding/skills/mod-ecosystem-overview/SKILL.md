---
name: mod-ecosystem-overview
description: Use when making an ecosystem level choice for a Minecraft mod: picking a loader, choosing a publishing platform, setting up CI publishing, publishing docs, or picking a source control and license setup. Load this before deciding how to ship a mod.
---

# Mod ecosystem overview

This is a quick map of the choices you make when shipping a Minecraft mod. It is tuned to this workspace's reality: Fabric, server side mods, Minecraft 26.x unobfuscated, Java 25, Kotlin DSL gradle. Where the broader ecosystem differs, the difference is noted.

## Loader choice

- Fabric: this workspace's loader. Lightweight, fast iteration, Mixin based, official mappings (Mojang) since MC 26.1 ships unobfuscated. No Forge/NeoForge `DeferredRegister` style indirection; you register content directly at boot from a `ModInitializer`.
- NeoForge / Forge: the other major line. Heavier, event bus driven, `DeferredRegister` based. Not used here. If a feature needs a Forge only hook, that is a sign to reconsider the feature, not to switch loaders.
- Quilt: a Fabric compatible fork. Mostly source compatible with Fabric; this workspace does not target it but Fabric mods often run on Quilt unchanged.

Pick Fabric for this workspace. The only reason to reconsider is a hard dependency on a Forge only library, which has not come up.

## Publishing platforms

- Modrinth: the primary modern platform. Free, open source friendly, fast review, good API. Preferred for this workspace.
- CurseForge: the legacy high traffic platform. Required if you want maximum reach; has a stricter review and a non free TOS. Publish here second, after Modrinth.
- GitHub Releases: for dev builds and unreleased snapshots. Not a discovery platform, but a fine source of jars for a small audience.

For this workspace, publish to Modrinth first. CurseForge is optional reach.

## CI publishing

- `mod-publish-plugin` (FabricMC community plugin) publishes to Modrinth and CurseForge from a gradle task. It reads `modrinth_token` / `curseforge_api_key` from environment or gradle properties and uploads the jar built by `jar` (or `remapJar` on obfuscated versions; this workspace uses plain `jar` because MC 26.x is unobfuscated).
- GitHub Actions is the usual CI. Store the tokens as repository secrets, never in `gradle.properties`. Run `./gradlew build` then `./gradlew publishModrinth` (and `publishCurseForge`) on a tag push.
- The `mod-publish-plugin` skill (if you adopt it) covers the exact plugin config and troubleshooting.

This workspace does not currently publish via CI; the `dist` gradle task copies the jar to a local `dist/` folder. When you are ready to publish, add `mod-publish-plugin` to `build.gradle.kts` and a GitHub Actions workflow.

## Documentation publishing

- Keep design docs and handoffs in the mod's `docs/` folder (this workspace's convention, e.g. `pocketdungeons/docs/d3-handoffs/`). Markdown, version controlled with the code.
- A player facing mod page belongs on Modrinth (it renders markdown). Keep the Modrinth README short and link to the repo docs for depth.

## Source control and license

- Git, one repository per workspace, one subdirectory per mod (this workspace's layout). Each mod is an independent gradle project but shares the workspace root.
- License: MIT is this workspace's default (see any `fabric.mod.json`). It is permissive, mod ecosystem friendly, and matches what most Fabric mods use. Pick MIT unless you have a reason not to.
- Do not commit secrets. Publishing tokens live in CI secrets or `~/.gradle/gradle.properties` (user level, gitignored), never in the repo.

## Decision summary for this workspace

| Choice | Recommendation |
|---|---|
| Loader | Fabric |
| Platform | Modrinth first, CurseForge optional |
| CI | GitHub Actions with `mod-publish-plugin` |
| Docs | `docs/` in repo, markdown |
| License | MIT |
| Artifact | plain `jar` (MC 26.x unobfuscated, no `remapJar`) |
