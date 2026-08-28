/**
 * DeepSeek Harness plugin: Fabric modding domain knowledge, workspace pattern
 * reuse, and scaffolding tools for Minecraft 26.x (unobfuscated Mojang
 * mappings). Loaded as TypeScript source via a `cordis.yml` patch overlay.
 *
 * Registers:
 * - a stable system-prompt section (`ctx.systemPrompt.section`) with Fabric
 *   modding essentials and the house style rules;
 * - a dynamic prompt context (`ctx.systemPrompt.context`) that scans the
 *   workspace each assembly and reports the mods, versions, and entrypoints
 *   already in use;
 * - six tools (`ctx.tools.register`): `scaffold_fabric_mod`,
 *   `scaffold_mixin`, `scaffold_registry`, `scaffold_entrypoint`,
 *   `read_mod_reference`, and `build_mod`.
 *
 * Situational depth ships as bundled SKILL.md files under `skills/`, surfaced
 * through the `skill-filesystem` row's `customSkillDirs` config (set in the
 * overlay, not here, since this module cannot mount other packages). The skills
 * cover mixin development, compatibility troubleshooting, the mod ecosystem,
 * and Stonecutter multi version builds.
 * @module @mrpinoys/dsh-fabric-modding
 */

import type { Context } from '@deepseek-ai/cordis'
import z from '@deepseek-ai/schemastery'
import { buildDomainSection, buildWorkspaceContext } from './prompt.ts'
import { registerScaffoldTools } from './scaffold.ts'
import { registerReferenceTool } from './reference.ts'
import { registerBuildTool } from './build.ts'

export const name = 'fabric-modding'
export const inject = ['tools', 'systemPrompt']

/** Plugin configuration. All fields optional. */
export interface Config {
  /** Root the pattern scanner walks and writes are confined to. */
  workspaceRoot: string
  /** Lets scaffolding tools write files directly. Default true. */
  enableFileWrites: boolean
  /** Lets the build_mod tool run gradle. Default true. */
  enableBuildRuns: boolean
  /** Sort order for the domain-knowledge system-prompt section. */
  domainSectionOrder: number
  /** Sort order for the dynamic workspace-pattern context. */
  workspaceContextOrder: number
}

/** Schemastery configuration for this plugin. */
export const Config: z<Config> = z.object({
  workspaceRoot: z.string().default('').description('Workspace root the scanner walks and writes are confined to. Defaults to process.cwd().'),
  enableFileWrites: z.boolean().default(true).description('Let scaffolding tools write files directly. Set false to route writes through the approved file path.'),
  enableBuildRuns: z.boolean().default(true).description('Let the build_mod tool run gradle. Set false to disable builds entirely.'),
  domainSectionOrder: z.number().default(8800).description('Sort order for the domain-knowledge system-prompt section.'),
  workspaceContextOrder: z.number().default(8900).description('Sort order for the dynamic workspace-pattern context.'),
})

/**
 * Wire the plugin: prompt section, dynamic context, and tools.
 * @param ctx - registrant context carrying the tool and system-prompt services.
 * @param config - deployment configuration.
 */
export function apply(ctx: Context, config: Config): void {
  const resolved = config ?? {}
  const workspaceRoot = (resolved.workspaceRoot as string) || process.cwd()

  // Stable domain-knowledge section. Static text, evaluated once.
  ctx.systemPrompt.section({
    name: 'fabric-modding-domain',
    order: (resolved.domainSectionOrder as number) ?? 8800,
    text: buildDomainSection(),
  })

  // Dynamic workspace-pattern context. Re-evaluated each assembly so the model
  // sees the current set of mods and version pins without an extra tool call.
  // The provider is synchronous by contract, so the async scan is kicked off on
  // first call and cached; the first assembly of a process gets a placeholder
  // and the next gets the full text.
  ctx.systemPrompt.context({
    name: 'fabric-modding-workspace',
    order: (resolved.workspaceContextOrder as number) ?? 8900,
    text: () => resolveContextSync(workspaceRoot),
  })

  registerScaffoldTools(ctx, { workspaceRoot, enableFileWrites: (resolved.enableFileWrites as boolean) ?? true })
  registerReferenceTool(ctx, { workspaceRoot })
  registerBuildTool(ctx, { workspaceRoot, enableBuildRuns: (resolved.enableBuildRuns as boolean) ?? true })
}

/**
 * The prompt-context provider is synchronous, but the workspace scan is async.
 * Kick off the scan on first call and cache the promise; return a placeholder
 * until it resolves, then the next assembly gets the full text. In practice the
 * scan completes well within the first turn, so only the very first assembly of
 * a process sees the placeholder.
 */
let contextPromise: Promise<string> | null = null
let contextValue: string | null = null

function resolveContextSync(root: string): string {
  if (contextValue) return contextValue
  if (!contextPromise) {
    contextPromise = buildWorkspaceContext(root).then((text) => {
      contextValue = text
      return text
    }).catch((err) => {
      contextValue = `# Workspace mod scan\n\nScan failed: ${String(err instanceof Error ? err.message : err)}`
      return contextValue
    })
    return '# Workspace mod scan\n\nScanning workspace mods...'
  }
  return contextValue ?? '# Workspace mod scan\n\nScanning workspace mods...'
}
