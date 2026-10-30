// Deploys Charter, Foundry and the Foundry content to the shipped kingdom server (Kinetic d212ecae).
//
//   node tools/playtest/shipped-deploy.mjs [--foundry] [--charter] [--content] [--bedrock] [--allow name,name] [--dry]
//
// Only run it when the owner has asked for a deploy. It refuses to continue if the server's mods are not the same
// Minecraft version as the jars, if anyone outside --allow is online, or if the server is not running. It backs up
// every jar it replaces into /mods_backup, stops the server with a save, uploads, starts, and prints what the log says.
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

// Where each piece is built. The worktree layout changes, so the jar folders can be overridden.
const FOUNDRY = process.env.FOUNDRY_DIR || resolve(root, 'foundry')
const CHARTER = process.env.CHARTER_DIR || resolve(root, 'charter')
const FOUNDRY_JAR = 'MrPinoys_Foundry-0.1.0.jar'
const CHARTER_JAR = 'MrPinoys_Charter-0.1.0.jar'
const jarOf = (dir, name) => resolve(dir, dir === CHARTER ? 'fabric' : '.', 'build', 'libs', name)
const files = (dir, re) => existsSync(dir) ? readdirSync(dir).filter(n => re.test(n)).map(n => ({ name: n, bytes: readFileSync(resolve(dir, n)) })) : []

// 1. Is it safe to go ahead?
const mods = await ls('/mods')
const target = (mods.find(n => /^fabric-api-.*\+(\d+\.\d+(\.\d+)?)\.jar$/.test(n)) || '').match(/\+(\d+\.\d+(\.\d+)?)\.jar$/)?.[1]
const props = readFileSync(resolve(CHARTER, 'gradle.properties'), 'utf8')
const built = props.match(/^minecraft_version=(.+)$/m)?.[1]?.trim()
console.log(`server mods are for Minecraft ${target}; Charter builds for ${built}`)
if (!target || target !== built) { console.log('version mismatch; aborting. Build for the server version first.'); process.exit(1) }
if ((await k.state()) !== 'running') { console.log('server is not running; aborting'); process.exit(1) }
await k.command('list'); await sleep(3000)
const line = (await log()).filter(x => /There are \d+ of a max/.test(x)).pop() || ''
console.log('list says:', line.replace(/^\[[^\]]*\]\s*/, ''))
const others = (line.split('online:')[1] || '?').split(',').map(s => s.trim()).filter(s => s && !allowed.has(s))
if (others.length) { console.log('other players online; ask the owner first:', others.join(', ')); process.exit(1) }

// 2. What goes up.
const jars = []
if (has('--foundry')) jars.push({ name: FOUNDRY_JAR, bytes: readFileSync(jarOf(FOUNDRY, FOUNDRY_JAR)) })
if (has('--charter')) jars.push({ name: CHARTER_JAR, bytes: readFileSync(jarOf(CHARTER, CHARTER_JAR)) })
const ex = resolve(FOUNDRY, 'examples')
const content = has('--content') ? [
  ['/config/foundry/content/kingdoms/items', files(resolve(ex, 'content/kingdoms/items'), /\.json$/)],
  ['/config/foundry/content/kingdoms/recipes', files(resolve(ex, 'content/kingdoms/recipes'), /\.json$/)],
  ['/config/foundry/content/demo/items', files(resolve(ex, 'content/demo/items'), /^fairy_wand.*\.json$/)],
  ['/config/foundry/content/demo/recipes', files(resolve(ex, 'content/demo/recipes'), /^fairy_wand.*\.json$/)],
  ['/config/foundry/assets/kingdoms/textures/item', files(resolve(ex, 'assets/kingdoms/textures/item'), /\.png$/)],
  ['/config/foundry/assets/demo/textures/item', files(resolve(ex, 'assets/demo/textures/item'), /^fairy_wand.*\.png$/)],
] : []
const bedrock = has('--bedrock') ? [
  ['/config/Geyser-Fabric/custom_mappings', files(resolve(ex, 'bedrock/custom_mappings'), /\.json$/)],
  ['/config/Geyser-Fabric/packs', files(resolve(ex, 'bedrock'), /\.mcpack$/)],
] : []
console.log('jars:', jars.map(j => `${j.name} ${j.bytes.length}`).join(', ') || 'none',
  '| content folders:', content.length, '| bedrock folders:', bedrock.length)
if (has('--dry')) { console.log('dry run; nothing changed'); process.exit(0) }
if (!jars.length && !content.length && !bedrock.length) { console.log('nothing to deploy; pass --foundry, --charter, --content or --bedrock'); process.exit(1) }

// 3. Back up, stop, upload, start.
const backups = []
for (const j of jars) if (mods.includes(j.name)) backups.push({ name: j.name.replace('.jar', `.${stamp}.jar`), bytes: await k.read('/mods/' + j.name) })
if (backups.length) await k.upload('/mods_backup', backups)
console.log('backed up', backups.map(b => b.name).join(', ') || 'nothing')
await k.command('say Restarting in 10 seconds for an update.')
await sleep(10000)
await k.command('save-all flush'); await sleep(3000)
await k.power('stop')
for (let i = 0; i < 40 && (await k.state()) !== 'offline'; i++) await sleep(3000)
if ((await k.state()) !== 'offline') { console.log('did not stop; aborting'); process.exit(1) }
if (jars.length) await k.upload('/mods', jars)
for (const [dir, list] of [...content, ...bedrock]) if (list.length) await k.upload(dir, list)
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
