/**
 * Domain-knowledge system-prompt section and the dynamic workspace-context
 * builder. The section is stable prose; the context is re-evaluated each
 * assembly from a fresh workspace scan.
 * @module prompt
 */

import { renderScan, scanWorkspace, type WorkspaceScan } from './patterns.ts'

/**
 * The stable Fabric modding domain section. Tuned to this workspace's stack:
 * Minecraft 26.x unobfuscated (Mojang/official mappings, no Yarn), Java 25,
 * Fabric Loom, Kotlin DSL gradle, mostly server-side mods. Follows the house
 * convention: no em dashes and no double hyphens as punctuation.
 */
export function buildDomainSection(): string {
  return [
    '# Minecraft Fabric modding (this workspace)',
    '',
    'You are working in a multi-mod Fabric workspace. Each subdirectory is one gradle mod project. Match the conventions the workspace already uses; the dynamic context below lists the detected mods, versions, and entrypoints. Reuse existing patterns over inventing new ones, and call `read_mod_reference` to pull real snippets from sibling mods before writing new code.',
    '',
    '## Stack and mappings',
    '',
    '- Minecraft 26.x ships unobfuscated, so these mods use Mojang/official names directly. There is no Yarn mappings dependency. Use official class and method names: `net.minecraft.core.registries.Registries`, `net.minecraft.resources.Identifier`, `net.minecraft.resources.ResourceKey`, `net.minecraft.world.level.Level`, `net.minecraft.server.MinecraftServer`, etc.',
    '- `Identifier.fromNamespaceAndPath(modId, path)` builds namespaced ids. The mod id is lowercase, no spaces.',
    '- Java release 25, Fabric Loom, Kotlin DSL gradle (`build.gradle.kts`) in this workspace. `gradle.properties` pins `minecraft_version`, `loader_version`, `fabric_api_version`, `maven_group`, `mod_version`.',
    '- `fabric.mod.json` version is `${version}`, expanded by `processResources` from the gradle project version. Do not hardcode the version string.',
    '',
    '## Project layout',
    '',
    '- `src/main/java/<maven_group>/...` for sources, `src/main/resources/fabric.mod.json` for metadata, `src/main/resources/<mod>.mixins.json` for mixin config, `src/main/resources/data/<mod>/...` for datapack content.',
    '- `build.gradle.kts` applies `net.fabricmc.fabric-loom`, declares `minecraft`, `fabric-loader`, `fabric-api` from the properties, sets `java { sourceCompatibility/targetCompatibility }` and `options.release`.',
    '- Because MC 26.x is unobfuscated, Loom does not register `remapJar`; plain `jar` is the deployable artifact. Do not add remap tasks.',
    '',
    '## Entrypoints and registries',
    '',
    '- `ModInitializer.onInitialize()` for server/common, `ClientModInitializer.onInitializeClient()` for client. Register in `fabric.mod.json` under `entrypoints.main` / `entrypoints.client`.',
    '- `environment` is `server`, `client`, or `*`. Most mods here are `server`-side only; set this honestly.',
    '- Register content at boot from the entrypoint, not lazily: `Registry.register(Registries.ITEM, id, new Item(new Item.Properties()))`, `Registries.BLOCK`, `Registries.COMMAND`, etc. Keep a `<Mod>Registries` helper per mod that owns all `register()` calls.',
    '- Commands: register via `CommandRegistrationCallback.EVENT` (Fabric API) or a dedicated register helper invoked from the entrypoint. Match the pattern the target mod already uses.',
    '',
    '## Mixins',
    '',
    '- Mixin package is `<mod>.mixin`. The mixins json declares `package`, `compatibilityLevel` (e.g. `JAVA_25`), `mixins: [...]` listing class simple names, and `injectors.defaultRequire: 1`.',
    '- A mixin class uses `@Mixin(TargetClass.class)`, `@Inject(method = "...", at = @At("..."))` or `@Redirect` / `@ModifyArg`. Keep mixin names descriptive of what they change.',
    '- Add every new mixin class simple name to the mixins json `mixins` array, or it will not apply.',
    '',
    '## Verification before declaring done',
    '',
    '- Run `./gradlew build` (or the mod-specific test task) and fix compile errors before claiming a change works. These mods have pure-Java regression tests registered as gradle `JavaExec` tasks; run the relevant one.',
    '- Server-side mods: do not reference client-only classes (`net.minecraft.client.*`) from common or server code. Gate client code behind the client entrypoint.',
    '',
    '## House style (mandatory)',
    '',
    '- Never write an em dash or a double hyphen (`--`) as punctuation in any player-facing or operator-facing string, comment, javadoc, log line, markdown doc, commit message, or PR text. Use a colon, semicolon, comma, parentheses, or a period and a new sentence instead. Command flags (`--patch`) and code operators (`i--`) are fine; prose dashes are not.',
    '- Compact code: collapse duplicate else branches, avoid needless nesting, share helpers. Follow idiomatic Java 25.',
    '- Do not add or remove comments unless asked. If you edit a line for another reason, keep its comment intact.',
  ].join('\n')
}

/** Cache the most recent scan so repeated assemblies in one turn don't re-walk. */
let cached: { root: string; scan: WorkspaceScan } | null = null

/**
 * Build the dynamic workspace context text. Re-scans when the root changes or
 * on the first call; otherwise reuses the cached scan for the turn.
 */
export async function buildWorkspaceContext(root: string, signal?: AbortSignal): Promise<string> {
  if (!cached || cached.root !== root) {
    cached = { root, scan: await scanWorkspace(root, signal) }
  }
  const header = '# Workspace mod scan (reuse these conventions)'
  return `${header}\n\n${renderScan(cached.scan)}`
}
