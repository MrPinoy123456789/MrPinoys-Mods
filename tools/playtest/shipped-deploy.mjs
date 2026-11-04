// Deploys Charter, Foundry, the Foundry content and Pocket Dungeons (wild mode) to the shipped kingdom server
// (Kinetic d212ecae).
//
//   node tools/playtest/shipped-deploy.mjs [--foundry] [--charter] [--pd] [--content] [--worn] [--bedrock] [--allow name,name] [--set key=value ...] [--merge key=value ...] [--pd-set key=value ...] [--jar path ...] [--no-restart] [--dry]
//
// Only run it when the owner has asked for a deploy. It refuses to continue if the server's mods are not the same
// Minecraft version as the jars, if anyone outside --allow is online, or if the server is not running. It backs up
// every jar it replaces into /mods_backup, stops the server with a save, uploads, starts, and prints what the log says.
// --no-restart uploads (after the same backups) without stopping or starting anything and without the players online
// check; the owner restarts when ready, and nothing new loads until then. --merge key=value adds the keys of a JSON
// object to that object in charter.json (for example racePassives) instead of replacing it as --set does.
// --pd uploads the Pocket Dungeons jar; --pd-set key=value changes a key of config/pocketdungeons.json the same way
// --set changes charter.json (for example --pd-set wildTargetActive=60). Never --pd-set mode: the server runs wild.
// The key comes from kinetic.env or the environment and is never printed. See .claude/skills/deploy-shipped-server.
import { pathToFileURL } from 'node:url'
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..', '..')
const { remoteConfig, Kinetic } = await import(pathToFileURL(resolve(here, 'kinetic.mjs')).href)

const args = process.argv.slice(2)
const has = flag => args.includes(flag)
const value = flag => { const i = args.indexOf(flag); return i >= 0 ? args[i + 1] : '' }
const SERVER = process.env.SHIPPED_SERVER_ID || 'd212ecae'
const OWNER = process.env.SHIPPED_OWNER || 'MrPinoy123456789'
const allowed = new Set([OWNER, ...value('--allow').split(',').filter(Boolean)])
const stamp = 'before-' + new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')

const k = new Kinetic({ ...remoteConfig(), serverId: SERVER })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const ls = async d => (await k.request('GET', '/files/list', { query: { directory: d } })).data.map(x => x.attributes.name)

async function log() {
  for (let i = 0; i < 4; i++) {
    try {
      const sig = await k.request('GET', '/files/download', { query: { file: '/logs/latest.log' } })
      return Buffer.from(await (await fetch(sig.attributes.url)).arrayBuffer()).toString('utf8').split(/\r?\n/)
    } catch { await sleep(3000) }
  }
  return []
}

// --set key=value (repeatable) changes keys of config/charter/charter.json while the server is stopped; the old file is backed up.
const parseKv = flag => args.flatMap((a, i) => (a === flag && args[i + 1] ? [args[i + 1]] : [])).map(kv => {
  const at = kv.indexOf('=')
  let v = kv.slice(at + 1)
  try { v = JSON.parse(v) } catch { /* a plain string */ }
  return [kv.slice(0, at), v]
})
const sets = parseKv('--set')
const merges = parseKv('--merge')
const pdSets = parseKv('--pd-set')
if (pdSets.some(([key]) => key === 'mode')) { console.log('--pd-set mode: the kingdom server runs wild mode only; refusing'); process.exit(1) }
const noRestart = has('--no-restart')

// Where each piece is built. The worktree layout changes, so the jar folders can be overridden.
const FOUNDRY = process.env.FOUNDRY_DIR || resolve(root, 'foundry')
const CHARTER = process.env.CHARTER_DIR || resolve(root, 'charter')
const FOUNDRY_JAR = 'MrPinoys_Foundry-0.1.0.jar'
const CHARTER_JAR = 'MrPinoys_Charter-0.1.0.jar'
const PD = process.env.PD_DIR || resolve(root, 'pocketdungeons')
const PD_JAR = 'MrPinoys_Pocket_Dungeons-0.1.0.jar'
const jarOf = (dir, name) => resolve(dir, dir === CHARTER ? 'fabric' : '.', 'build', 'libs', name)
const files = (dir, re) => existsSync(dir) ? readdirSync(dir).filter(n => re.test(n)).map(n => ({ name: n, bytes: readFileSync(resolve(dir, n)) })) : []

// 1. Is it safe to go ahead?
const mods = await ls('/mods')
const target = (mods.find(n => /^fabric-api-.*\+(\d+\.\d+(\.\d+)?)\.jar$/.test(n)) || '').match(/\+(\d+\.\d+(\.\d+)?)\.jar$/)?.[1]
const props = readFileSync(resolve(CHARTER, 'gradle.properties'), 'utf8')
const built = props.match(/^minecraft_version=(.+)$/m)?.[1]?.trim()
console.log(`server mods are for Minecraft ${target}; Charter builds for ${built}`)
if (!target || target !== built) { console.log('version mismatch; aborting. Build for the server version first.'); process.exit(1) }
if (!noRestart && (await k.state()) !== 'running') { console.log('server is not running; aborting'); process.exit(1) }
await k.command('list'); await sleep(3000)
const line = (await log()).filter(x => /There are \d+ of a max/.test(x)).pop() || ''
console.log('list says:', line.replace(/^\[[^\]]*\]\s*/, ''))
const others = (line.split('online:')[1] || '?').split(',').map(s => s.trim()).filter(s => s && !allowed.has(s))
if (others.length && !noRestart) { console.log('other players online; ask the owner first:', others.join(', ')); process.exit(1) }

// 2. What goes up.
const jars = []
if (has('--foundry')) jars.push({ name: FOUNDRY_JAR, bytes: readFileSync(jarOf(FOUNDRY, FOUNDRY_JAR)) })
if (has('--charter')) jars.push({ name: CHARTER_JAR, bytes: readFileSync(jarOf(CHARTER, CHARTER_JAR)) })
if (has('--pd')) jars.push({ name: PD_JAR, bytes: readFileSync(jarOf(PD, PD_JAR)) })
// --jar <path> (repeatable): any other mod jar, uploaded under its own file name; an old copy is backed up first.
for (const path of args.flatMap((a, i) => (a === '--jar' && args[i + 1] ? [args[i + 1]] : []))) jars.push({ name: path.split(/[\/]/).pop(), bytes: readFileSync(path) })
const ex = resolve(FOUNDRY, 'examples')
const content = has('--content') ? [
  ['/config/foundry/content/kingdoms/items', files(resolve(ex, 'content/kingdoms/items'), /\.json$/)],
  ['/config/foundry/content/kingdoms/recipes', files(resolve(ex, 'content/kingdoms/recipes'), /\.json$/)],
  ['/config/foundry/content/demo/items', files(resolve(ex, 'content/demo/items'), /^fairy_wand.*\.json$/)],
  ['/config/foundry/assets/kingdoms/textures/item', files(resolve(ex, 'assets/kingdoms/textures/item'), /\.png$/)],
  // the race weapons' 3D models (held_model) and their textures
  ['/config/foundry/assets/kingdoms/models/item/held', files(resolve(ex, 'assets/kingdoms/models/item/held'), /\.json$/)],
  ['/config/foundry/assets/kingdoms/textures/item/held', files(resolve(ex, 'assets/kingdoms/textures/item/held'), /\.png$/)],
  ['/config/foundry/assets/demo/textures/item', files(resolve(ex, 'assets/demo/textures/item'), /^fairy_wand.*\.png$/)],
] : []
// --worn: the 3D worn trinket models, their textures (in the items atlas folder), the worn display placements and race traits, the
// client item definitions of the antlers on each helmet and the atlas file that adds the vanilla helmet textures.
const worn = has('--worn') ? [
  ['/config/foundry/assets/kingdoms/models/item/worn', files(resolve(ex, 'assets/kingdoms/models/item/worn'), /\.json$/)],
  ['/config/foundry/assets/kingdoms/textures/item/worn', files(resolve(ex, 'assets/kingdoms/textures/item/worn'), /\.png$/)],
  ['/config/foundry', files(resolve(ex, 'config'), /^(worn_displays|race_traits)\.json$/)],
  ['/config/foundry/assets/kingdoms/items', files(resolve(ex, 'assets/kingdoms/items'), /\.json$/)],
  ['/config/foundry/assets/minecraft/atlases', files(resolve(ex, 'assets/minecraft/atlases'), /\.json$/)],
] : []
const bedrock = has('--bedrock') ? [
  ['/config/Geyser-Fabric/custom_mappings', files(resolve(ex, 'bedrock/custom_mappings'), /\.json$/)],
  ['/config/Geyser-Fabric/packs', files(resolve(ex, 'bedrock'), /\.mcpack$/)],
] : []
console.log('jars:', jars.map(j => `${j.name} ${j.bytes.length}`).join(', ') || 'none',
  '| content folders:', content.length, '| worn folders:', worn.map(([d, l]) => `${d} (${l.length})`).join(', ') || 'none',
  '| bedrock folders:', bedrock.length)
if (has('--dry')) { console.log('dry run; nothing changed'); process.exit(0) }
if (!jars.length && !content.length && !worn.length && !bedrock.length && !sets.length && !merges.length && !pdSets.length) { console.log('nothing to deploy; pass --foundry, --charter, --pd, --content, --worn, --bedrock, --set or --pd-set'); process.exit(1) }

// 3. Back up, stop, upload, start.
const backups = []
for (const j of jars) if (mods.includes(j.name)) backups.push({ name: j.name.replace('.jar', `.${stamp}.jar`), bytes: await k.read('/mods/' + j.name) })
let configNew = null
if (sets.length || merges.length) {
  const old = await k.read('/config/charter/charter.json')
  backups.push({ name: `charter.${stamp}.json`, bytes: old })
  const cfg = JSON.parse(old.toString('utf8'))
  for (const [key, v] of sets) cfg[key] = v
  for (const [key, v] of merges) {
    // A key missing from the file means Charter runs on its built in default for it; merging into nothing would replace
    // that default (this dropped the Merfolk's racePassives once), so stop and say so.
    if (!(key in cfg)) { console.log(`--merge ${key}: the key is not in charter.json, so Charter uses its default; use --set with the whole value`); process.exit(1) }
    cfg[key] = { ...cfg[key], ...v }
  }
  configNew = Buffer.from(JSON.stringify(cfg, null, 2) + '\n', 'utf8')
}
let pdConfigNew = null
if (pdSets.length) {
  const old = await k.read('/config/pocketdungeons.json')
  backups.push({ name: `pocketdungeons.${stamp}.json`, bytes: old })
  const cfg = JSON.parse(old.toString('utf8'))
  for (const [key, v] of pdSets) {
    console.log(`pocketdungeons.json ${key}: ${JSON.stringify(cfg[key])} -> ${JSON.stringify(v)}`)
    cfg[key] = v
  }
  pdConfigNew = Buffer.from(JSON.stringify(cfg, null, 2) + '\n', 'utf8')
}
if (backups.length) await k.upload('/mods_backup', backups)
console.log('backed up', backups.map(b => b.name).join(', ') || 'nothing')
if (noRestart) {
  // A running server has its jars open, and the panel's upload rewrites a file in place, which can break a running
  // server the next time it loads a class. So each jar goes up as <name>.new (Fabric loads only .jar files) and is moved
  // over the old one; a move leaves the running server reading the copy it has open. If the panel will not move over an
  // existing file, the old one is deleted first (same effect), and if anything fails it is uploaded under its own name.
  for (const j of jars) {
    await k.upload('/mods', [{ name: j.name + '.new', bytes: j.bytes }])
    try {
      try {
        await k.rename('/mods', j.name + '.new', j.name)
      } catch {
        await k.remove('/mods', [j.name])
        await k.rename('/mods', j.name + '.new', j.name)
      }
      console.log('swapped in', j.name)
    } catch (e) {
      console.log('could not move', j.name, 'into place (' + e.message + '); uploading it under its own name')
      await k.upload('/mods', [j])
      try { await k.remove('/mods', [j.name + '.new']) } catch { /* leave it; Fabric ignores .new */ }
    }
  }
  const after = await ls('/mods')
  for (const j of jars) if (!after.includes(j.name)) { console.log('MISSING after upload:', j.name); process.exit(1) }
  if (configNew) await k.upload('/config/charter', [{ name: 'charter.json', bytes: configNew }])
  if (pdConfigNew) await k.upload('/config', [{ name: 'pocketdungeons.json', bytes: pdConfigNew }])
  for (const [dir, list] of [...content, ...worn, ...bedrock]) if (list.length) await k.upload(dir, list)
  console.log('uploaded without a restart: nothing new loads until the server is restarted | rollback files are in /mods_backup with the suffix', stamp)
  process.exit(0)
}
await k.command('say Restarting in 10 seconds for an update.')
await sleep(10000)
await k.command('save-all flush'); await sleep(3000)
await k.power('stop')
for (let i = 0; i < 40 && (await k.state()) !== 'offline'; i++) await sleep(3000)
if ((await k.state()) !== 'offline') { console.log('did not stop; aborting'); process.exit(1) }
if (jars.length) await k.upload('/mods', jars)
if (configNew) await k.upload('/config/charter', [{ name: 'charter.json', bytes: configNew }])
if (pdConfigNew) await k.upload('/config', [{ name: 'pocketdungeons.json', bytes: pdConfigNew }])
for (const [dir, list] of [...content, ...worn, ...bedrock]) if (list.length) await k.upload(dir, list)
console.log('uploaded')
await k.power('start')
let lines = []
for (let i = 0; i < 40; i++) {
  await sleep(8000)
  lines = await log()
  if (lines.some(x => /Done \(|Crash report|Incompatible mods/.test(x))) break
}
await sleep(8000)
lines = await log()
console.log(lines.filter(x => /Done \(|ERROR|Incompatible|Foundry:|Charter state|custom items/.test(x)).slice(-12).join('\n'))
console.log('state', await k.state(), '| rollback jars are in /mods_backup with the suffix', stamp)
