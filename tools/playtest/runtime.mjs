import { spawn } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

export function pocketDungeonsHooks(config = {}) {
  return {
    ...config,
    onInteract: config.onInteract || 'dungeon lemonmenu',
    onInteractHolding: { 'minecraft:written_book': 'dungeon lemonarchive', ...config.onInteractHolding },
  }
}

export async function installLocalLemon(workspace, runDir) {
  const project = join(workspace, 'lemon')
  const win = process.platform === 'win32'
  await new Promise((resolve, reject) => {
    const child = win
      ? spawn('cmd.exe', ['/c', 'gradlew.bat jar --console=plain'], { cwd: project, windowsHide: true, stdio: 'inherit' })
      : spawn('./gradlew', ['jar', '--console=plain'], { cwd: project, stdio: 'inherit' })
    child.on('error', reject)
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(`Lemon build failed (${code})`)))
  })
  const jars = readdirSync(join(project, 'build', 'libs')).filter(name => name.endsWith('.jar') && !name.endsWith('-sources.jar'))
  if (jars.length !== 1) throw new Error('Expected exactly one built Lemon jar')
  const mods = join(runDir, 'mods')
  mkdirSync(mods, { recursive: true })
  const stale = readdirSync(mods).filter(name => /^MrPinoys_Lemon-.*\.jar$/.test(name) && name !== jars[0])
  if (stale.length) throw new Error(`Remove obsolete Lemon runtime jars before starting: ${stale.join(', ')}`)
  copyFileSync(join(project, 'build', 'libs', jars[0]), join(mods, jars[0]))
  const configDir = join(runDir, 'config', 'lemon')
  const configPath = join(configDir, 'lemon.json')
  const config = existsSync(configPath) ? JSON.parse(readFileSync(configPath, 'utf8')) : {}
  mkdirSync(configDir, { recursive: true })
  writeFileSync(configPath, JSON.stringify(pocketDungeonsHooks(config), null, 2) + '\n')
}
