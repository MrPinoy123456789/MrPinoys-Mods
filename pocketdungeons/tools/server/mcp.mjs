#!/usr/bin/env node
// MCP server for the Pocket Dungeons test server, so any MCP client (Claude Code, Devin,
// a script) can drive Lemon without a shell. No dependencies. See MCP.md.
//
//   node mcp.mjs                        stdio, for a local client (Claude Code)
//   node mcp.mjs --http 8787            Streamable HTTP on 127.0.0.1:8787/mcp, needs PD_MCP_TOKEN
//   node mcp.mjs ... --admin            also expose start, stop and console commands
//
// Every tool shells out to pdserver.mjs, so behaviour matches the CLI and LEMON_AGENT.md.

import { createServer } from 'node:http'
import { createInterface } from 'node:readline'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { timingSafeEqual } from 'node:crypto'
import { context, pd } from './pdcall.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const playtests = resolve(here, '..', '..', 'docs', 'playtests')
const packPath = resolve(here, '..', 'lemon', 'out', 'pack.md')
const args = process.argv.slice(2)
const httpPort = args.includes('--http') ? Number(args[args.indexOf('--http') + 1]) : null
const admin = args.includes('--admin')
const token = process.env.PD_MCP_TOKEN || ''

// Lemon llm mode keepalive. The mode lapses server side after lemonLlmLapseSeconds
// (5 min) unless refreshed. The refresh used to ride on wait_events alone, so an agent
// that paused the loop to investigate lost llm mode and the player's next question got
// the guide fallback at once (playtest 2026-09-29-2). Now this process refreshes it
// every minute for every player the agent put in llm mode, while the agent is still
// making calls of any kind; an agent that goes quiet for LLM_LEASE_MS lets it lapse, so
// a vanished agent still hands the player back to the guide. lemon_mode guide ends it.
const LLM_REFRESH_MS = 60000
const LLM_LEASE_MS = 20 * 60000
const llmPlayers = new Set()
let lastCall = 0
const keepalive = setInterval(async () => {
  if (!llmPlayers.size) return
  if (Date.now() - lastCall > LLM_LEASE_MS) {
    llmPlayers.clear()
    return
  }
  for (const p of llmPlayers) await pd(['lemon', 'mode', p, 'llm'], 15000).catch(() => {})
}, LLM_REFRESH_MS)
keepalive.unref() // never the reason this process stays alive

const player = { type: 'string', description: 'Minecraft player name' }
const text = { type: 'string', description: 'What Lemon says. Short: the bubble shows about ten words at a time.' }
const obj = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required })
const lemonVerb = verb => async a => (await pd(['lemon', verb, a.player, a.text])).out

const tools = {
  status: {
    description: 'Is the test server up, who is online, last five log lines.',
    inputSchema: obj({}),
    run: async () => (await pd(['status'], 15000)).out,
  },
  sync: {
    description: 'Skip every event logged before now. Call once before the first wait_events.',
    inputSchema: obj({}),
    run: async () => (await pd(['sync'], 15000)).out,
  },
  wait_events: {
    description: 'Block until new game events arrive (or the timeout), return them one per line. Keeps the player\'s Lemon in llm mode. Event format: LEMON_AGENT.md section 3.1.',
    inputSchema: obj({ player, timeout_s: { type: 'number', description: 'Seconds to wait, 5 to 240. Default 60.' } }, ['player']),
    run: async a => {
      const t = Math.max(5, Math.min(240, Number(a.timeout_s) || 60))
      llmPlayers.add(a.player)
      return (await pd(['wait', '--player', a.player, '--timeout', String(t)], (t + 30) * 1000)).out
    },
  },
  context: {
    description: 'The player\'s situation as one line of JSON: phase, floor, room, rooms, omen, spawners, inventory, recent events. Trimmed by default; full=true for everything.',
    inputSchema: obj({ player, full: { type: 'boolean' } }, ['player']),
    run: a => context(a.player, { full: !!a.full }),
  },
  lemon_say: { description: 'Lemon appears and says the text, then idles away.', inputSchema: obj({ player, text }), run: lemonVerb('say') },
  lemon_ask: { description: 'Lemon asks a question and waits; the reply arrives as a "lemon answer" event.', inputSchema: obj({ player, text }), run: lemonVerb('ask') },
  lemon_reply: { description: 'Answer the player\'s pending question. Delivered now, never held.', inputSchema: obj({ player, text }), run: lemonVerb('reply') },
  lemon_think: {
    description: 'Show a short "let me check" line while you work out an answer.',
    inputSchema: obj({ player, text: { type: 'string' } }, ['player']),
    run: async a => (await pd(['lemon', 'think', a.player, ...(a.text ? [a.text] : [])])).out,
  },
  lemon_quiet: { description: 'Lemon vanishes and holds unprompted lines until the player speaks to it.', inputSchema: obj({ player }), run: async a => (await pd(['lemon', 'quiet', a.player])).out },
  lemon_mode: {
    description: 'guide hands Lemon back to its built-in guide (do this when you finish); llm routes questions to you. This server keeps llm mode refreshed while you keep making calls, even between wait_events.',
    inputSchema: obj({ player, mode: { type: 'string', enum: ['guide', 'llm'] } }),
    run: async a => {
      if (a.mode === 'llm') llmPlayers.add(a.player)
      else llmPlayers.delete(a.player)
      return (await pd(['lemon', 'mode', a.player, a.mode])).out
    },
  },
  knowledge_pack: {
    description: 'Lemon\'s compact briefing: rules, game guide, puzzle hint ladders, decisions, open research questions, room table. Read once at session start instead of the repo.',
    inputSchema: obj({}),
    run: async () => existsSync(packPath) ? readFileSync(packPath, 'utf8') : 'No pack yet. Run: node tools/lemon/build-pack.mjs',
  },
  session_notes: {
    description: 'The newest playtest notes file (docs/playtests), last N lines. Written by the Lemon brain or by a live agent.',
    inputSchema: obj({ lines: { type: 'number' } }, []),
    run: async a => {
      const files = readdirSync(playtests).filter(f => /^\d{4}-\d\d-\d\d-\d+\.md$/.test(f))
        .sort((x, y) => x.localeCompare(y, undefined, { numeric: true }))
      if (!files.length) return 'No playtest notes yet.'
      const f = files.at(-1)
      return `${f}\n` + readFileSync(join(playtests, f), 'utf8').split(/\r?\n/).slice(-(a.lines || 80)).join('\n')
    },
  },
}

if (admin) {
  Object.assign(tools, {
    server_start: { description: 'Start the test server (a minute or two; the local one builds the mod from source first).', inputSchema: obj({}), run: async () => (await pd(['start'], 400000)).out },
    server_stop: { description: 'Save and stop the test server. Only if the player asks.', inputSchema: obj({}), run: async () => (await pd(['stop'], 200000)).out },
    room_bias_hold: {
      description: 'PD-90: the moment the player agrees to a bias, hold their doors (no preview, no commit, a countdown on the door screen) while you set it up. Setting or clearing the bias releases the hold and tells the player it is done; if you get stuck the hold runs out by itself (default 90 s, at most 180) and says so. seconds 0 releases it.',
      inputSchema: obj({ player, seconds: { type: 'number', description: 'Seconds to hold, 1 to 180. Default 90. 0 releases.' } }, ['player']),
      run: async a => {
        // PD-138: a call without player sent the word "undefined" to the server
        // ("undefined is not online"). With one player online, hold theirs.
        let target = a.player
        if (!target) {
          const listed = (await pd(['cmd', 'list'], 10000)).out
          const names = (listed.split(':').slice(1).join(':') || '').split(',').map(n => n.trim()).filter(Boolean)
          if (names.length !== 1) {
            return `room_bias_hold needs player: ${names.length ? 'online are ' + names.join(', ') : 'nobody is online'}.`
          }
          target = names[0]
        }
        const args = ['cmd', 'dungeon', 'admin', 'bias', 'hold', target]
        if (a.seconds !== undefined) args.push(String(Math.max(0, Math.min(180, Math.round(a.seconds)))))
        return (await pd(args, 10000)).out
      },
    },
    room_bias: {
      description: 'Steer the next floors toward a room under test: multiplies its selection weight (2 to 50; 1 removes; room "clear" drops every bias). A nudge, not a guarantee: the room must still fit a cell. Not saved across restarts. Tell the player first; it changes their dungeon. Call room_bias_hold first; setting the bias releases the hold, tells the player, and shows the lean on the door screen.',
      inputSchema: obj({ room: { type: 'string', description: 'Bare room name, e.g. thicket, or "clear"' }, multiplier: { type: 'number' } }, ['room']),
      run: async a => {
        // No multiplier lists the biases in force.
        const args = a.room === 'clear' ? ['clear'] : a.multiplier ? [a.room, String(Math.round(a.multiplier))] : []
        return (await pd(['cmd', 'dungeon', 'admin', 'bias', ...args], 10000)).out
      },
    },
    server_cmd: {
      description: 'Run a console command (no slash). Only with the player\'s yes if it changes their game.',
      inputSchema: obj({ command: { type: 'string' } }),
      run: async a => (await pd(['cmd', ...a.command.split(/\s+/)], 20000)).out,
    },
  })
}

// ---- JSON-RPC ------------------------------------------------------------------------

async function handle(msg) {
  const { id, method, params = {} } = msg
  const ok = result => ({ jsonrpc: '2.0', id, result })
  if (id === undefined) return null // notifications (initialized, cancelled) need no answer
  switch (method) {
    case 'initialize':
      return ok({
        protocolVersion: params.protocolVersion || '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'pocketdungeons-lemon', version: '0.1.0' },
        instructions: 'Drives Lemon on the Pocket Dungeons test server (local, or the Kinetic one when kinetic.env is set). Call knowledge_pack once, then sync, then loop on wait_events. Rules: LEMON_AGENT.md.',
      })
    case 'ping': return ok({})
    case 'tools/list':
      return ok({ tools: Object.entries(tools).map(([name, t]) => ({ name, description: t.description, inputSchema: t.inputSchema })) })
    case 'tools/call': {
      lastCall = Date.now()
      const t = tools[params.name]
      if (!t) return { jsonrpc: '2.0', id, error: { code: -32602, message: `unknown tool ${params.name}` } }
      try {
        const out = await t.run(params.arguments || {})
        return ok({ content: [{ type: 'text', text: out || '(no output)' }] })
      } catch (e) {
        return ok({ content: [{ type: 'text', text: `error: ${e.message}` }], isError: true })
      }
    }
    default:
      return { jsonrpc: '2.0', id, error: { code: -32601, message: `method not found: ${method}` } }
  }
}

async function handleBody(body) {
  if (Array.isArray(body)) return (await Promise.all(body.map(handle))).filter(Boolean)
  return handle(body)
}

// ---- transports ----------------------------------------------------------------------

function authorized(req) {
  const got = Buffer.from(req.headers.authorization || '')
  const want = Buffer.from(`Bearer ${token}`)
  return got.length === want.length && timingSafeEqual(got, want)
}

if (httpPort) {
  if (token.length < 24) {
    console.error('Set PD_MCP_TOKEN to a random secret of 24 or more characters before serving over HTTP.')
    process.exit(2)
  }
  createServer((req, res) => {
    const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(obj === undefined ? '' : JSON.stringify(obj)) }
    if (!req.url.startsWith('/mcp')) return send(404, { error: 'not found' })
    if (!authorized(req)) return send(401, { error: 'unauthorized' })
    if (req.method === 'GET') { res.writeHead(405); return res.end() } // no server-initiated stream
    if (req.method === 'DELETE') { res.writeHead(200); return res.end() }
    if (req.method !== 'POST') return send(405, { error: 'method not allowed' })
    let raw = ''
    req.on('data', c => { raw += c; if (raw.length > 1e6) req.destroy() })
    req.on('end', async () => {
      let body
      try { body = JSON.parse(raw) } catch { return send(400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }) }
      const out = await handleBody(body)
      if (out === null || (Array.isArray(out) && !out.length)) { res.writeHead(202); return res.end() }
      send(200, out)
    })
  }).listen(httpPort, '127.0.0.1', () => console.error(`Lemon MCP on http://127.0.0.1:${httpPort}/mcp${admin ? ' (admin tools on)' : ''}`))
} else {
  const rl = createInterface({ input: process.stdin })
  rl.on('line', async line => {
    if (!line.trim()) return
    let msg
    try { msg = JSON.parse(line) } catch { return }
    const out = await handleBody(msg)
    if (out !== null && !(Array.isArray(out) && !out.length)) process.stdout.write(JSON.stringify(out) + '\n')
  })
}
