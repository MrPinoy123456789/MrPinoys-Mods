#!/usr/bin/env node
// The Lemon brain: a cheap live Lemon. It runs the `wait` loop itself, handles routine
// events with rules, and calls a small model only when the player talks to Lemon, answers
// it, files a report, or clears a floor. The knowledge pack is the cached system prompt.
// Anything it cannot handle is flagged ESCALATE in the notes for a stronger agent.
//
//   set ANTHROPIC_API_KEY, then:
//   node tools/lemon/brain.mjs --player <name> [--model claude-haiku-4-5-20251001] [--dry]
//
// --dry prints what Lemon would do instead of sending it to the server.

import { appendFileSync, existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { context, pd } from '../server/pdcall.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const mod = resolve(here, '..', '..')
const argv = process.argv.slice(2)
const opt = (name, dflt) => { const i = argv.indexOf(name); return i < 0 ? dflt : argv[i + 1] }
const PLAYER = opt('--player', null)
const MODEL = opt('--model', 'claude-haiku-4-5-20251001')
const DRY = argv.includes('--dry')
const KEY = process.env.ANTHROPIC_API_KEY

if (!PLAYER) { console.error('usage: brain.mjs --player <name> [--model <id>] [--dry]'); process.exit(2) }
if (!KEY) { console.error('Set ANTHROPIC_API_KEY first.'); process.exit(2) }

const packPath = join(here, 'out', 'pack.md')
if (!existsSync(packPath)) { console.error('No knowledge pack. Run: node tools/lemon/build-pack.mjs'); process.exit(2) }
const pack = readFileSync(packPath, 'utf8')

const OUTPUT_RULES = `Reply with one JSON object and nothing else:
{"line": "<what Lemon says, under 200 characters, or empty to stay silent>",
 "note": "<one line for the playtest notes: what happened and why it matters>",
 "escalate": <true if you could not answer from the briefing, the player reported a bug, or it needs code or admin action>}`

// ---- notes file ------------------------------------------------------------------------

const playtests = join(mod, 'docs', 'playtests')
const today = new Date().toLocaleDateString('en-CA')
const taken = readdirSync(playtests).map(f => (f.match(new RegExp(`^${today}-(\\d+)\\.md$`)) ?? [])[1]).filter(Boolean).map(Number)
const notesPath = join(playtests, `${today}-${Math.max(0, ...taken) + 1}.md`)
writeFileSync(notesPath, `# Playtest ${today}: ${PLAYER}\n\nlive session, Lemon brain (${MODEL}), draft\n\n`)
const note = line => {
  const stamped = `- ${new Date().toTimeString().slice(0, 8)} ${line}`
  appendFileSync(notesPath, stamped + '\n')
  console.log(stamped)
}

// ---- model -----------------------------------------------------------------------------

const usage = { calls: 0, input: 0, cacheRead: 0, cacheWrite: 0, output: 0 }
const history = [] // last few exchanges, so follow-ups make sense

async function think(task, ctx) {
  const recent = history.slice(-6).join('\n') || '(none yet)'
  const user = `${task}\n\nRecent conversation:\n${recent}\n\nPlayer context:\n${ctx}\n\n${OUTPUT_RULES}`
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'x-api-key': KEY, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 300,
      // The pack never changes during a session: cache it so repeat calls read it cheaply.
      system: [{ type: 'text', text: pack, cache_control: { type: 'ephemeral' } }],
      messages: [{ role: 'user', content: user }],
    }),
  })
  if (!res.ok) throw new Error(`API ${res.status}: ${(await res.text()).slice(0, 300)}`)
  const body = await res.json()
  const u = body.usage ?? {}
  usage.calls++
  usage.input += u.input_tokens ?? 0
  usage.cacheRead += u.cache_read_input_tokens ?? 0
  usage.cacheWrite += u.cache_creation_input_tokens ?? 0
  usage.output += u.output_tokens ?? 0
  const text = body.content?.find(c => c.type === 'text')?.text ?? ''
  const json = text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1)
  try {
    const out = JSON.parse(json)
    return { line: String(out.line ?? '').trim(), note: String(out.note ?? '').trim(), escalate: !!out.escalate }
  } catch {
    return { line: '', note: `unparsed model reply: ${text.slice(0, 200)}`, escalate: true }
  }
}

// ---- Lemon actions ---------------------------------------------------------------------

async function lemon(verb, text) {
  if (!text) return
  const clean = text.replace(/\s*(—|--)\s*/g, ', ').slice(0, 240) // house rule, and the chat limit
  if (DRY) { console.log(`  [dry] lemon ${verb}: ${clean}`); return }
  const { out, ok } = await pd(['lemon', verb, PLAYER, clean], 15000)
  if (!ok) note(`HARNESS lemon ${verb} failed: ${out}`)
}

// ---- event handling --------------------------------------------------------------------

let quiet = false
let greeted = false
let followUps = 0
let lastLeave = null
const asked = [] // times of our unprompted questions, for the four per hour cap

async function onEvent(e) {
  const m = e.match(/^(\S+) (\S+)(?: (\S+))? ?(.*)$/)
  if (!m) return
  const [, , kind, sub] = m
  const who = (e.match(/<([^>]+)>/) ?? [])[1] ?? (kind === 'join' || kind === 'leave' ? sub : null)
  const text = e.replace(/^.*?<[^>]+> ?/, '')
  if (who && who !== PLAYER && kind !== 'floor') return

  if (kind === 'join') {
    lastLeave = null
    note(`join ${who}`)
    if (!greeted) { greeted = true; await lemon('say', 'Hi, I\'m Lemon. Talk to me anytime, just type. I\'ll ask the odd question at quiet moments.') }
  } else if (kind === 'leave') {
    lastLeave = Date.now()
    note(`leave ${who}`)
  } else if (kind === 'lemon' && (sub === 'ask' || sub === 'unanswered')) {
    quiet = false
    followUps = 0
    if (sub === 'ask') await lemon('think', '')
    const ctx = await context(PLAYER)
    const r = await think(`The player asked Lemon: "${text}". Answer it (a puzzle gets a nudge, or a clue if they already asked about this room).`, ctx)
    history.push(`Player: ${text}`, `Lemon: ${r.line}`)
    await lemon('reply', r.line || 'Not sure about that one. Noting it for the devs.')
    note(`CONFUSION Q: "${text}" A: "${r.line}"${r.note ? `. ${r.note}` : ''}${r.escalate ? ' ESCALATE' : ''}`)
  } else if (kind === 'lemon' && sub === 'answer') {
    history.push(`Player (answering): ${text}`)
    if (followUps >= 2 || quiet) { note(`ANSWER "${text}"`); return }
    const r = await think(`The player answered Lemon's last question: "${text}". If a short open "why" or "what" follow-up would reveal more, give it as the line; if the answer was short or closed, leave the line empty.`, await context(PLAYER))
    note(`ANSWER "${text}"${r.note ? `. ${r.note}` : ''}`)
    if (r.line) { followUps++; history.push(`Lemon: ${r.line}`); await lemon('ask', r.line) }
  } else if (kind === 'lemon' && sub === 'quiet') {
    quiet = true
    note('player asked for quiet')
  } else if (kind === 'report') {
    note(`BUG REPORT "${text}" ESCALATE`)
    await lemon('say', 'Got it, noted for the devs. Thanks!')
  } else if (kind === 'floor') {
    note(`floor ${e.replace(/^\S+ floor /, '')}`)
    const hour = Date.now() - 3600000
    while (asked.length && asked[0] < hour) asked.shift()
    if (quiet || asked.length >= 4 || !e.includes(PLAYER)) return
    const r = await think('The player just cleared a floor: a natural break. Ask one short open question anchored to what just happened, preferring an open research question the context gives a way into.', await context(PLAYER))
    if (r.line) { asked.push(Date.now()); followUps = 0; history.push(`Lemon: ${r.line}`); await lemon('ask', r.line) }
  } else if (kind === 'error') {
    note(`ERROR ${e.replace(/^\S+ error /, '')}`)
  } else if (kind === 'server') {
    note(e.replace(/^\S+ /, ''))
  }
}

// ---- main loop -------------------------------------------------------------------------

let stopping = false
async function finish(reason) {
  if (stopping) return
  stopping = true
  note(`session end: ${reason}`)
  if (!DRY) await pd(['lemon', 'mode', PLAYER, 'guide'], 15000)
  appendFileSync(notesPath, `\n## Model usage\n\n${usage.calls} calls to ${MODEL}: ${usage.input} input, ${usage.cacheRead} cache read, ${usage.cacheWrite} cache write, ${usage.output} output tokens.\n`)
  console.log(`Notes: ${notesPath}`)
  process.exit(0)
}
process.on('SIGINT', () => finish('stopped by owner'))

const status = await pd(['status'], 15000)
if (!status.out.startsWith('UP')) { console.error('The test server is down. Start it with: server start'); process.exit(1) }
await pd(['sync'], 15000)
note(`brain started for ${PLAYER}; notes in ${notesPath}`)

let downSince = null
for (;;) {
  const { out } = await pd(['wait', '--player', PLAYER, '--timeout', '240'], 300000)
  if (out.startsWith('server is down')) { downSince ??= Date.now() } else downSince = null
  for (const e of out.split(/\r?\n/).filter(Boolean)) {
    if (/^no new events|^server is down/.test(e)) continue
    try { await onEvent(e) } catch (err) { note(`HARNESS brain error on "${e}": ${err.message}`) }
  }
  if (lastLeave && Date.now() - lastLeave > 15 * 60000) await finish('player offline 15 minutes')
  if (downSince && Date.now() - downSince > 10 * 60000) await finish('server down 10 minutes')
}
