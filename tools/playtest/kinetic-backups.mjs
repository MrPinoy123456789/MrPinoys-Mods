// Panel backups of the kingdom server (Kinetic d212ecae): check them, and pull a copy off the host.
//
//   node tools/playtest/kinetic-backups.mjs status
//   node tools/playtest/kinetic-backups.mjs pull [latest|<uuid>] --out <folder>
//
// status   read only. Lists every panel backup (size, age, checksum, locked) and the schedules, and warns when the
//          newest successful backup is more than 30 hours old, when every slot is used, or when a schedule is stuck
//          (still "processing" with its next run in the past). Changes nothing on the server.
// pull     downloads one backup through the panel's signed link into <folder>/<server>-<date>-<uuid8>.tar.gz and checks
//          the panel's sha1 checksum. Read only for the server. Keep the off site copies the doc describes
//          (docs/SERVER_HARDENING.md, "Backups"). A backup is about 2.3 GB; the owner runs this, not an agent.
//
// The API key comes from kinetic.env or the environment and is never printed.
import { pathToFileURL, fileURLToPath } from 'node:url'
import { createWriteStream, mkdirSync, renameSync, existsSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { createHash } from 'node:crypto'
import { Readable } from 'node:stream'

const here = dirname(fileURLToPath(import.meta.url))
const { remoteConfig, Kinetic } = await import(pathToFileURL(resolve(here, 'kinetic.mjs')).href)
const SERVER = process.env.SHIPPED_SERVER_ID || 'd212ecae'
const k = new Kinetic({ ...remoteConfig(), serverId: SERVER })
const [cmd, which = 'latest'] = process.argv.slice(2)
const value = flag => { const i = process.argv.indexOf(flag); return i >= 0 ? process.argv[i + 1] || '' : '' }
const hours = iso => (Date.now() - Date.parse(iso)) / 3600000

const server = (await k.request('GET', '')).attributes
const limit = server.feature_limits?.backups
const list = (await k.request('GET', '/backups')).data.map(b => b.attributes)
  .sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))

if (cmd === 'status' || !cmd) {
  console.log(`backups: ${list.length} of ${limit} slots`)
  for (const b of list) {
    console.log(`  ${b.created_at.slice(0, 16)}  ${(b.bytes / 1e9).toFixed(2)} GB  ${b.is_successful ? 'ok' : 'FAILED'}${b.is_locked ? '  locked' : ''}  ${b.disk}  ${b.checksum || ''}  ${b.uuid}`)
  }
  const good = list.filter(b => b.is_successful)
  if (!good.length) console.log('WARNING: no successful backup at all')
  else if (hours(good[0].created_at) > 30) console.log(`WARNING: the newest backup is ${Math.round(hours(good[0].created_at))} hours old`)
  if (limit && list.length >= limit && list.every(b => b.is_locked)) console.log('WARNING: every slot is locked; a scheduled backup has nowhere to go')
  const schedules = (await k.request('GET', '/schedules')).data.map(s => s.attributes)
  console.log('schedules:')
  for (const s of schedules) {
    const c = s.cron
    const stuck = s.is_active && s.is_processing && s.next_run_at && Date.parse(s.next_run_at) < Date.now() - 3600000
    console.log(`  ${s.name}: ${c.minute} ${c.hour} ${c.day_of_month} ${c.month} ${c.day_of_week}  active=${s.is_active}  last=${(s.last_run_at || 'never').slice(0, 16)} ${s.last_run_status || ''}  next=${(s.next_run_at || '').slice(0, 16)}${stuck ? '  STUCK (processing, next run in the past)' : ''}`)
  }
  process.exit(0)
}

if (cmd === 'pull') {
  const out = value('--out')
  if (!out) { console.log('pass --out <folder> (outside the repository, on a disk with room for about 2.5 GB)'); process.exit(1) }
  const b = which === 'latest' ? list.find(x => x.is_successful) : list.find(x => x.uuid === which)
  if (!b) { console.log('no such backup'); process.exit(1) }
  mkdirSync(out, { recursive: true })
  const name = `${SERVER}-${b.created_at.slice(0, 10)}-${b.uuid.slice(0, 8)}.tar.gz`
  const final = join(out, name)
  if (existsSync(final)) { console.log('already pulled:', final); process.exit(0) }
  const url = (await k.request('GET', `/backups/${b.uuid}/download`)).attributes.url
  const res = await fetch(url)
  if (!res.ok) { console.log('download refused: HTTP', res.status); process.exit(1) }
  const hash = createHash('sha1')
  const part = final + '.part'
  const file = createWriteStream(part)
  let got = 0
  for await (const chunk of Readable.fromWeb(res.body)) {
    hash.update(chunk); got += chunk.length
    if (!file.write(chunk)) await new Promise(r => file.once('drain', r))
  }
  await new Promise(r => file.end(r))
  const sum = 'sha1:' + hash.digest('hex')
  if (b.checksum && b.checksum !== sum) { console.log(`CHECKSUM MISMATCH: panel says ${b.checksum}, file is ${sum}; kept as ${part}`); process.exit(1) }
  renameSync(part, final)
  console.log(`pulled ${final} (${(got / 1e9).toFixed(2)} GB, ${sum} matches the panel)`)
  process.exit(0)
}
console.log('usage: status | pull [latest|<uuid>] --out <folder>')
