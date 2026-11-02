// Stages the server hardening set (deploy/server-hardening) on the kingdom server (Kinetic d212ecae).
//
//   node tools/playtest/hardening-stage.mjs --dry
//   node tools/playtest/hardening-stage.mjs --no-restart [--skip id,id] [--no-properties] [--no-configs]
//   node tools/playtest/hardening-stage.mjs --restart --owner-approved [--allow name,name]
//   node tools/playtest/hardening-stage.mjs --lp-setup --owner-approved [--strict]
//   node tools/playtest/hardening-stage.mjs --rollback <before-stamp> --no-restart
//
// Only run it when the owner has asked for this deploy. Read docs/SERVER_HARDENING.md first.
//
// --dry           checks the server's Minecraft version, lists what would change and verifies every jar's hashes;
//                 changes nothing.
// --no-restart    backs up, then uploads: the jars from deploy/server-hardening/jars (or, for jars not kept in git,
//                 downloaded from Modrinth and checked against manifest.json), the config files, and the changed keys
//                 of server.properties. Nothing new loads until the owner restarts the server.
// --restart       the same, then saves, stops and starts the server. Refused unless --owner-approved is also given
//                 and nobody outside --allow is online (as shipped-deploy.mjs).
// --lp-setup      sends the LuckPerms console commands in luckperms-groups.txt (and luckperms-strict.txt with
//                 --strict) to a running server where LuckPerms has loaded. Needs --owner-approved.
// --rollback S    puts back the server.properties and config files saved with suffix S and moves the new jars out
//                 of /mods into /mods_backup. With --no-restart only; the owner restarts.
//
// Backups follow shipped-deploy.mjs: every file replaced goes to /mods_backup with a before-<time> suffix first.
// ops.json and whitelist.json are never touched: a running server rewrites them from memory, so change operators
// with /deop in game or in the console. The API key comes from kinetic.env or the environment and is never printed.
import { pathToFileURL, fileURLToPath } from 'node:url'
import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { createHash } from 'node:crypto'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..', '..')
const set = resolve(root, 'deploy', 'server-hardening')
const { remoteConfig, Kinetic } = await import(pathToFileURL(resolve(here, 'kinetic.mjs')).href)

const args = process.argv.slice(2)
const has = flag => args.includes(flag)
const value = flag => { const i = args.indexOf(flag); return i >= 0 ? args[i + 1] || '' : '' }
const SERVER = process.env.SHIPPED_SERVER_ID || 'd212ecae'
const OWNER = process.env.SHIPPED_OWNER || 'MrPinoy123456789'
const allowed = new Set([OWNER, ...value('--allow').split(',').filter(Boolean)])
const skip = new Set(value('--skip').split(',').filter(Boolean))
const stamp = 'before-' + new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
const dry = has('--dry'), noRestart = has('--no-restart'), restart = has('--restart'), approved = has('--owner-approved')
const sleep = ms => new Promise(r => setTimeout(r, ms))
const die = msg => { console.log(msg); process.exit(1) }

const modes = [dry, noRestart, restart, has('--lp-setup')].filter(Boolean).length
if (modes !== 1) die('pass exactly one of --dry, --no-restart, --restart or --lp-setup (see the header of this file)')
if (has('--rollback') && !noRestart) die('--rollback runs with --no-restart only; the owner restarts')
if (restart && !approved) die('refusing to restart: the owner restarts the server. Use --no-restart, or --restart --owner-approved after the owner has said yes for this restart.')
if (has('--lp-setup') && !approved) die('refusing to send console commands without --owner-approved (the owner says yes first)')

const manifest = JSON.parse(readFileSync(resolve(set, 'manifest.json'), 'utf8'))
const k = new Kinetic({ ...remoteConfig(), serverId: SERVER })
const ls = async d => (await k.request('GET', '/files/list', { query: { directory: d } })).data.map(x => x.attributes)
const names = async d => (await ls(d)).map(a => a.name)
const exists = async path => (await k.stat(path)) !== null

async function log() {
  for (let i = 0; i < 4; i++) {
    try {
      const sig = await k.request('GET', '/files/download', { query: { file: '/logs/latest.log' } })
      return Buffer.from(await (await fetch(sig.attributes.url)).arrayBuffer()).toString('utf8').split(/\r?\n/)
    } catch { await sleep(3000) }
  }
  return []
}

// ---- 0. LuckPerms console setup ----
if (has('--lp-setup')) {
  if ((await k.state()) !== 'running') die('server is not running; aborting')
  const boot = await log()
  if (!boot.some(l => /LuckPerms/i.test(l))) die('the log does not mention LuckPerms; is it installed and loaded? aborting')
  const files = [manifest.luckperms, ...(has('--strict') ? [manifest.luckpermsStrict] : [])]
  for (const f of files) {
    const lines = readFileSync(resolve(set, f), 'utf8').split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith('#'))
    console.log(`${f}: ${lines.length} commands`)
    for (const line of lines) { await k.command(line); await sleep(400) }
  }
  await sleep(3000)
  const tail = (await log()).slice(-60).filter(l => /luckperms|LP|error|Unknown/i.test(l))
  console.log(tail.slice(-20).join('\n'))
  process.exit(0)
}

// ---- 1. Is it safe to go ahead? ----
const mods = await names('/mods')
const target = (mods.find(n => /^fabric-api-.*\+(\d+\.\d+(\.\d+)?)\.jar$/.test(n)) || '').match(/\+(\d+\.\d+(\.\d+)?)\.jar$/)?.[1]
console.log(`server mods are for Minecraft ${target}; this set is for ${manifest.minecraft}`)
if (target !== manifest.minecraft) die('version mismatch; aborting')
const api = mods.find(n => n.startsWith('fabric-api-'))
if (api !== `fabric-api-${manifest.fabricApi}.jar`) console.log(`note: the server has ${api}, the set was checked with fabric-api-${manifest.fabricApi}.jar`)

// ---- rollback ----
if (has('--rollback')) {
  const s = value('--rollback')
  if (!/^before-\d{12}$/.test(s)) die('--rollback wants the suffix printed by the stage run, such as before-202611020800')
  const backups = await names('/mods_backup')
  const putBack = []
  const props = `server.properties.${s}`
  if (backups.includes(props)) putBack.push({ from: props, dir: '/', name: 'server.properties' })
  for (const c of manifest.configs) {
    const saved = flat(c.remote) + '.' + s
    if (backups.includes(saved)) putBack.push({ from: saved, dir: dirOf(c.remote), name: baseOf(c.remote) })
  }
  for (const p of putBack) {
    await k.upload(p.dir, [{ name: p.name, bytes: await k.read('/mods_backup/' + p.from) }])
    console.log('restored', p.dir + (p.dir.endsWith('/') ? '' : '/') + p.name, 'from', p.from)
  }
  for (const j of manifest.jars) {
    if (!mods.includes(j.file)) continue
    await k.rename('/', 'mods/' + j.file, `mods_backup/${j.file.replace(/\.jar$/, '')}.removed-${s.slice(7)}.jar`)
    console.log('moved out of /mods:', j.file)
  }
  for (const j of manifest.jars) {
    const old = backups.find(n => n.startsWith(j.file.replace(/\.jar$/, '') + '.') && n.endsWith(`.${s}.jar`))
    if (old) console.log('an older copy was replaced by this set; put it back by hand if wanted: /mods_backup/' + old)
  }
  console.log('rollback uploaded; nothing changes until the owner restarts. Configs written by the new mods (config/luckperms, banhammer-sqlite.db) are left in place and are harmless without their jars.')
  process.exit(0)
}

// ---- 2. What goes up ----
function sha(bytes, alg) { return createHash(alg).update(bytes).digest('hex') }
const jars = []
for (const j of manifest.jars) {
  if (skip.has(j.id)) { console.log('skipped by --skip:', j.id); continue }
  let bytes
  if (j.staged && existsSync(resolve(set, j.staged))) bytes = readFileSync(resolve(set, j.staged))
  else {
    if (new URL(j.url).host !== 'cdn.modrinth.com') die('refusing a jar from outside cdn.modrinth.com: ' + j.url)
    bytes = Buffer.from(await (await fetch(j.url)).arrayBuffer())
  }
  if (bytes.length !== j.size || sha(bytes, 'sha512') !== j.sha512 || sha(bytes, 'sha1') !== j.sha1) die(`hash or size mismatch for ${j.file}; aborting`)
  const others = mods.filter(n => n !== j.file && new RegExp(j.replaces, 'i').test(n))
  if (others.length) die(`/mods already has ${others.join(', ')} for ${j.id}; remove or back it up by hand first, then run again`)
  jars.push({ ...j, bytes, present: mods.includes(j.file) })
}
const configs = has('--no-configs') ? [] : manifest.configs.map(c => ({ ...c, bytes: readFileSync(resolve(set, c.local)) }))
const changes = has('--no-properties') ? [] : readFileSync(resolve(set, manifest.serverProperties), 'utf8').split(/\r?\n/)
  .map(l => l.trim()).filter(l => l && !l.startsWith('#')).map(l => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)])

let propsOld = null, propsNew = null
if (changes.length) {
  propsOld = (await k.read('/server.properties')).toString('utf8')
  const nl = propsOld.includes('\r\n') ? '\r\n' : '\n'
  const lines = propsOld.split(/\r?\n/)
  for (const [key, v] of changes) {
    const i = lines.findIndex(l => l.startsWith(key + '='))
    const was = i >= 0 ? lines[i].slice(key.length + 1) : '(absent)'
    console.log(`server.properties ${key}: ${was} -> ${v}`)
    if (i >= 0) lines[i] = `${key}=${v}`; else lines.splice(lines.length - (lines[lines.length - 1] === '' ? 1 : 0), 0, `${key}=${v}`)
  }
  propsNew = Buffer.from(lines.join(nl), 'utf8')
}
console.log('jars:', jars.map(j => `${j.file} ${j.bytes.length}${j.present ? ' (already there, same name)' : ''}`).join(', ') || 'none')
console.log('configs:', configs.map(c => c.remote).join(', ') || 'none')
// --dry and --no-restart send no console command at all (not even list): they only read and upload files.
if (dry) { console.log('dry run; nothing changed. Hashes of every jar matched manifest.json.'); process.exit(0) }

// ---- 3. Back up, then upload ----
if (restart) {
  if ((await k.state()) !== 'running') die('server is not running; aborting')
  const others = (await players()).filter(n => !allowed.has(n))
  if (others.length) die('other players online; ask the owner first: ' + others.join(', '))
}
const backups = []
for (const j of jars) if (j.present) backups.push({ name: `${j.file.replace(/\.jar$/, '')}.${stamp}.jar`, bytes: await k.read('/mods/' + j.file) })
for (const c of configs) if (await exists(c.remote)) backups.push({ name: `${flat(c.remote)}.${stamp}`, bytes: await k.read(c.remote) })
if (propsOld !== null) backups.push({ name: `server.properties.${stamp}`, bytes: Buffer.from(propsOld, 'utf8') })
if (backups.length) await k.upload('/mods_backup', backups)
console.log('backed up', backups.map(b => b.name).join(', ') || 'nothing')

if (restart) {
  await k.command('say Restarting in 10 seconds for a server update.')
  await sleep(10000)
  await k.command('save-all flush'); await sleep(3000)
  await k.power('stop')
  for (let i = 0; i < 40 && (await k.state()) !== 'offline'; i++) await sleep(3000)
  if ((await k.state()) !== 'offline') die('did not stop; aborting before any upload')
}
// New jars are new files, so a running server is never reading them. A jar already present under the same name is
// swapped in by a rename, as shipped-deploy.mjs does, never rewritten in place.
for (const j of jars) {
  if (!j.present || restart) { await k.upload('/mods', [{ name: j.file, bytes: j.bytes }]); continue }
  await k.upload('/mods', [{ name: j.file + '.new', bytes: j.bytes }])
  try { await k.rename('/mods', j.file + '.new', j.file) } catch { await k.remove('/mods', [j.file]); await k.rename('/mods', j.file + '.new', j.file) }
}
for (const c of configs) await k.upload(dirOf(c.remote), [{ name: baseOf(c.remote), bytes: c.bytes }])
if (propsNew) await k.upload('/', [{ name: 'server.properties', bytes: propsNew }])
const after = await names('/mods')
for (const j of jars) if (!after.includes(j.file)) die('MISSING after upload: ' + j.file)
console.log('uploaded', jars.length, 'jars,', configs.length, 'configs', propsNew ? 'and server.properties' : '')
if (!restart) {
  console.log(`staged without a restart; nothing new loads until the owner restarts. Rollback suffix: ${stamp}`)
  console.log(`after the restart: node tools/playtest/hardening-stage.mjs --lp-setup --owner-approved   (or paste ${manifest.luckperms} into the console)`)
  process.exit(0)
}
await k.power('start')
let lines = []
for (let i = 0; i < 40; i++) {
  await sleep(8000)
  lines = await log()
  if (lines.some(x => /Done \(|Crash report|Incompatible mods|Mixin apply failed/.test(x))) break
}
await sleep(8000)
lines = await log()
console.log(lines.filter(x => /Done \(|ERROR|Incompatible|Mixin|LuckPerms|AntiXray|BanHammer|spark|Charter state/i.test(x)).slice(-25).join('\n'))
console.log('state', await k.state(), '| rollback suffix', stamp)

async function players() {
  await k.command('list'); await sleep(3000)
  const line = (await log()).filter(x => /There are \d+ of a max/.test(x)).pop() || ''
  return (line.split('online:')[1] || '').split(',').map(s => s.trim()).filter(Boolean)
}
function flat(remote) { return remote.replace(/^\//, '').replace(/\//g, '_') }
function dirOf(remote) { return remote.slice(0, remote.lastIndexOf('/')) || '/' }
function baseOf(remote) { return remote.slice(remote.lastIndexOf('/') + 1) }
