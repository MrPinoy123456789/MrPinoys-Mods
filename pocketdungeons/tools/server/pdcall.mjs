// Runs one pdserver.mjs command in a child process and returns its output, so the MCP
// server and the Lemon brain reuse the CLI exactly as agents use it. No dependencies.

import { execFile } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const cli = join(dirname(fileURLToPath(import.meta.url)), 'pdserver.mjs')

/** Resolves with { out, ok }; never rejects, a failed command reports through `ok` and its output. */
export function pd(args, timeoutMs = 300000) {
  return new Promise(resolve => {
    execFile(process.execPath, [cli, ...args], { timeout: timeoutMs, windowsHide: true, maxBuffer: 4 * 2 ** 20 },
      (err, stdout, stderr) => resolve({ out: `${stdout}${stderr}`.trim(), ok: !err }))
  })
}

/**
 * The player's context snapshot, trimmed for a model: every room shrinks to a short
 * string and `recent` keeps its last few events. `full` returns it untouched.
 */
export async function context(player, { full = false, recent = 5 } = {}) {
  const { out, ok } = await pd(['context', player], 15000)
  if (!ok || full) return out
  let snap
  try { snap = JSON.parse(out) } catch { return out }
  if (Array.isArray(snap.rooms)) snap.rooms = snap.rooms.map(compactRoom)
  if (Array.isArray(snap.recent)) snap.recent = snap.recent.slice(-recent)
  return JSON.stringify(snap)
}

const ROOM_KEYS = ['id', 'room', 'role', 'entered', 'spawner', 'locked', 'lock', 'state']

function compactRoom(r) {
  if (!r || typeof r !== 'object') return r
  return ROOM_KEYS.filter(k => r[k] !== undefined && r[k] !== false && r[k] !== null)
    .map(k => r[k] === true ? k : `${k}=${typeof r[k] === 'object' ? JSON.stringify(r[k]) : r[k]}`).join(' ')
}
