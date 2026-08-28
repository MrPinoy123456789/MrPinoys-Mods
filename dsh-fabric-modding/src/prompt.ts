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
    'Multi-mod Fabric workspace. Each subdirectory is one gradle mod project. The dynamic context below lists detected mods and their conventions. Reuse existing patterns; call `read_mod_reference` to pull snippets from sibling mods before writing new code.',
    '',
    '## Stack',
    '',
    '- MC 26.x unobfuscated: Mojang/official names directly, no Yarn. Use `net.minecraft.core.registries.Registries`, `net.minecraft.resources.Identifier`, etc.',
    '- `Identifier.fromNamespaceAndPath(modId, path)` for namespaced ids. Mod id is lowercase, no spaces.',
    '- Java 25, Fabric Loom, Kotlin DSL gradle. `fabric.mod.json` version is `${version}` from `processResources`; do not hardcode it.',
    '- MC 26.x unobfuscated means Loom has no `remapJar`; plain `jar` is the deployable artifact.',
    '',
    '## Layout and registries',
    '',
    '- Sources: `src/main/java/<group>/...`. Metadata: `src/main/resources/fabric.mod.json`. Mixins: `src/main/resources/<mod>.mixins.json`. Datapacks: `src/main/resources/data/<mod>/...`.',
    '- `ModInitializer.onInitialize()` for server/common, `ClientModInitializer.onInitializeClient()` for client. Most mods here are `server`-side only.',
    '- Register content at boot from the entrypoint: `Registry.register(Registries.ITEM, id, ...)`. Keep a `<Mod>Registries` helper per mod. Commands via `CommandRegistrationCallback.EVENT` or a register helper; match the target mod\'s pattern.',
    '',
    '## Mixins',
    '',
    '- Package `<mod>.mixin`. Mixins json: `package`, `compatibilityLevel` (`JAVA_25`), `mixins: [...]`, `injectors.defaultRequire: 1`. Add every new mixin class name to the `mixins` array or it will not apply.',
    '- `@Mixin(Target.class)`, `@Inject`/`@ModifyArg`/`@Redirect`. Load the `mixin-development` skill before writing mixins.',
    '',
    '## Context discipline',
    '',
    '- Read only the milestone handoff you are implementing, plus the Java files directly relevant. Do NOT read master plans, brainstorms, bug logs, or lore unless the handoff references a section.',
    '- Use `read_mod_reference` for snippets, not whole files. Implement one milestone, run tests via `build_mod`, stop.',
    '- If the mod has a `CONVENTIONS.md` (shown in the scan above), read it once before writing code in that mod. It carries mod-specific rules the plugin does not.',
    '',
    '## Verification',
    '',
    '- Call `build_mod` to run gradle (default `build`, or a specific test task). It returns parsed compile errors and failed tasks. For long builds, `run_in_background: true` then poll with `job_output`.',
    '- These mods have pure-Java regression tests as gradle `JavaExec` tasks (e.g. `doorMaskTest`, `pipelineProof`). Pass the task name to `build_mod`.',
    '- Server-side mods: do not reference `net.minecraft.client.*` from server code.',
    '- Verify Minecraft API against the 26.2 jar, not memory. Use `javap -cp` on the merged-deobf jar in the Loom cache. Checking a method exists is not the same as checking what it does.',
    '- `./gradlew build` green after every commit, not just at the end.',
    '',
    '## Commits',
    '',
    '- One commit per logical change. Message explains why, not just what.',
    '',
    '## Skills (load on demand)',
    '',
    '`mixin-development`, `compat-troubleshooting`, `mod-ecosystem-overview`, `stonecutter-multiversion`.',
    '',
    '## House style (mandatory)',
    '',
    '- No em dashes or `--` as punctuation in any string, comment, javadoc, log, doc, commit, or PR. Use `:`, `;`, `,`, `()`, or a period. Command flags (`--patch`) and code operators (`i--`) are fine.',
    '- Compact code, idiomatic Java 25. Do not add or remove comments unless asked.',
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
