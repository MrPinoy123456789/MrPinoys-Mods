#!/usr/bin/env node
// Pocket Dungeons test server control for agents (and humans). No dependencies.
// See README.md in this folder. Commands:
//
//   start [--window] [--timeout <s>]   start the test server, wait until it is ready
//   stop [--timeout <s>]               clean shutdown over RCON ("stop"), wait for exit
//   status                             up or down, players online, last log lines
//   say <player|@a> <text...>          chat to a player as [Interviewer]
//   cmd <command...>                   run a console command, print the reply
//   chat [--all] [--follow]            new chat, joins, floors, Lemon and errors since the last read
//   context <player>                   the player's context snapshot, one line of JSON
//   lemon say|ask <player> <text...>   speak through Lemon (ask waits for the player's reply)
//   lemon quiet <player>               make Lemon vanish now
//   lemon mode <player> <guide|llm>    llm holds the player's questions for you; refresh it every few minutes
//
// The server runs from source with Gradle (runServer) in pocketdungeons/run, bound
// to 127.0.0.1, RCON enabled. It is the local TEST server, never the live server.

import { spawn } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, readSync, statSync, writeFileSync } from 'node:fs'
import { createConnection } from 'node:net'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { freemem, platform } from 'node:os'

const here = dirname(fileURLToPath(import.meta.url))
const modDir = resolve(here, '..', '..')
const runDir = join(modDir, 'run')
const propsPath = join(runDir, 'server.properties')
const logPath = join(runDir, 'logs', 'latest.log')
const cursorPath = join(runDir, '.agent-chat-cursor.json')
const consoleLog = join(runDir, 'logs', 'agent-console.log')
const isWin = platform() === 'win32'

const [verb, ...rest] = process.argv.slice(2)
const flag = name => { const i = rest.indexOf(name); if (i < 0) return null; rest.splice(i, 1); return true }
const flagValue = (name, dflt) => { const i = rest.indexOf(name); if (i < 0) return dflt; const v = rest[i + 1]; rest.splice(i, 2); return v }
const sleep = ms => new Promise(r => setTimeout(r, ms))

// ---- server.properties and RCON ------------------------------------------------

function props() {
  if (!existsSync(propsPath)) throw new Error(`missing ${propsPath}; has the test server ever been started?`)
  return Object.fromEntries(readFileSync(propsPath, 'utf8').split(/\r?\n/)
    .filter(l => l.includes('=') && !l.startsWith('#'))
    .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]))
}

/**
 * Sends one RCON command; resolves with the reply text, rejects if the server is not reachable.
 * The server splits a long reply into 4096-byte packets, so a second packet of an unknown type is
 * sent straight after the command: the server answers it after the command's last packet.
 */
function rcon(command, timeoutMs = 8000) {
  const conf = props()
  if (conf['enable-rcon'] !== 'true' || !conf['rcon.password']) {
    return Promise.reject(new Error(`RCON is not enabled in ${propsPath} (enable-rcon=true and rcon.password needed)`))
  }
  const packet = (id, type, body) => {
    const payload = Buffer.from(body, 'utf8')
    const buf = Buffer.alloc(14 + payload.length)
    buf.writeInt32LE(10 + payload.length, 0)
    buf.writeInt32LE(id, 4)
    buf.writeInt32LE(type, 8)
    payload.copy(buf, 12)
    return buf
  }
  return new Promise((resolveP, rejectP) => {
    const sock = createConnection({ host: '127.0.0.1', port: Number(conf['rcon.port'] || 25575) })
    let pending = Buffer.alloc(0)
    let authed = false
    let reply = ''
    const fail = e => { sock.destroy(); rejectP(e) }
    sock.setTimeout(timeoutMs, () => fail(new Error('RCON timed out')))
    sock.on('error', fail)
    sock.on('connect', () => sock.write(packet(1, 3, conf['rcon.password'])))
    sock.on('data', chunk => {
      pending = Buffer.concat([pending, chunk])
      while (pending.length >= 4) {
        const len = pending.readInt32LE(0)
        if (pending.length < 4 + len) break
        const id = pending.readInt32LE(4)
        const body = pending.subarray(12, 4 + len - 2).toString('utf8')
        pending = pending.subarray(4 + len)
        if (!authed) {
          if (id === -1) return fail(new Error('RCON authentication failed'))
          authed = true
          sock.write(packet(2, 2, command))
          sock.write(packet(3, 0, ''))
        } else if (id === 2) {
          reply += body
        } else if (id === 3) {
          sock.end()
          resolveP(reply)
        }
      }
    })
  })
}

const isUp = () => rcon('list', 3000).then(() => true, () => false)

// ---- log reading -----------------------------------------------------------------

const EVENT = /\) (\[Not Secure\] )?<([^>]+)> (.*)$|(\S+) (joined|left) the game|completed floor|\/(WARN|ERROR)\] \(PocketDungeons\)|\(PocketDungeons\) (Lemon \w+ <|Report from )|Exception|Stopping server|Done \(/
// Lemon's lines: chat routed to Lemon is not broadcast, so the mod logs it (and Lemon's own lines) itself.
// Anchored to the line's own logger prefix, so chat that quotes one cannot pass for one.
const LEMON = /^\[\d\d:\d\d:\d\d\] \[[^\]]+\/INFO\] \(PocketDungeons\) Lemon (\w+) <([^>]+)>(?: (.*))?$/
const REPORT = /^\[\d\d:\d\d:\d\d\] \[[^\]]+\/INFO\] \(PocketDungeons\) Report from ([^:]+): (.*)$/
const NOISE = /oshi|SystemReport/

/** Turns a raw log line into a compact event line, or null if it is not one agents care about. */
function eventOf(line) {
  // Untimestamped lines are stack-trace continuations; the timestamped line above them is enough.
  const time = (line.match(/^\[(\d\d:\d\d:\d\d)\]/) ?? [])[1]
  if (!time || !EVENT.test(line) || NOISE.test(line)) return null
  let m = line.match(LEMON)
  if (m) return `${time} lemon ${m[1]} <${m[2]}>${m[3] ? ' ' + m[3] : ''}`
  m = line.match(REPORT)
  if (m) return `${time} report <${m[1]}> ${m[2]}`
  m = line.match(/\) (?:\[Not Secure\] )?<([^>]+)> (.*)$/)
  if (m) return `${time} chat <${m[1]}> ${m[2]}`
  m = line.match(/(\S+) (joined|left) the game/)
  if (m) return `${time} ${m[2] === 'joined' ? 'join' : 'leave'} ${m[1]}`
  if (line.includes('completed floor')) return `${time} floor ${line.slice(line.indexOf(') ') + 2)}`
  if (line.includes('Done (')) return `${time} server ready`
  if (line.includes('Stopping server')) return `${time} server stopping`
  return `${time} error ${line.slice(line.indexOf('] ', 12) + 2)}`
}

/** New event lines since the saved cursor; a shorter log than the cursor means the server restarted. */
function readNew(fromStart) {
  if (!existsSync(logPath)) return []
  const size = statSync(logPath).size
  let cursor = { offset: 0 }
  if (!fromStart && existsSync(cursorPath)) {
    try { cursor = JSON.parse(readFileSync(cursorPath, 'utf8')) } catch { cursor = { offset: 0 } }
  }
  if (cursor.offset > size) cursor.offset = 0
  const len = size - cursor.offset
  const buf = Buffer.alloc(Math.max(0, len))
  if (len > 0) { const fd = openSync(logPath, 'r'); readSync(fd, buf, 0, len, cursor.offset); closeSync(fd) }
  // Only consume whole lines, so a half-written line is read next time.
  const text = buf.toString('utf8')
  const lastNl = text.lastIndexOf('\n')
  const whole = lastNl >= 0 ? text.slice(0, lastNl + 1) : ''
  writeFileSync(cursorPath, JSON.stringify({ offset: cursor.offset + Buffer.byteLength(whole, 'utf8') }))
  return whole.split(/\r?\n/).map(eventOf).filter(Boolean)
}

function tail(n) {
  if (!existsSync(logPath)) return []
  return readFileSync(logPath, 'utf8').split(/\r?\n/).filter(Boolean).slice(-n)
}

// ---- commands --------------------------------------------------------------------

async function start() {
  const timeout = Number(flagValue('--timeout', 300)) * 1000
  const windowed = flag('--window')
  if (await isUp()) { console.log('Test server is already running.'); return }
  const freeGb = freemem() / 2 ** 30
  if (freeGb < 3) console.log(`Warning: only ${freeGb.toFixed(1)} GB of RAM free; the build and server need about 3 GB.`)
  mkdirSync(join(runDir, 'logs'), { recursive: true })
  const startedAt = Date.now()
  const env = { ...process.env, JAVA_TOOL_OPTIONS: process.env.JAVA_TOOL_OPTIONS ?? '-Xmx2g' }
  const gradlew = join(modDir, isWin ? 'gradlew.bat' : 'gradlew')
  if (windowed && isWin) {
    // A visible console a human can type "stop" into: the same launcher a person double-clicks.
    spawn('cmd.exe', ['/c', 'start', '""', `"${join(modDir, 'Run Test Server.cmd')}"`],
      { cwd: modDir, env, detached: true, stdio: 'ignore', windowsVerbatimArguments: true }).unref()
  } else {
    const out = openSync(consoleLog, 'w')
    const child = isWin
      ? spawn('cmd.exe', ['/c', `"${gradlew}" runServer --console=plain`], { cwd: modDir, env, detached: true, stdio: ['ignore', out, out], windowsHide: true, windowsVerbatimArguments: true })
      : spawn(gradlew, ['runServer', '--console=plain'], { cwd: modDir, env, detached: true, stdio: ['ignore', out, out] })
    child.unref()
  }
  process.stdout.write('Starting the test server (building from source first)')
  while (Date.now() - startedAt < timeout) {
    await sleep(3000)
    process.stdout.write('.')
    if (await isUp()) {
      readNew(false) // move the chat cursor past the startup noise
      console.log(`\nReady after ${Math.round((Date.now() - startedAt) / 1000)}s. Players connect to localhost (port 25565).`)
      return
    }
  }
  console.log(`\nNot ready after ${timeout / 1000}s. Last console output:`)
  if (existsSync(consoleLog)) console.log(readFileSync(consoleLog, 'utf8').split(/\r?\n/).filter(Boolean).slice(-15).join('\n'))
  process.exitCode = 1
}

async function stop() {
  const timeout = Number(flagValue('--timeout', 120)) * 1000
  if (!(await isUp())) { console.log('Test server is not running.'); return }
  await rcon('save-all flush').catch(() => {})
  await rcon('stop').catch(() => {})
  const startedAt = Date.now()
  while (Date.now() - startedAt < timeout) {
    await sleep(2000)
    if (!(await isUp())) { console.log('Test server stopped cleanly.'); return }
  }
  console.log('Server still answering after the stop timeout; check it by hand.')
  process.exitCode = 1
}

async function status() {
  if (await isUp()) {
    console.log(`UP. ${await rcon('list')}`)
  } else {
    console.log('DOWN.')
  }
  console.log('Last log lines:')
  for (const l of tail(5)) console.log(`  ${l.slice(0, 200)}`)
}

async function say() {
  const [target, ...words] = rest
  if (!target || !words.length) throw new Error('usage: say <player|@a> <text...>')
  const text = words.join(' ')
  await rcon(`tellraw ${target} [{"text":"[Interviewer] ","color":"light_purple"},{"text":${JSON.stringify(text)},"color":"white"}]`)
  console.log(`said to ${target}: ${text}`)
}

async function cmd() {
  if (!rest.length) throw new Error('usage: cmd <command...>')
  const reply = await rcon(rest.join(' '))
  console.log(reply || '(no output)')
}

async function chat() {
  const all = flag('--all')
  const follow = flag('--follow')
  for (const e of readNew(all)) console.log(e)
  if (!follow) return
  for (;;) {
    await sleep(1000)
    for (const e of readNew(false)) console.log(e)
  }
}

async function context() {
  const [player] = rest
  if (!player) throw new Error('usage: context <player>')
  console.log(await rcon(`dungeon admin context ${player}`))
}

async function lemon() {
  const [action, player, ...words] = rest
  const text = words.join(' ')
  let command
  if ((action === 'say' || action === 'ask') && player && text) command = `dungeon lemon ${action} ${player} ${text}`
  else if (action === 'quiet' && player) command = `dungeon lemon quiet ${player}`
  else if (action === 'mode' && player && (text === 'guide' || text === 'llm')) command = `dungeon lemon mode ${player} ${text}`
  else throw new Error('usage: lemon say|ask <player> <text...> | lemon quiet <player> | lemon mode <player> <guide|llm>')
  console.log((await rcon(command)) || '(no output)')
}

const verbs = { start, stop, status, say, cmd, chat, context, lemon }
if (!verbs[verb]) {
  console.log('usage: node pdserver.mjs <start|stop|status|say|cmd|chat|context|lemon> [...]  (see README.md)')
  process.exitCode = 2
} else {
  verbs[verb]().catch(e => { console.error(`error: ${e.message}`); process.exitCode = 1 })
}
