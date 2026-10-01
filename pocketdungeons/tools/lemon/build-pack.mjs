#!/usr/bin/env node
// Builds Lemon's knowledge pack: one compact, stable markdown briefing that replaces
// reading the repo during play. It is the cached system prompt for brain.mjs and the
// knowledge_pack tool of the MCP server. Rebuild after changing rooms, docs or GUIDE.md.
//
//   node tools/lemon/build-pack.mjs        writes tools/lemon/out/pack.md

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const mod = resolve(here, '..', '..')
const read = p => existsSync(p) ? readFileSync(p, 'utf8') : ''

// Lemon's rules, condensed from docs/LEMON_AGENT.md sections 1, 2 and 5.
const persona = `# Lemon

You are Lemon, a small glowing allay guiding one player through Pocket Dungeons, a
Minecraft roguelite, on the owner's local test server. Two jobs: answer questions
and unstick the player without spoiling puzzles, and learn what is confusing, fun,
frustrating or broken by asking short questions at natural breaks.

Voice: short, warm, a little playful, never corporate. One or two sentences, under
200 characters. Never use em dashes or double hyphens as punctuation.

Rules:
- Puzzles (locks, plates, mechanisms, parkour, hidden routes) are the player's to solve.
  First ask for help: a nudge (where to look). Asked again: a clue (which part matters,
  not the order). Never the solution; offer to note it as too hard instead.
- Combat, resources and mechanics are not puzzles: explain freely.
- Not sure? Say you are not sure and that you are noting it for the devs. Never guess a mechanic.
- Interview: open questions anchored to what just happened. Never lead, never defend the
  design, never explain it back in reply to criticism. Short answer means move on.
- Every question the player asks is data about what the game failed to teach.
`

function section(md, heading) {
  const i = md.indexOf(heading)
  if (i < 0) return ''
  const next = md.indexOf('\n## ', i + heading.length)
  return md.slice(i, next < 0 ? undefined : next).trim()
}

// Open and partial agenda items: id, question, latest status only (the evidence stays in the file).
function agenda() {
  const md = read(join(mod, 'docs', 'playtests', 'AGENDA.md'))
  const items = md.split(/\n(?=## A\d)/).slice(1)
  const rows = items.map(block => {
    const title = block.split('\n')[0].replace(/^## /, '')
    const status = (block.match(/\*\*Status:\*\* (\w+)/) ?? [])[1] ?? '?'
    const retest = (block.match(/Retest[^\n]*/g) ?? []).at(-1) ?? ''
    return { title, status, retest }
  }).filter(r => r.status === 'open' || r.status === 'partial')
  return rows.map(r => `- ${r.title} (${r.status})${r.retest ? `. ${r.retest}` : ''}`).join('\n')
}

function rooms() {
  const dir = join(mod, 'src', 'main', 'resources', 'data', 'pocketdungeons', 'dungeon_room')
  const lines = readdirSync(dir).filter(f => f.endsWith('.json')).sort().map(f => {
    const r = JSON.parse(readFileSync(join(dir, f), 'utf8').replace(/^﻿/, '')) // some rooms are saved with a BOM
    const bits = [
      (r.roles ?? []).join('/'),
      r.tier != null && `tier ${r.tier}`,
      r.minDepth != null && `depth ${r.minDepth}+`,
      r.requires?.length && `needs ${r.requires.join(', ')}`,
      r.pressure && `pressure ${r.pressure}`,
      r.access && r.access !== 'open' && `access ${r.access}`,
    ].filter(Boolean)
    return `- ${f.replace(/\.json$/, '')}: ${bits.join(', ')}`
  })
  return lines.join('\n')
}

const guide = read(join(here, 'GUIDE.md'))
const hints = read(join(here, 'HINTS.md'))
const decisions = section(read(join(mod, 'docs', 'AUDIT_2026-09.md')), '## 11. Owner decisions log')

const pack = [
  persona,
  guide ? `# Game guide\n\n${guide.replace(/^# .*\n/, '').trim()}` : '',
  hints ? `# Puzzle hint ladders\n\n${hints.replace(/^# .*\n/, '').trim()}` : '',
  decisions ? `# How the game is meant to work (owner decisions)\n\n${decisions.replace(/^## .*\n/, '').trim()}` : '',
  `# Open research questions (ask about these at breaks)\n\n${agenda()}`,
  `# Rooms (id: roles, tier, depth, requirements)\n\n${rooms()}`,
].filter(Boolean).join('\n\n')

mkdirSync(join(here, 'out'), { recursive: true })
writeFileSync(join(here, 'out', 'pack.md'), pack)
console.log(`Wrote tools/lemon/out/pack.md: ${pack.length} chars, about ${Math.round(pack.length / 4)} tokens.`)
if (!guide) console.log('No GUIDE.md: Lemon has no plain-words mechanics guide. Write tools/lemon/GUIDE.md.')
if (!hints) console.log('No HINTS.md: Lemon has no puzzle hint ladders and will only nudge generically.')
