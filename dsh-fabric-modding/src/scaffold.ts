/**
 * Scaffolding tools and file templates. Each tool generates boilerplate that
 * matches the workspace's detected versions (Minecraft 26.x unobfuscated,
 * Mojang mappings, Java 25, Kotlin DSL, Loom). Tools take a `write` flag:
 * when true they write files directly under the confined base directory; when
 * false they return the file contents for the model to write itself.
 * @module scaffold
 */

import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'
import path from 'node:path'
import { confine, exists, readText, writeText } from './fsutil.ts'
import { FALLBACK_DEFAULTS, scanWorkspace, type ScaffoldDefaults } from './patterns.ts'

/** Options passed from the plugin entry. */
export interface ScaffoldOptions {
  workspaceRoot: string
  enableFileWrites: boolean
}

/** One generated file. */
interface GeneratedFile {
  /** Path relative to the workspace root. */
  path: string
  content: string
  /** Whether to overwrite an existing file. Scaffolding defaults to false. */
  overwrite?: boolean
}

/** Canonical tool result. */
interface ScaffoldResult {
  files: { path: string; action: 'written' | 'returned' | 'skipped'; bytes: number }[]
  notes: string[]
}

/** Convert a display name like "Pocket Dungeons" to "PocketDungeons". */
function pascalFromName(name: string): string {
  return name.split(/[^A-Za-z0-9]+/).filter(Boolean)
    .map(w => w.charAt(0).toUpperCase() + w.slice(1)).join('') || 'Mod'
}

/** Fallback Pascal case from an id like "pocketdungeons" to "Pocketdungeons". */
function pascalFromId(id: string): string {
  const s = id.replace(/[^A-Za-z0-9]/g, '')
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** Convert a maven group like "com.foo.bar" to a path "com/foo/bar". */
function groupPath(group: string): string {
  return group.split('.').filter(Boolean).join('/')
}

/** Resolve defaults: scan the workspace, else fall back to MC 26.2 pins. */
async function resolveDefaults(root: string, signal: AbortSignal): Promise<ScaffoldDefaults> {
  const scan = await scanWorkspace(root, signal)
  return scan.defaults ?? FALLBACK_DEFAULTS
}

/** Build the build.gradle.kts for a new mod. */
function buildGradleKts(d: ScaffoldDefaults, archiveName: string): string {
  return [
    'plugins {',
    `    id("net.fabricmc.fabric-loom") version "${d.loomVersion}"`,
    '    `java-library`',
    '}',
    '',
    'version = "${property("mod_version")}"',
    'group = "${property("maven_group")}"',
    '',
    'base {',
    `    archivesName = "${archiveName}"`,
    '}',
    '',
    'repositories {',
    '    mavenLocal()',
    '}',
    '',
    'dependencies {',
    '    minecraft("com.mojang:minecraft:${property("minecraft_version")}")',
    '    // No mappings: Minecraft ships unobfuscated since 26.1.',
    '    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")',
    '    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")',
    '}',
    '',
    'java {',
    '    withSourcesJar()',
    `    sourceCompatibility = JavaVersion.VERSION_${d.javaRelease}`,
    `    targetCompatibility = JavaVersion.VERSION_${d.javaRelease}`,
    '}',
    '',
    'tasks.withType<JavaCompile>().configureEach {',
    `    options.release = ${d.javaRelease}`,
    '    options.encoding = "UTF-8"',
    '}',
    '',
    'tasks.processResources {',
    '    inputs.property("version", project.version)',
    '    filesMatching("fabric.mod.json") {',
    '        expand("version" to project.version)',
    '    }',
    '}',
    '',
  ].join('\n')
}

/** Build settings.gradle.kts. */
function settingsGradleKts(modId: string): string {
  return `rootProject.name = "${modId}"\n`
}

/** Build gradle.properties. */
function gradleProperties(d: ScaffoldDefaults, modId: string, modVersion: string): string {
  return [
    '# Versions re-check at https://fabricmc.net/develop when updating.',
    '',
    'org.gradle.jvmargs=-Xmx2G',
    'org.gradle.parallel=true',
    'org.gradle.configuration-cache=false',
    '',
    `minecraft_version=${d.minecraftVersion}`,
    `loader_version=${d.loaderVersion}`,
    `fabric_api_version=${d.fabricApiVersion}`,
    '',
    `mod_version=${modVersion}`,
    `maven_group=${modId}`,
    '',
  ].join('\n')
}

/** Build fabric.mod.json. `cls` is the full entrypoint class name (e.g. "PocketDungeonsMod"). */
function fabricModJson(modId: string, name: string, desc: string, env: string, d: ScaffoldDefaults, group: string, cls: string): string {
  const obj = {
    schemaVersion: 1,
    id: modId,
    version: '${version}',
    name,
    description: desc,
    authors: [] as string[],
    license: 'MIT',
    environment: env,
    mixins: [`${modId}.mixins.json`],
    entrypoints: { main: [`${group}.${cls}`] },
    depends: {
      fabricloader: `>=${d.loaderVersion}`,
      minecraft: `~${d.minecraftVersion}`,
      java: `>=${d.javaRelease}`,
      'fabric-api': '*',
    },
  }
  return JSON.stringify(obj, null, 2) + '\n'
}

/** Build the mixins json (empty mixins array). */
function mixinsJson(modId: string, group: string, javaRelease: number): string {
  const obj = {
    required: true,
    package: `${group}.mixin`,
    compatibilityLevel: `JAVA_${javaRelease}`,
    mixins: [] as string[],
    injectors: { defaultRequire: 1 },
  }
  return JSON.stringify(obj, null, 2) + '\n'
}

/** The entrypoint class name: append "Mod" only when the Pascal name does not already end with it. */
function entrypointClass(pascal: string): string {
  return pascal.endsWith('Mod') ? pascal : `${pascal}Mod`
}

/** Build the ModInitializer entrypoint. `cls` is the full class name, `pascal` the base for logger/registries. */
function mainEntrypoint(group: string, cls: string, pascal: string, modId: string): string {
  return [
    `package ${group};`,
    '',
    'import net.fabricmc.api.ModInitializer;',
    'import org.slf4j.Logger;',
    'import org.slf4j.LoggerFactory;',
    '',
    `public final class ${cls} implements ModInitializer {`,
    '',
    `    public static final String MOD_ID = "${modId}";`,
    `    public static final Logger LOG = LoggerFactory.getLogger("${pascal}");`,
    '',
    '    @Override',
    '    public void onInitialize() {',
    `        ${pascal}Registries.register();`,
    `        LOG.info("${pascal} initialised");`,
    '    }',
    '}',
    '',
  ].join('\n')
}

/** Build the ClientModInitializer entrypoint. `cls` is the full class name. */
function clientEntrypoint(group: string, cls: string, pascal: string, modId: string): string {
  return [
    `package ${group};`,
    '',
    'import net.fabricmc.api.ClientModInitializer;',
    'import org.slf4j.Logger;',
    'import org.slf4j.LoggerFactory;',
    '',
    `public final class ${cls} implements ClientModInitializer {`,
    '',
    `    public static final String MOD_ID = "${modId}";`,
    `    public static final Logger LOG = LoggerFactory.getLogger("${pascal}");`,
    '',
    '    @Override',
    '    public void onInitializeClient() {',
    `        LOG.info("${pascal} client initialised");`,
    '    }',
    '}',
    '',
  ].join('\n')
}

/** Build a registry helper with one example registration of the given kind. */
function registryHelper(group: string, pascal: string, modId: string, kind: 'item' | 'block' | 'command'): string {
  const head = [
    `package ${group};`,
    '',
  ]
  if (kind === 'item') {
    return [
      ...head,
      'import net.minecraft.core.Registry;',
      'import net.minecraft.core.registries.Registries;',
      'import net.minecraft.resources.Identifier;',
      'import net.minecraft.world.item.Item;',
      '',
      `public final class ${pascal}Items {`,
      '',
      `    private ${pascal}Items() {}`,
      '',
      `    public static final Item EXAMPLE = register("example", new Item(new Item.Properties()));`,
      '',
      '    public static void register() {',
      '        // Items register themselves via the field above; add more here.',
      '    }',
      '',
      `    private static Item register(String name, Item item) {`,
      `        return Registry.register(Registries.ITEM, Identifier.fromNamespaceAndPath("${modId}", name), item);`,
      '    }',
      '}',
      '',
    ].join('\n')
  }
  if (kind === 'block') {
    return [
      ...head,
      'import net.minecraft.core.Registry;',
      'import net.minecraft.core.registries.Registries;',
      'import net.minecraft.resources.Identifier;',
      'import net.minecraft.world.level.block.Block;',
      'import net.minecraft.world.level.block.Blocks;',
      '',
      `public final class ${pascal}Blocks {`,
      '',
      `    private ${pascal}Blocks() {}`,
      '',
      `    public static final Block EXAMPLE = register("example", new Block(Block.Properties.copy(Blocks.STONE)));`,
      '',
      '    public static void register() {',
      '        // Blocks register themselves via the field above; add more here.',
      '    }',
      '',
      `    private static Block register(String name, Block block) {`,
      `        return Registry.register(Registries.BLOCK, Identifier.fromNamespaceAndPath("${modId}", name), block);`,
      '    }',
      '}',
      '',
    ].join('\n')
  }
  // command
  return [
    ...head,
    'import net.minecraft.commands.Commands;',
    '',
    `public final class ${pascal}Commands {`,
    '',
    `    private ${pascal}Commands() {}`,
    '',
    '    public static void register() {',
    '        // Register commands here. Call this from the mod entrypoint.',
    '        // Example: CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> {',
    '        //     dispatcher.register(Commands.literal("example").executes(src -> {',
    '        //         src.getSource().sendSuccess(() -> Component.literal("ok"), false);',
    '        //         return 0;',
    '        //     }));',
    '        // });',
    '    }',
    '}',
    '',
  ].join('\n')
}

/** Build the `<Pascal>Registries` aggregator owned by the entrypoint. Holds one example item. */
function registriesAggregator(group: string, pascal: string, modId: string): string {
  return [
    `package ${group};`,
    '',
    'import net.minecraft.core.Registry;',
    'import net.minecraft.core.registries.Registries;',
    'import net.minecraft.resources.Identifier;',
    'import net.minecraft.world.item.Item;',
    '',
    `public final class ${pascal}Registries {`,
    '',
    `    private ${pascal}Registries() {}`,
    '',
    '    /** Example item. Add blocks, commands, and more items here. */',
    `    public static final Item EXAMPLE_ITEM = register("example_item", new Item(new Item.Properties()));`,
    '',
    '    public static void register() {',
    '        // Content above registers itself via the static fields. Call',
    '        // additional register helpers (commands, listeners) from here.',
    '    }',
    '',
    '    private static Item register(String name, Item item) {',
    `        return Registry.register(Registries.ITEM, Identifier.fromNamespaceAndPath("${modId}", name), item);`,
    '    }',
    '}',
    '',
  ].join('\n')
}

/** Build a mixin class stub. */
function mixinClass(group: string, pascal: string, targetClass: string, method: string, modId: string, purpose: string): string {
  const targetSimple = targetClass.substring(targetClass.lastIndexOf('.') + 1)
  return [
    `package ${group}.mixin;`,
    '',
    `import ${targetClass};`,
    'import org.spongepowered.asm.mixin.Mixin;',
    'import org.spongepowered.asm.mixin.injection.At;',
    'import org.spongepowered.asm.mixin.injection.Inject;',
    'import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;',
    '',
    `@Mixin(${targetSimple}.class)`,
    `public abstract class ${pascal}Mixin {`,
    '',
    `    @Inject(method = "${method}", at = @At("HEAD"))`,
    `    private void ${modId}$${purpose}(CallbackInfo ci) {`,
    '        // TODO: implement the mixin behavior.',
    '    }',
    '}',
    '',
  ].join('\n')
}

/** Apply a list of generated files: write or collect, honoring overwrite rules. */
async function applyFiles(files: GeneratedFile[], root: string, write: boolean, signal: AbortSignal): Promise<ScaffoldResult> {
  const out: ScaffoldResult = { files: [], notes: [] }
  for (const f of files) {
    if (signal.aborted) { out.notes.push('aborted'); break }
    const abs = confine(root, f.path)
    if (write) {
      if (!f.overwrite && await exists(abs)) {
        out.files.push({ path: f.path, action: 'skipped', bytes: f.content.length })
        out.notes.push(`skipped existing file (set overwrite or edit it): ${f.path}`)
        continue
      }
      await writeText(abs, f.content, signal)
      out.files.push({ path: f.path, action: 'written', bytes: f.content.length })
    } else {
      out.files.push({ path: f.path, action: 'returned', bytes: f.content.length })
      out.notes.push(`${f.path}:\n${f.content}`)
    }
  }
  return out
}

/** Shared output schema + render for scaffolding tools. */
const scaffoldOutput = {
  schema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      files: {
        type: 'array',
        required: true,
        items: {
          type: 'object',
          additionalProperties: false,
          properties: {
            path: { type: 'string', required: true },
            action: { type: 'string', required: true, enum: ['written', 'returned', 'skipped'] },
            bytes: { type: 'integer', required: true },
          },
        },
      },
      notes: { type: 'array', required: true, items: { type: 'string' } },
    },
  },
  render: (_args, value) => {
    const summary = value.files.map((f: { action: string; path: string; bytes: number }) => `${f.action} ${f.path} (${f.bytes}b)`).join('\n')
    return [{ type: 'text', text: summary || 'no files generated' }]
  },
}

/** Register all four scaffolding tools on `ctx.tools`. */
export function registerScaffoldTools(ctx: Context, opts: ScaffoldOptions): void {
  const root = opts.workspaceRoot

  ctx.tools.register(defineTool({
    name: 'scaffold_fabric_mod',
    description: 'Generate a complete Fabric mod skeleton (build.gradle.kts, settings.gradle.kts, gradle.properties, fabric.mod.json, mixins json, ModInitializer, and an example items registry) matching the workspace versions. Versions default from the first mod already present. Set write=false to receive file contents instead of writing them.',
    parameters: {
      id: { type: 'string', required: true, description: 'Lowercase mod id, no spaces (e.g. "mycoolmod"). Used as fabric.mod.json id and default maven group.' },
      name: { type: 'string', required: true, description: 'Display name (e.g. "My Cool Mod"). PascalCased into the Java entrypoint class name.' },
      base: { type: 'string', required: true, description: 'Target directory for the new mod, relative to the workspace root (e.g. "mycoolmod"). Must be inside the workspace.' },
      environment: { type: 'string', enum: ['server', 'client', '*'], description: 'fabric.mod.json environment. Default "server".' },
      description: { type: 'string', description: 'One-line mod description for fabric.mod.json.' },
      javaRelease: { type: 'integer', description: 'Java release target. Defaults from the workspace (25).' },
      minecraftVersion: { type: 'string', description: 'Override the Minecraft version pin.' },
      loaderVersion: { type: 'string', description: 'Override the Fabric loader version pin.' },
      fabricApiVersion: { type: 'string', description: 'Override the Fabric API version pin.' },
      modVersion: { type: 'string', description: 'Initial mod version. Default "0.1.0".' },
      write: { type: 'boolean', description: 'Write files directly when true (default). Return contents when false.' },
    },
    output: scaffoldOutput,
    async execute(args, exec) {
      const d = await resolveDefaults(root, exec.signal)
      const id = args.id
      const pascal = pascalFromName(args.name) || pascalFromId(id)
      const group = id
      const gp = groupPath(group)
      const env = args.environment ?? 'server'
      const desc = args.description ?? `${args.name} mod.`
      const modVersion = args.modVersion ?? d.modVersion
      const local: ScaffoldDefaults = {
        ...d,
        javaRelease: args.javaRelease ?? d.javaRelease,
        minecraftVersion: args.minecraftVersion ?? d.minecraftVersion,
        loaderVersion: args.loaderVersion ?? d.loaderVersion,
        fabricApiVersion: args.fabricApiVersion ?? d.fabricApiVersion,
      }
      const archiveName = pascal.replace(/([a-z0-9])([A-Z])/g, '$1_$2')
      const cls = entrypointClass(pascal)
      const files: GeneratedFile[] = [
        { path: path.join(args.base, 'build.gradle.kts'), content: buildGradleKts(local, archiveName) },
        { path: path.join(args.base, 'settings.gradle.kts'), content: settingsGradleKts(id) },
        { path: path.join(args.base, 'gradle.properties'), content: gradleProperties(local, id, modVersion) },
        { path: path.join(args.base, 'src/main/resources/fabric.mod.json'), content: fabricModJson(id, args.name, desc, env, local, group, cls) },
        { path: path.join(args.base, 'src/main/resources', `${id}.mixins.json`), content: mixinsJson(id, group, local.javaRelease) },
        { path: path.join(args.base, 'src/main/java', gp, `${cls}.java`), content: mainEntrypoint(group, cls, pascal, id) },
        { path: path.join(args.base, 'src/main/java', gp, `${pascal}Registries.java`), content: registriesAggregator(group, pascal, id) },
      ]
      const write = opts.enableFileWrites && (args.write ?? true)
      return applyFiles(files, root, write, exec.signal)
    },
  }))

  ctx.tools.register(defineTool({
    name: 'scaffold_mixin',
    description: 'Generate a mixin class under <mod>.mixin and append it to the mod mixins json. Provide the fully-qualified target class and the method to inject into. The mixin is a HEAD-inject stub with a TODO body.',
    parameters: {
      modId: { type: 'string', required: true, description: 'The mod id (maven group and mixins json name).' },
      base: { type: 'string', required: true, description: 'The mod project directory, relative to the workspace root.' },
      mixinName: { type: 'string', required: true, description: 'Mixin simple name without "Mixin" suffix (e.g. "CustomClick").' },
      targetClass: { type: 'string', required: true, description: 'Fully qualified target class (e.g. "net.minecraft.server.MinecraftServer").' },
      method: { type: 'string', required: true, description: 'Target method signature or name (e.g. "<init>" or "tick").' },
      purpose: { type: 'string', description: 'Short lowercase purpose tag for the injected method name (e.g. "logTick"). Defaults to "hook".' },
      write: { type: 'boolean', description: 'Write files directly when true (default). Return contents when false.' },
    },
    output: scaffoldOutput,
    async execute(args, exec) {
      const group = args.modId
      const gp = groupPath(group)
      const pascal = pascalFromId(args.mixinName)
      const purpose = (args.purpose ?? 'hook').replace(/[^A-Za-z0-9]/g, '')
      const className = `${pascal}Mixin`
      const javaPath = path.join(args.base, 'src/main/java', gp, 'mixin', `${className}.java`)
      const jsonRel = path.join(args.base, 'src/main/resources', `${args.modId}.mixins.json`)
      const files: GeneratedFile[] = [
        { path: javaPath, content: mixinClass(group, pascal, args.targetClass, args.method, args.modId, purpose) },
      ]
      const write = opts.enableFileWrites && (args.write ?? true)
      const result = await applyFiles(files, root, write, exec.signal)

      // Append the mixin entry to the mixins json (merge into the array).
      if (write && !exec.signal.aborted) {
        const jsonAbs = confine(root, jsonRel)
        const raw = await readText(jsonAbs)
        if (raw) {
          try {
            const j = JSON.parse(raw) as { mixins?: string[] }
            const arr = j.mixins ?? []
            if (!arr.includes(className)) {
              arr.push(className)
              j.mixins = arr
              await writeText(jsonAbs, JSON.stringify(j, null, 2) + '\n', exec.signal)
              result.files.push({ path: jsonRel, action: 'written', bytes: 0 })
              result.notes.push(`appended ${className} to ${args.modId}.mixins.json`)
            }
          } catch {
            result.notes.push(`could not parse ${jsonRel}; add "${className}" to its mixins array manually`)
          }
        } else {
          result.notes.push(`no mixins json at ${jsonRel}; create it or run scaffold_fabric_mod first`)
        }
      } else if (!write) {
        result.notes.push(`After writing the class, add "${className}" to the mixins array in ${args.modId}.mixins.json`)
      }
      return result
    },
  }))

  ctx.tools.register(defineTool({
    name: 'scaffold_registry',
    description: 'Generate an example registry helper for items, blocks, or commands, using Mojang/official names and Identifier.fromNamespaceAndPath. Produces an additive <Pascal><Kind> class; it does not overwrite an existing one.',
    parameters: {
      modId: { type: 'string', required: true, description: 'The mod id (maven group).' },
      base: { type: 'string', required: true, description: 'The mod project directory, relative to the workspace root.' },
      kind: { type: 'string', enum: ['item', 'block', 'command'], description: 'What to scaffold. Default "item".' },
      write: { type: 'boolean', description: 'Write files directly when true (default). Return contents when false.' },
    },
    output: scaffoldOutput,
    async execute(args, exec) {
      const group = args.modId
      const gp = groupPath(group)
      const pascal = pascalFromId(args.modId)
      const kind = args.kind ?? 'item'
      const suffix = kind === 'item' ? 'Items' : kind === 'block' ? 'Blocks' : 'Commands'
      const fileName = `${pascal}${suffix}.java`
      const javaPath = path.join(args.base, 'src/main/java', gp, fileName)
      const files: GeneratedFile[] = [
        { path: javaPath, content: registryHelper(group, pascal, args.modId, kind) },
      ]
      const write = opts.enableFileWrites && (args.write ?? true)
      const result = await applyFiles(files, root, write, exec.signal)
      result.notes.push(`Call ${pascal}${suffix}.register() from the mod entrypoint onInitialize().`)
      return result
    },
  }))

  ctx.tools.register(defineTool({
    name: 'scaffold_entrypoint',
    description: 'Generate a ModInitializer (main) or ClientModInitializer (client) entrypoint class. Does not overwrite an existing entrypoint.',
    parameters: {
      modId: { type: 'string', required: true, description: 'The mod id (maven group).' },
      name: { type: 'string', description: 'Display name, PascalCased into the class name. Defaults to the mod id.' },
      base: { type: 'string', required: true, description: 'The mod project directory, relative to the workspace root.' },
      kind: { type: 'string', enum: ['main', 'client'], description: 'main -> ModInitializer, client -> ClientModInitializer. Default "main".' },
      write: { type: 'boolean', description: 'Write files directly when true (default). Return contents when false.' },
    },
    output: scaffoldOutput,
    async execute(args, exec) {
      const group = args.modId
      const gp = groupPath(group)
      const pascal = (args.name ? pascalFromName(args.name) : pascalFromId(args.modId))
      const kind = args.kind ?? 'main'
      const cls = kind === 'client' ? (pascal.endsWith('Client') ? pascal : `${pascal}Client`) : entrypointClass(pascal)
      const fileName = `${cls}.java`
      const javaPath = path.join(args.base, 'src/main/java', gp, fileName)
      const content = kind === 'client' ? clientEntrypoint(group, cls, pascal, args.modId) : mainEntrypoint(group, cls, pascal, args.modId)
      const files: GeneratedFile[] = [{ path: javaPath, content }]
      const write = opts.enableFileWrites && (args.write ?? true)
      const result = await applyFiles(files, root, write, exec.signal)
      if (kind === 'client') {
        result.notes.push(`Register this class under fabric.mod.json entrypoints.client as "${group}.${cls}".`)
      }
      return result
    },
  }))
}
