/**
 * `read_mod_reference` tool. Searches the workspace's Java sources for a query
 * and returns the best matching snippets, so the model copies existing patterns
 * from sibling mods instead of inventing new code. Simple keyword scoring: a
 * file scores by counting query term hits, weighted toward class declarations
 * and imports.
 * @module reference
 */

import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'
import { findJavaSources } from './patterns.ts'
import { readText } from './fsutil.ts'

/** Options passed from the plugin entry. */
export interface ReferenceOptions {
  workspaceRoot: string
}

/** One matching snippet. */
interface Snippet {
  file: string
  score: number
  /** Up to `maxLines` lines around the best matching region. */
  excerpt: string
}

/** Score a file against the query terms; return the best windowed excerpt. */
function scoreFile(text: string, terms: string[]): { score: number; excerpt: string } {
  const lines = text.split(/\r?\n/)
  const lower = text.toLowerCase()
  let score = 0
  for (const t of terms) {
    const lt = t.toLowerCase()
    let from = 0
    while (true) {
      const idx = lower.indexOf(lt, from)
      if (idx < 0) break
      score++
      from = idx + lt.length
    }
  }
  // Weight class declarations and registry calls a bit more.
  if (/class\s+\w+/.test(text)) score += 1
  if (/Registry\.register|Registries\./.test(text)) score += 2
  if (score === 0) return { score: 0, excerpt: '' }

  // Find the densest window of 40 lines for the excerpt.
  const window = 40
  let bestStart = 0
  let bestHits = -1
  for (let i = 0; i + window <= lines.length || i < lines.length; i++) {
    const slice = lines.slice(i, i + window).join('\n').toLowerCase()
    let hits = 0
    for (const t of terms) {
      let from = 0
      const lt = t.toLowerCase()
      while (true) {
        const idx = slice.indexOf(lt, from)
        if (idx < 0) break
        hits++
        from = idx + lt.length
      }
    }
    if (hits > bestHits) { bestHits = hits; bestStart = i }
    if (i + window >= lines.length) break
  }
  const excerpt = lines.slice(bestStart, bestStart + window).join('\n')
  return { score, excerpt }
}

/** Register the read_mod_reference tool on `ctx.tools`. */
export function registerReferenceTool(ctx: Context, opts: ReferenceOptions): void {
  ctx.tools.register(defineTool({
    name: 'read_mod_reference',
    description: 'Search the workspace Java sources for a pattern and return the best matching snippets from existing mods. Use this before writing new registry, mixin, command, or entrypoint code so you reuse patterns this workspace already uses. Pass concrete terms (class names, method names, registry keys).',
    parameters: {
      query: { type: 'string', required: true, description: 'Space-separated search terms (e.g. "Registry register item", "CommandRegistrationCallback", "Mixin MinecraftServer tick").' },
      limit: { type: 'integer', description: 'Max snippets to return. Default 3.' },
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          snippets: {
            type: 'array',
            required: true,
            items: {
              type: 'object',
              additionalProperties: false,
              properties: {
                file: { type: 'string', required: true },
                score: { type: 'integer', required: true },
                excerpt: { type: 'string', required: true },
              },
            },
          },
          totalFilesSearched: { type: 'integer', required: true },
        },
      },
      render: (_args, value) => {
        if (value.snippets.length === 0) {
          return [{ type: 'text', text: `No matches across ${value.totalFilesSearched} Java files.` }]
        }
        const body = value.snippets.map((s: { file: string; score: number; excerpt: string }) => `### ${s.file} (score ${s.score})\n\`\`\`java\n${s.excerpt}\n\`\`\``).join('\n\n')
        return [{ type: 'text', text: `${value.snippets.length} match(es) across ${value.totalFilesSearched} files:\n\n${body}` }]
      },
    },
    async execute(args, exec) {
      const terms = args.query.split(/\s+/).filter(Boolean)
      const limit = args.limit ?? 3
      const files = await findJavaSources(opts.workspaceRoot, exec.signal)
      const scored: Snippet[] = []
      for (const f of files) {
        if (exec.signal.aborted) break
        const text = await readText(f)
        if (!text) continue
        const { score, excerpt } = scoreFile(text, terms)
        if (score > 0) scored.push({ file: f, score, excerpt })
      }
      scored.sort((a, b) => b.score - a.score)
      const top = scored.slice(0, limit)
      return { snippets: top, totalFilesSearched: files.length }
    },
  }))
}
