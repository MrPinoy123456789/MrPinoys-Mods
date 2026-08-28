/**
 * Workspace scanner. Walks the workspace root one level deep for gradle mod
 * projects, reads their version pins and fabric.mod.json, and reports the
 * conventions already in use so the model reuses them instead of inventing new
 * ones. Tailored to this workspace: Minecraft 26.x ships unobfuscated, so
 * mappings are Mojang/official (no Yarn).
 * @module patterns
 */

import { promises as fs } from 'node:fs'
import path from 'node:path'
import { exists, listFiles, readText } from './fsutil.ts'

/** One detected gradle mod project under the workspace root. */
export interface ModSummary {
  /** Directory name (also used as the default mod id candidate). */
  dir: string
  /** Absolute path to the mod project root. */
  root: string
  /** Build file flavor: `kts` for build.gradle.kts, `groovy` for build.gradle. */
  buildFile: 'kts' | 'groovy' | 'none'
  /** Parsed gradle.properties keys that matter for scaffolding. */
  gradle: Record<string, string>
  /** Parsed fabric.mod.json, when present. */
  fabricMod: FabricModSummary | null
  /** Java release target, when discoverable. */
  javaRelease: number | null
  /** Mappings in use: `mojang` (unobfuscated, no mappings dep) or `yarn`. */
  mappings: 'mojang' | 'yarn' | 'unknown'
  /** For multi-module projects: the subproject dir holding fabric.mod.json. */
  subproject?: string
  /** True when a CONVENTIONS.md file exists at the mod root. */
  hasConventions: boolean
}

/** The subset of fabric.mod.json this plugin cares about. */
export interface FabricModSummary {
  id: string
  name: string
  version: string
  environment: string
  entrypoints: Record<string, string[]>
  mixins: string[]
  depends: Record<string, string>
}

/** Aggregate workspace scan result. */
export interface WorkspaceScan {
  root: string
  mods: ModSummary[]
  /** The first mod with usable version pins, used as scaffolding defaults. */
  defaults: ScaffoldDefaults | null
}

/** Version defaults lifted from an existing mod for new scaffolds. */
export interface ScaffoldDefaults {
  minecraftVersion: string
  loaderVersion: string
  fabricApiVersion: string
  loomVersion: string
  javaRelease: number
  modVersion: string
  mappings: 'mojang' | 'yarn'
  buildFile: 'kts' | 'groovy'
}

/** Fallback defaults matching the pocketdungeons mod (MC 26.2, Java 25). */
export const FALLBACK_DEFAULTS: ScaffoldDefaults = {
  minecraftVersion: '26.2',
  loaderVersion: '0.19.3',
  fabricApiVersion: '0.156.0+26.2',
  loomVersion: '1.17-SNAPSHOT',
  javaRelease: 25,
  modVersion: '0.1.0',
  mappings: 'mojang',
  buildFile: 'kts',
}

/** Parse a gradle.properties file into a flat string map. */
function parseProperties(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq < 0) continue
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim()
  }
  return out
}

/** Best-effort fabric.mod.json parse into the summary shape. */
function parseFabricMod(raw: string): FabricModSummary | null {
  try {
    const j = JSON.parse(raw) as Record<string, unknown>
    const entrypoints = (j.entrypoints ?? {}) as Record<string, unknown>
    const depends = (j.depends ?? {}) as Record<string, unknown>
    const mixins = (j.mixins ?? []) as unknown[]
    const toStrArr = (v: unknown): string[] => Array.isArray(v) ? v.map(x => String(x)) : []
    return {
      id: String(j.id ?? ''),
      name: String(j.name ?? ''),
      version: String(j.version ?? ''),
      environment: String(j.environment ?? '*'),
      entrypoints: Object.fromEntries(Object.entries(entrypoints).map(([k, v]) => [k, toStrArr(v)])),
      mixins: mixins.map(m => String(m)),
      depends: Object.fromEntries(Object.entries(depends).map(([k, v]) => [k, String(v)])),
    }
  } catch {
    return null
  }
}

/** Detect the Java release from build.gradle.kts / build.gradle contents. */
function detectJavaRelease(buildText: string): number | null {
  const releaseMatch = buildText.match(/release\s*=\s*(\d+)/)
  if (releaseMatch) return Number(releaseMatch[1])
  const versionMatch = buildText.match(/VERSION_(\d+)/)
  if (versionMatch) return Number(versionMatch[1])
  return null
}

/** Detect mappings: a mappings(...) dependency means Yarn; absence means Mojang. */
function detectMappings(buildText: string, gradle: Record<string, string>): 'mojang' | 'yarn' | 'unknown' {
  if (/mappings\s*\(/.test(buildText) || /yarn/i.test(gradle.yarn_mappings ?? '')) return 'yarn'
  // MC 26.1+ ships unobfuscated; no mappings dep is the Mojang case here.
  if (/fabric-loom/.test(buildText)) return 'mojang'
  return 'unknown'
}

/** Find the subproject (under a multi-module gradle project) that holds fabric.mod.json. */
async function findFabricSubproject(modRoot: string): Promise<string | null> {
  let entries: import('node:fs').Dirent[]
  try {
    entries = await fs.readdir(modRoot, { withFileTypes: true })
  } catch {
    return null
  }
  const names = entries.filter(e => e.isDirectory()).map(e => e.name)
    .filter(n => !['build', '.gradle', '.git', 'node_modules'].includes(n))
  // Prefer a subproject literally named "fabric", then any with a fabric.mod.json.
  names.sort((a, b) => {
    if (a === 'fabric' && b !== 'fabric') return -1
    if (b === 'fabric' && a !== 'fabric') return 1
    return a.localeCompare(b)
  })
  for (const name of names) {
    const fmj = path.join(modRoot, name, 'src', 'main', 'resources', 'fabric.mod.json')
    if (await exists(fmj)) return name
  }
  return null
}

/** Scan one candidate mod directory into a summary. Handles single- and multi-module projects. */
async function scanMod(dir: string, root: string): Promise<ModSummary | null> {
  const modRoot = path.join(root, dir)
  const kts = path.join(modRoot, 'build.gradle.kts')
  const groovy = path.join(modRoot, 'build.gradle')
  const settingsKts = path.join(modRoot, 'settings.gradle.kts')
  const settingsGroovy = path.join(modRoot, 'settings.gradle')
  const hasKts = await exists(kts)
  const hasGroovy = await exists(groovy)
  const hasSettingsKts = await exists(settingsKts)
  const hasSettingsGroovy = await exists(settingsGroovy)
  const buildFile: ModSummary['buildFile'] = hasKts
    ? 'kts'
    : hasGroovy
      ? 'groovy'
      : hasSettingsKts || hasSettingsGroovy
        ? (hasSettingsKts ? 'kts' : 'groovy')
        : 'none'
  if (buildFile === 'none') return null

  const gradleText = (await readText(path.join(modRoot, 'gradle.properties'))) ?? ''
  const gradle = parseProperties(gradleText)

  let buildText = ''
  let fmjPath = path.join(modRoot, 'src', 'main', 'resources', 'fabric.mod.json')
  let subproject: string | undefined

  if (hasKts || hasGroovy) {
    // Single-module project: build file and fabric.mod.json at the root.
    buildText = (await readText(hasKts ? kts : groovy)) ?? ''
  } else {
    // Multi-module project: find the subproject carrying fabric.mod.json.
    const sub = await findFabricSubproject(modRoot)
    if (sub) {
      subproject = sub
      const subKts = path.join(modRoot, sub, 'build.gradle.kts')
      const subGroovy = path.join(modRoot, sub, 'build.gradle')
      buildText = (await readText(await exists(subKts) ? subKts : subGroovy)) ?? ''
      fmjPath = path.join(modRoot, sub, 'src', 'main', 'resources', 'fabric.mod.json')
    } else {
      // No fabric subproject found; fall back to the settings file text.
      buildText = (await readText(hasSettingsKts ? settingsKts : settingsGroovy)) ?? ''
    }
  }

  const javaRelease = detectJavaRelease(buildText)
  const mappings = detectMappings(buildText, gradle)
  const fmjText = await readText(fmjPath)
  const fabricMod = fmjText ? parseFabricMod(fmjText) : null
  const hasConventions = await exists(path.join(modRoot, 'CONVENTIONS.md'))

  return { dir, root: modRoot, buildFile, gradle, fabricMod, javaRelease, mappings, subproject, hasConventions }
}

/**
 * Scan the workspace root for gradle mod projects (one level deep). Skips
 * obvious non-mod directories. Honors `signal` for cancellation.
 */
export async function scanWorkspace(root: string, signal?: AbortSignal): Promise<WorkspaceScan> {
  const mods: ModSummary[] = []
  let entries: import('node:fs').Dirent[]
  try {
    entries = await fs.readdir(root, { withFileTypes: true })
  } catch {
    return { root, mods: [], defaults: null }
  }
  for (const e of entries) {
    if (signal?.aborted) break
    if (!e.isDirectory()) continue
    // Skip non-mod top-level dirs.
    if (['archived', 'dist', 'docs', '.git', '.claude', 'node_modules', '_tmp_mcsrc6'].includes(e.name)) continue
    const mod = await scanMod(e.name, root)
    if (mod) mods.push(mod)
  }
  return { root, mods, defaults: pickDefaults(mods) }
}

/** Choose the first mod with a minecraft_version pin as the defaults source. */
function pickDefaults(mods: ModSummary[]): ScaffoldDefaults | null {
  const src = mods.find(m => m.gradle.minecraft_version) ?? null
  if (!src) return null
  return {
    minecraftVersion: src.gradle.minecraft_version ?? FALLBACK_DEFAULTS.minecraftVersion,
    loaderVersion: src.gradle.loader_version ?? FALLBACK_DEFAULTS.loaderVersion,
    fabricApiVersion: src.gradle.fabric_api_version ?? FALLBACK_DEFAULTS.fabricApiVersion,
    loomVersion: src.gradle.loom_version ?? FALLBACK_DEFAULTS.loomVersion,
    javaRelease: src.javaRelease ?? FALLBACK_DEFAULTS.javaRelease,
    modVersion: src.gradle.mod_version ?? FALLBACK_DEFAULTS.modVersion,
    mappings: src.mappings === 'yarn' ? 'yarn' : 'mojang',
    buildFile: src.buildFile === 'groovy' ? 'groovy' : 'kts',
  }
}

/** Render a scan as concise prose for the dynamic prompt context. Collapses
 * shared version pins into one header line so 15 mods with the same pins don't
 * produce 15 identical version lines. */
export function renderScan(scan: WorkspaceScan): string {
  if (scan.mods.length === 0) {
    return 'No gradle Fabric mod projects detected at the workspace root.'
  }
  const lines: string[] = []
  lines.push(`Workspace root: ${scan.root}`)
  lines.push(`Detected ${scan.mods.length} mod project(s):`)

  // Detect whether all mods share the same version pins; if so, emit once.
  const pins = new Set<string>()
  for (const m of scan.mods) {
    if (m.gradle.minecraft_version) {
      pins.add(`mc=${m.gradle.minecraft_version} loader=${m.gradle.loader_version ?? '?'} fabric-api=${m.gradle.fabric_api_version ?? '?'}`)
    }
  }
  const allSamePins = pins.size === 1
  if (allSamePins) {
    lines.push(`All mods share: ${[...pins][0]}`)
  }

  for (const m of scan.mods) {
    const fmj = m.fabricMod
    const sub = m.subproject ? `:${m.subproject}` : ''
    const meta = `${m.buildFile}, ${m.mappings}${m.javaRelease ? `, java ${m.javaRelease}` : ''}`
    const conv = m.hasConventions ? ' CONVENTIONS.md' : ''
    if (fmj) {
      lines.push(`- ${m.dir}${sub}: id=${fmj.id} env=${fmj.environment} mixins=[${fmj.mixins.join(', ')}]${conv} (${meta})`)
    } else {
      lines.push(`- ${m.dir}${sub}${conv ? ' ' + conv.trim() : ''} (${meta})`)
    }
    // Only emit per-mod pins when they differ across mods.
    if (!allSamePins && m.gradle.minecraft_version) {
      lines.push(`    mc=${m.gradle.minecraft_version} loader=${m.gradle.loader_version ?? '?'} fabric-api=${m.gradle.fabric_api_version ?? '?'}`)
    }
  }
  if (scan.defaults) {
    const d = scan.defaults
    lines.push(`Scaffolding defaults: loom=${d.loomVersion} java=${d.javaRelease} mappings=${d.mappings} build=${d.buildFile}`)
  }
  return lines.join('\n')
}

/** Find Java source files under the workspace (depth 6), for the reference tool. */
export async function findJavaSources(root: string, signal?: AbortSignal): Promise<string[]> {
  return listFiles(root, 6, '.java', signal)
}
