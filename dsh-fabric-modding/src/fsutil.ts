/**
 * Path-confined filesystem helpers. Every read and write resolves against a
 * workspace root and refuses to escape it, so scaffolding tools can never touch
 * files outside the project even when the model passes a relative `..` path.
 * @module fsutil
 */

import { promises as fs } from 'node:fs'
import path from 'node:path'
import type { AbortSignal } from 'node:events'

/** Normalize to an absolute path and verify it stays inside `root`. */
export function confine(root: string, target: string): string {
  const resolved = path.resolve(root, target)
  const rel = path.relative(root, resolved)
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    throw new Error(`path escapes workspace root: ${target} -> ${resolved} (root ${root})`)
  }
  return resolved
}

/** True if `p` exists as any filesystem entry. Swallows errors to false. */
export async function exists(p: string): Promise<boolean> {
  try {
    await fs.access(p)
    return true
  } catch {
    return false
  }
}

/** Read a UTF-8 file, returning `null` when missing instead of throwing. */
export async function readText(p: string): Promise<string | null> {
  try {
    return await fs.readFile(p, 'utf8')
  } catch {
    return null
  }
}

/**
 * Write `content` to `p`, creating parent directories. Honors `signal` by
 * checking before the write; the write itself is atomic-ish (single call).
 */
export async function writeText(p: string, content: string, signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) throw new Error('aborted before write')
  await fs.mkdir(path.dirname(p), { recursive: true })
  await fs.writeFile(p, content, 'utf8')
}

/** Append a line to `p` if not already present (used for mixins json edits). */
export async function ensureLine(p: string, line: string): Promise<void> {
  const current = (await readText(p)) ?? ''
  if (current.includes(line)) return
  const sep = current.length > 0 && !current.endsWith('\n') ? '\n' : ''
  await writeText(p, current + sep + line + '\n')
}

/** Recursively list files under `dir` up to `depth`, optionally filtered by suffix. */
export async function listFiles(dir: string, depth: number, suffix?: string, signal?: AbortSignal): Promise<string[]> {
  const out: string[] = []
  async function walk(d: string, remaining: number): Promise<void> {
    if (remaining < 0 || signal?.aborted) return
    let entries: import('node:fs').Dirent[]
    try {
      entries = await fs.readdir(d, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of entries) {
      if (signal?.aborted) return
      const full = path.join(d, e.name)
      // Skip build output, vcs, and gradle caches to keep scans fast and clean.
      if (e.isDirectory()) {
        if (e.name === 'build' || e.name === '.gradle' || e.name === '.git' || e.name === 'node_modules') continue
        await walk(full, remaining - 1)
      } else if (e.isFile()) {
        if (!suffix || full.endsWith(suffix)) out.push(full)
      }
    }
  }
  await walk(dir, depth)
  return out
}
