// One-off: download the current Pocket Dungeons jar from the test server's /mods
// into a dated local backup before a deploy. Prints the server id it acts on.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Kinetic, remoteConfig } from './kinetic.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const remote = remoteConfig()
if (!remote) {
  console.error('no remote config: kinetic.env is missing or PD_TARGET=local')
  process.exit(1)
}
console.log(`panel server ${remote.serverId}`)
if (remote.serverId !== '714a0605') {
  console.error(`refusing: id is ${remote.serverId}, expected 714a0605`)
  process.exit(1)
}
const panel = new Kinetic(remote)
const dir = '/mods'
const data = await panel.request('GET', '/files/list', { query: { directory: dir } })
const jars = (data.data || []).map(d => d.attributes).filter(a => /^MrPinoys_Pocket_Dungeons-.*\.jar$/.test(a.name))
console.log(`jars in /mods: ${jars.map(j => `${j.name} (${j.size} bytes, ${j.modified_at})`).join(', ') || 'none'}`)
const outDir = join(here, '.remote', 'backups')
mkdirSync(outDir, { recursive: true })
const stamp = new Date().toISOString().slice(0, 10)
for (const jar of jars) {
  const bytes = await panel.read(`${dir}/${jar.name}`)
  const out = join(outDir, `${stamp}-${jar.name}`)
  writeFileSync(out, bytes)
  console.log(`saved ${out} (${bytes.length} bytes)`)
}
