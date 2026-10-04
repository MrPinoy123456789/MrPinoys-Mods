// lemonwatch.mjs: persistent Lemon event watcher.
//
// Holds the wait_events loop so the connection survives between agent turns.
// Run it in the background, then read its stdout or the log file whenever you
// need the latest events. It is the ONLY wait_events consumer; do not call
// wait_events yourself while it runs (the cursor is shared).
//
//   node tools/server/lemonwatch.mjs <player> [timeout_s]
//
// Events and errors go to stdout and to run/lemon-watch.log. It never exits on
// its own: a quiet stretch prints a heartbeat line, an error retries in 5 s.

import { appendFileSync, readFileSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const player = process.argv[2]
const timeout = Math.max(5, Math.min(240, Number(process.argv[3]) || 240))
if (!player) { console.error('usage: node lemonwatch.mjs <player> [timeout_s]'); process.exit(2) }

const url = process.env.PD_MCP_URL || 'http://127.0.0.1:8787/mcp'
const token = process.env.PD_MCP_TOKEN
  || (existsSync(join(root, 'run', '.mcp-token')) ? readFileSync(join(root, 'run', '.mcp-token'), 'utf8').trim() : '')
if (!token) { console.error('no token: set PD_MCP_TOKEN or start the server once to write run/.mcp-token'); process.exit(2) }

const log = join(root, 'run', 'lemon-watch.log')
const stamp = () => new Date().toISOString().slice(11, 19)
let id = 0

function emit(text) {
  for (const line of String(text).split(/\r?\n/)) {
    if (!line.trim()) continue
    const out = `[${stamp()}] ${line}`
    console.log(out)
    try { appendFileSync(log, out + '\n') } catch {}
  }
}

async function call(name, args, ms) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++id, method: 'tools/call', params: { name, arguments: args } }),
    signal: AbortSignal.timeout(ms),
  })
  const body = await res.json()
  if (body.error) throw new Error(`${body.error.code}: ${body.error.message}`)
  return (body.result?.content || []).map(c => c.text || '').join('\n')
}

emit(`lemonwatch up: player=${player} timeout=${timeout}s url=${url}`)
for (;;) {
  try {
    const out = await call('wait_events', { player, timeout_s: timeout }, (timeout + 60) * 1000)
    emit(out.trim() ? out : '(quiet)')
    // Hold every fresh question immediately so the 45 s fallback cannot fire
    // while the agent is between poll cycles. The real reply still comes from
    // whoever is driving; this only buys time.
    for (const line of out.split(/\r?\n/)) {
      if (/\blemon ask </.test(line)) {
        call('lemon_think', { player }, 15000).catch(() => {})
      }
    }
  } catch (e) {
    emit(`watch error: ${e.message}; retrying in 5s`)
    await new Promise(r => setTimeout(r, 5000))
  }
}
