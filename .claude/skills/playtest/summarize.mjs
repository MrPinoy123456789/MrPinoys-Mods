#!/usr/bin/env node
// Summarises Pocket Dungeons playtest journal files (format:
// pocketdungeons/docs/PLAYTEST_EVENTS.md) into a compact markdown digest for
// the /playtest interview. No dependencies.
//
// Usage: node summarize.mjs [--root <dir>]... [--date <yyyy-mm-dd|latest|all>]
//                           [--player <name or uuid>] [--list]
// With no --root, searches the mod's run folders under the workspace.

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const workspace = resolve(here, '..', '..', '..')

const args = process.argv.slice(2)
const opt = { roots: [], date: 'latest', player: null, list: false }
for (let i = 0; i < args.length; i++) {
  const a = args[i]
  if (a === '--root') opt.roots.push(resolve(args[++i]))
  else if (a === '--date') opt.date = args[++i]
  else if (a === '--player') opt.player = args[++i].toLowerCase()
  else if (a === '--list') opt.list = true
}

/** Every `.../pocketdungeons/playtest` folder under the roots, a few levels deep. */
function findJournalDirs(roots) {
  const found = []
  const walk = (dir, depth) => {
    if (depth > 6) return
    let names
    try { names = readdirSync(dir) } catch { return }
    for (const name of names) {
      if (name === 'node_modules' || name === '.git' || name === 'build' || name === '.gradle') continue
      const full = join(dir, name)
      let st
      try { st = statSync(full) } catch { continue }
      if (!st.isDirectory()) continue
      if (name === 'playtest' && dir.endsWith('pocketdungeons')) found.push(full)
      else walk(full, depth + 1)
    }
  }
  for (const r of roots) if (existsSync(r)) walk(r, 0)
  return [...new Set(found)]
}

const roots = opt.roots.length ? opt.roots : [join(workspace, 'pocketdungeons')]
const dirs = findJournalDirs(roots)
if (!dirs.length) {
  console.log(`No playtest journal found under: ${roots.join(', ')}`)
  console.log('The in-game journal writes <world>/pocketdungeons/playtest/<date>/<uuid>.jsonl; pass --root <server folder> if it lives elsewhere.')
  process.exit(0)
}

const dates = new Map() // date -> [file paths]
for (const d of dirs) {
  for (const date of readdirSync(d)) {
    const full = join(d, date)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !statSync(full).isDirectory()) continue
    for (const f of readdirSync(full)) if (f.endsWith('.jsonl')) {
      if (!dates.has(date)) dates.set(date, [])
      dates.get(date).push(join(full, f))
    }
  }
}
const sortedDates = [...dates.keys()].sort()
if (opt.list) {
  for (const d of sortedDates) console.log(`${d}: ${dates.get(d).length} player file(s)`)
  process.exit(0)
}
if (!sortedDates.length) { console.log('Journal folders exist but hold no events yet.'); process.exit(0) }
const pick = opt.date === 'all' ? sortedDates : [opt.date === 'latest' ? sortedDates.at(-1) : opt.date]

/** Parses a JSONL file, skipping blank and torn lines. */
function readEvents(file) {
  const out = []
  let torn = 0
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue
    try { out.push(JSON.parse(line)) } catch { torn++ }
  }
  return { events: out, torn }
}

const byPlayer = new Map()
let tornTotal = 0
for (const date of pick) {
  for (const file of dates.get(date) ?? []) {
    const { events, torn } = readEvents(file)
    tornTotal += torn
    for (const e of events) {
      const key = e.player ?? 'unknown'
      if (!byPlayer.has(key)) byPlayer.set(key, [])
      byPlayer.get(key).push(e)
    }
  }
}

const hhmm = t => (t ?? '').slice(11, 16)
const median = xs => { const s = [...xs].sort((a, b) => a - b); return s.length ? s[Math.floor(s.length / 2)] : 0 }
const out = []
out.push(`# Playtest digest (${pick.join(', ')})`)
if (tornTotal) out.push(`_${tornTotal} torn line(s) skipped (likely a crash mid-write)._`)

for (const [uuid, evs] of byPlayer) {
  evs.sort((a, b) => (a.t ?? '').localeCompare(b.t ?? ''))
  const name = evs.findLast(e => e.name)?.name ?? uuid
  if (opt.player && !(name.toLowerCase().includes(opt.player) || uuid.toLowerCase().startsWith(opt.player))) continue
  const count = ev => evs.filter(e => e.ev === ev)
  const floors = count('floor_complete')
  const banks = count('bank')
  const checkins = count('checkin')
  const reports = count('report')
  const errors = count('error')
  const rescues = count('rescue')
  const omen = count('omen_rise')

  out.push('', `## ${name}`, `${hhmm(evs[0].t)} to ${hhmm(evs.at(-1).t)} UTC, ${evs.length} events`)
  out.push(`Floors ${floors.length}, banks ${banks.length}, rescues ${rescues.length}, check-ins ${checkins.length}, reports ${reports.length}, errors ${errors.length}`)

  const flags = []
  const medSec = median(floors.map(f => f.seconds ?? 0))
  for (const f of floors) {
    if ((f.rescues ?? 0) >= 3) flags.push(`${hhmm(f.t)} floor ${f.floor} (${f.zone}): ${f.rescues} rescues`)
    if (medSec && (f.seconds ?? 0) > 2 * medSec) flags.push(`${hhmm(f.t)} floor ${f.floor} (${f.zone}): ${f.seconds}s, over twice the median ${medSec}s`)
    if ((f.omen ?? 0) >= 3) flags.push(`${hhmm(f.t)} floor ${f.floor} (${f.zone}): ended at omen ${f.omen}`)
  }
  for (const c of checkins) if (c.score != null && c.score <= 2) flags.push(`${hhmm(c.t)} low check-in ${c.score}/5${c.comment ? `: "${c.comment}"` : ''}`)
  for (const e of count('quit_floor')) flags.push(`${hhmm(e.t)} quit floor ${e.floor} (-${e.penalty} levels)`)
  for (const e of count('session_leave')) if (e.reason === 'disconnect') flags.push(`${hhmm(e.t)} disconnected (phase ${e.phase})`)
  for (const e of count('owner_hold')) if (e.state === 'expire') flags.push(`${hhmm(e.t)} owner reconnect grace expired`)
  for (const e of count('leave_dungeon')) if (e.reason === 'checkpoint_exit' || e.reason === 'purge') flags.push(`${hhmm(e.t)} left the dungeon: ${e.reason}`)
  if (flags.length) out.push('', '**Moments worth asking about**', ...flags.map(f => `- ${f}`))

  if (reports.length) {
    out.push('', '**Reports (highest priority)**')
    for (const r of reports) out.push(`- ${hhmm(r.t)} ${r.room ? `in ${r.room} ` : ''}at ${r.pos ?? '?'}: "${r.text}" (before: ${(r.recent ?? []).join(' > ')})`)
  }
  if (errors.length) {
    out.push('', '**Mod errors during play**')
    for (const e of errors.slice(0, 10)) out.push(`- ${hhmm(e.t)} ${e.message}`)
  }

  if (floors.length) {
    out.push('', '| time | floor | zone | party | secs | omen | spawners | rescues | blocks | durability | chests |', '|---|---|---|---|---|---|---|---|---|---|---|')
    for (const f of floors) out.push(`| ${hhmm(f.t)} | ${f.floor} | ${f.zone} | ${f.party} | ${f.seconds} | ${f.omen} | ${f.spawners_cleared}/${f.spawners_total} | ${f.rescues} | ${f.blocks_placed} | ${f.durability_used} | ${f.chests} |`)
  }
  if (banks.length) {
    out.push('', '**Banks**')
    for (const b of banks) out.push(`- ${hhmm(b.t)} ${b.trigger}: ${b.floors} floor(s), band ${b.band}, +${b.levels_gained} level(s) (carry ${b.carry}), ${b.chests} chest(s), key now ${b.key_level}`)
  }
  const topups = count('kit_topup')
  if (topups.length) {
    out.push('', '**Kit top-ups**')
    for (const k of topups) out.push(`- ${hhmm(k.t)} band ${k.band}: ${Object.entries(k.granted ?? {}).map(([i, n]) => `${n} ${i.replace('minecraft:', '')}`).join(', ') || 'nothing'}${(k.tools_replaced ?? []).length ? `; tools: ${k.tools_replaced.join(', ')}` : ''}`)
  }
  if (omen.length) {
    const bySource = {}
    for (const o of omen) bySource[o.source] = (bySource[o.source] ?? 0) + (o.amount ?? 1)
    out.push('', `**Omen by source**: ${Object.entries(bySource).map(([s, n]) => `${s} ${n}`).join(', ')}`)
  }
  if (checkins.length) {
    out.push('', '**Check-ins**')
    for (const c of checkins) out.push(`- ${hhmm(c.t)} ${c.prompt}: ${c.score ?? 'skipped'}/5${c.comment ? ` "${c.comment}"` : ''}`)
  }
  const doors = count('door_commit')
  if (doors.length) {
    out.push('', '**Doors taken**: ' + doors.map(d => `${hhmm(d.t)} step ${d.step} L${d.level} ${d.theme}${(d.affixes ?? []).length ? ` [${d.affixes.join(', ')}]` : ''}`).join('; '))
  }
}
console.log(out.join('\n'))
