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
//   sync                               skip everything logged so far (start a session fresh)
//   wait [--player <p>] [--timeout <s>] block until new events arrive, print them; keeps <p>'s Lemon in llm mode
//   context <player>                   the player's context snapshot, one line of JSON
//   lemon say|ask <player> <text...>     speak through Lemon (ask waits for the player's reply)
//   lemon reply <player> <text...>       answer a pending question, never held for combat or quiet
//   lemon think <player> [text...]     "let me check" line; Lemon hides until the reply
//   lemon quiet <player>                 make Lemon vanish and hold unprompted lines until the player speaks
//   lemon mode <player> <guide|llm>      llm holds the player's questions for you; refresh it every few minutes
//   deploy [--restart]                 remote only: upload the built jar from dist/ to the server's mods folder
//
// Local (the default): the server runs from source with Gradle (runServer) in
// pocketdungeons/run, bound to 127.0.0.1, RCON enabled.
//
// Remote: when KINETIC_SERVER_ID and KINETIC_API_KEY are set (environment or kinetic.env,
// see kinetic.mjs), every verb drives that Kinetic panel server instead. Commands go
// through the panel's console endpoint, and everything read back (events and command
// replies) comes from the server's own logs/latest.log, so both modes parse the same lines.
//
// Either way it is a TEST server, never the live server.

import { spawn } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, readSync, statSync, writeFileSync } from 'node:fs'
import { createConnection } from 'node:net'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { freemem, platform } from 'node:os'
import { dedupeHeard, eventOf, quietVerdict, wakesWait, wholeLines } from './wait-filter.mjs'
import { Kinetic, remoteConfig } from './kinetic.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const modDir = resolve(here, '..', '..')
const runDir = join(modDir, 'run')
const propsPath = join(runDir, 'server.properties')
const logPath = join(runDir, 'logs', 'latest.log')
const cursorPath = join(runDir, '.agent-chat-cursor.json')
const consoleLog = join(runDir, 'logs', 'agent-console.log')
const isWin = platform() === 'win32'

const remote = remoteConfig()
const panel = remote ? new Kinetic(remote) : null
const REMOTE_LOG = '/logs/latest.log'
// The remote read cursor lives beside this script (gitignored), one per panel server.
const remoteStateDir = join(here, '.remote')
const readCursorPath = remote ? join(remoteStateDir, `cursor-${remote.serverId}.json`) : cursorPath

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
    let settled = false
    const done = text => { if (!settled) { settled = true; resolveP(text) } }
    const fail = e => { sock.destroy(); if (!settled) { settled = true; rejectP(e) } }
    sock.setTimeout(timeoutMs, () => fail(new Error('RCON timed out')))
    sock.on('error', fail)
    // A command like "stop" closes the connection before the end marker comes back:
    // once the command was sent, a close means it was received, so settle with what arrived.
    sock.on('close', () => authed ? done(reply) : fail(new Error('RCON connection closed before login')))
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
          done(reply)
        }
      }
    })
  })
}

/**
 * Up: the reply text (the player list locally, empty remotely), or null when down.
 * Remote asks the panel's power state rather than sending a command, so a check
 * every few seconds adds nothing to the server log.
 */
const isUp = () => panel
  ? panel.state().then(s => (s === 'running' ? '' : null), () => null)
  : rcon('list', 3000).then(text => text, () => null)

/**
 * Says which panel server a state-changing verb is about to act on, and where that id
 * came from, before it acts: kinetic.env is read on every call, so a file left in place
 * redirects every command, and a stop or start must never land on a server by surprise.
 */
const announceTarget = action => console.log(`Remote: ${action} panel server ${remote.serverId} (id from ${remote.source}).`)

/** Runs one console command and resolves with its reply, on whichever server is configured. */
const run = (command, timeoutMs) => (panel ? remoteCommand(command, timeoutMs) : rcon(command, timeoutMs))

// A logged line's prefix: time, thread and level, then Fabric's "(logger)" (vanilla has ": " instead).
const LOG_PREFIX = /^\[\d\d:\d\d:\d\d\] \[[^\]]+\](?: \(([^)]+)\))?:? /

/**
 * Remote stand-in for RCON. The panel's console endpoint returns no output, but a command
 * run from the console logs its feedback, so the reply is read back out of latest.log:
 * the command is followed by an unknown marker command, whose "<--[HERE]" error line
 * closes the reply. Only Minecraft's own lines in between count (command feedback), so a
 * mod event or a chat line logged at the same moment is not mistaken for part of it.
 */
async function remoteCommand(command, timeoutMs = 15000) {
  const before = (await panel.stat(REMOTE_LOG))?.size ?? 0
  const mark = `pdmark-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
  await panel.command(command)
  await panel.command(mark)
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    await sleep(700)
    const buf = await panel.read(REMOTE_LOG)
    const lines = buf.subarray(before <= buf.length ? before : 0).toString('utf8').split(/\r?\n/)
    const end = lines.findIndex(l => l.includes(`${mark}<--[HERE]`))
    if (end < 0) continue
    const reply = []
    let keep = false
    for (const line of lines.slice(0, end)) {
      const m = line.match(LOG_PREFIX)
      if (!m) { if (keep) reply.push(line); continue }
      const body = line.slice(m[0].length)
      // A production log has no logger names, so a mod line cannot be told from feedback
      // by its prefix; anything that parses as an event (a Lemon line, chat, a join, an
      // error) is still left out. Other mod lines logged in the same instant can remain.
      keep = (!m[1] || m[1] === 'Minecraft') && eventOf(line) === null
        && !body.startsWith('Unknown or incomplete command')
      if (keep) reply.push(body)
    }
    return reply.join('\n')
  }
  throw new Error(`no reply to "${command}" in the server log within ${timeoutMs / 1000}s`)
}

/**
 * Whether a server process still holds the world's session.lock. It stops answering
 * RCON before it finishes saving, so this, not RCON, says when it has really exited.
 */
function worldLocked() {
  if (panel) return false // the panel's power state says when it has exited
  let level = 'world'
  try { level = props()['level-name'] || 'world' } catch { /* defaults */ }
  const lock = join(runDir, level, 'session.lock')
  if (!existsSync(lock)) return false
  try { readFileSync(lock); return false } catch (e) { return e.code === 'EBUSY' || e.code === 'EPERM' || e.code === 'EACCES' }
}

/** The server log's lines written since `sinceMs`, or none if it has not been touched since. Local only. */
function logSince(sinceMs) {
  if (!existsSync(logPath) || statSync(logPath).mtimeMs < sinceMs) return []
  return readFileSync(logPath, 'utf8').split(/\r?\n/)
}

// ---- log reading -----------------------------------------------------------------

/** The server log's size and modification time, or null if there is none. */
async function logInfo() {
  if (panel) return panel.stat(REMOTE_LOG)
  if (!existsSync(logPath)) return null
  const st = statSync(logPath)
  return { size: st.size, mtimeMs: st.mtimeMs }
}

/** The server log's size in bytes, or -1 if there is none. */
async function logSize() {
  return (await logInfo())?.size ?? -1
}

/** The log's bytes from `offset` to its end (remote downloads the whole file). */
async function logBytes(offset) {
  if (panel) return (await panel.read(REMOTE_LOG)).subarray(offset)
  const size = statSync(logPath).size
  const buf = Buffer.alloc(Math.max(0, size - offset))
  if (buf.length > 0) { const fd = openSync(logPath, 'r'); readSync(fd, buf, 0, buf.length, offset); closeSync(fd) }
  return buf
}

/** New event lines since the saved cursor; a shorter log than the cursor means the server restarted. */
async function readNew(fromStart) {
  const info = await logInfo()
  if (!info) return []
  let cursor = { offset: 0 }
  if (!fromStart && existsSync(readCursorPath)) {
    try { cursor = JSON.parse(readFileSync(readCursorPath, 'utf8')) } catch { cursor = { offset: 0 } }
  }
  if (cursor.offset > info.size) cursor.offset = 0
  if (cursor.offset === info.size) return []
  const buf = await logBytes(cursor.offset)
  // Only consume whole lines, so a half-written line is read next time. PD-79: the
  // cursor used to jump to the file size, which skipped such a line for good.
  const { text, bytes } = wholeLines(buf)
  mkdirSync(dirname(readCursorPath), { recursive: true })
  writeFileSync(readCursorPath, JSON.stringify({ offset: cursor.offset + bytes }))
  return text.split(/\r?\n/).map(eventOf).filter(Boolean)
}

async function tail(n) {
  if (!(await logInfo())) return []
  return (await logBytes(0)).toString('utf8').split(/\r?\n/).filter(Boolean).slice(-n)
}

// ---- commands --------------------------------------------------------------------

async function startLocal() {
  const timeout = Number(flagValue('--timeout', 300)) * 1000
  const windowed = flag('--window')
  if ((await isUp()) !== null) { console.log('Test server is already running.'); return }
  // A server that is still shutting down holds the world lock; a new one would fail to start.
  for (let i = 0; i < 30 && worldLocked(); i++) {
    if (i === 0) console.log('Waiting for the previous server to release the world...')
    await sleep(2000)
  }
  if (worldLocked()) {
    console.log('Another server process still holds the world (session.lock). Stop it first.')
    process.exitCode = 1
    return
  }
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
    if ((await isUp()) !== null) {
      await readNew(false) // move the chat cursor past the startup noise
      console.log(`\nReady after ${Math.round((Date.now() - startedAt) / 1000)}s. Players connect to localhost (port 25565).`)
      return
    }
    // In window mode the build output is only in that window; the server log still shows a failed start.
    const failed = logSince(startedAt).findIndex(l => l.includes('Failed to start the minecraft server'))
    if (failed >= 0) {
      console.log('\nThe server failed to start:')
      console.log(logSince(startedAt).slice(failed, failed + 4).join('\n'))
      process.exitCode = 1
      return
    }
  }
  console.log(`\nNot ready after ${timeout / 1000}s.`)
  if (!windowed && existsSync(consoleLog)) {
    console.log('Last console output:')
    console.log(readFileSync(consoleLog, 'utf8').split(/\r?\n/).filter(Boolean).slice(-15).join('\n'))
  } else {
    console.log('It was started in a window: the build or startup error is shown there.')
  }
  process.exitCode = 1
}

/** Remote start: the panel's power signal, then its state until the server is running. */
async function startRemote() {
  const timeout = Number(flagValue('--timeout', 300)) * 1000
  flag('--window') // no window to open on a panel server
  if ((await isUp()) !== null) { console.log(`Test server ${remote.serverId} is already running.`); return }
  announceTarget('starting')
  await panel.power('start')
  const startedAt = Date.now()
  let seenStarting = false
  process.stdout.write(`Starting test server ${remote.serverId} on the panel`)
  while (Date.now() - startedAt < timeout) {
    await sleep(3000)
    process.stdout.write('.')
    const state = await panel.state().catch(() => null)
    if (state === 'starting') seenStarting = true
    if (state === 'running') {
      await readNew(false) // move the chat cursor past the startup noise
      console.log(`\nReady after ${Math.round((Date.now() - startedAt) / 1000)}s. Players connect to the address shown on the panel.`)
      return
    }
    if (state === 'offline' && seenStarting) {
      console.log('\nThe server stopped while starting. Last log lines:')
      for (const l of await tail(15)) console.log(`  ${l.slice(0, 200)}`)
      process.exitCode = 1
      return
    }
  }
  console.log(`\nNot ready after ${timeout / 1000}s. Last log lines:`)
  for (const l of await tail(15)) console.log(`  ${l.slice(0, 200)}`)
  process.exitCode = 1
}

const start = () => (panel ? startRemote() : startLocal())

async function stop() {
  const timeout = Number(flagValue('--timeout', 120)) * 1000
  if ((await isUp()) === null) { console.log('Test server is not running.'); return }
  if (panel) {
    announceTarget('stopping')
    await panel.command('save-all flush').catch(() => {})
    await panel.power('stop')
  } else {
    await rcon('save-all flush').catch(() => {})
    await rcon('stop').catch(() => {})
  }
  const startedAt = Date.now()
  while (Date.now() - startedAt < timeout) {
    await sleep(2000)
    // Locally RCON goes quiet before the final save finishes; the world lock is
    // released only on exit. Remotely the panel's state says offline only on exit.
    const down = panel
      ? (await panel.state().catch(() => null)) === 'offline'
      : (await isUp()) === null && !worldLocked()
    if (down) { console.log('Test server stopped cleanly.'); return }
  }
  console.log('Server still running after the stop timeout; check it by hand.')
  process.exitCode = 1
}

async function status() {
  let reply
  if (panel) {
    // Asked directly, not through isUp: a key or server id problem is reported as
    // such instead of passing for a stopped server.
    const state = await panel.state()
    if (state !== 'running' && state !== 'offline') console.log(`Panel state: ${state}.`)
    reply = state === 'running' ? await run('list', 10000).catch(() => '') : null
  } else {
    reply = await isUp()
  }
  if (reply !== null) {
    const trimmed = (reply || '').trim()
    if (trimmed) {
      console.log(`UP. ${trimmed}`)
    } else {
      console.log(`UP. (${panel ? 'player list' : 'RCON player list'} is empty)`)
    }
  } else {
    console.log('DOWN.')
  }
  if (panel) console.log(`Remote: panel server ${remote.serverId}.`)
  console.log('Last log lines:')
  try {
    for (const l of await tail(5)) console.log(`  ${l.slice(0, 200)}`)
  } catch (e) {
    // A server that was never started has no log yet; the panel can answer that with a 500.
    console.log(`  (could not read the log: ${e.message.slice(0, 120)})`)
  }
}

async function say() {
  const [target, ...words] = rest
  if (!target || !words.length) throw new Error('usage: say <player|@a> <text...>')
  const text = words.join(' ')
  await run(`tellraw ${target} [{"text":"[Interviewer] ","color":"light_purple"},{"text":${JSON.stringify(text)},"color":"white"}]`)
  console.log(`said to ${target}: ${text}`)
}

async function cmd() {
  if (!rest.length) throw new Error('usage: cmd <command...>')
  const reply = await run(rest.join(' '))
  console.log(reply || '(no output)')
}

async function chat() {
  const all = flag('--all')
  const follow = flag('--follow')
  for (const e of await readNew(all)) console.log(e)
  if (!follow) return
  for (;;) {
    await sleep(panel ? 2000 : 1000)
    for (const e of await readNew(false)) console.log(e)
  }
}

/** Skips every event logged so far, so the next `wait` or `chat` starts from now. */
async function sync() {
  const skipped = (await readNew(false)).length
  console.log(`Skipped ${skipped} earlier event(s); reading from now on.`)
}

/**
 * Blocks until there are new events (or the timeout passes) and prints them: the
 * one-command loop for agents that cannot stream output. With --player it also keeps
 * that player's Lemon in llm mode, refreshing it every minute while the server is up.
 *
 * PD-80: one slow or failed `list` (3 s RCON timeout) used to flip `up` and print
 * "server is down" at the timeout while the server was fine. Two failed checks in a row
 * still stop the Lemon refresh, but the timeout verdict ignores them: it is made fresh
 * at the reporting point (quietVerdict in wait-filter.mjs), from a longer RCON check
 * tried twice, the log growing during the wait, recent log output and the world lock.
 *
 * Remote polls every 2 s instead of every second: each poll is a panel request, and
 * the log is only downloaded when its size has changed.
 */
async function wait() {
  const timeout = Number(flagValue('--timeout', 240)) * 1000
  const player = flagValue('--player', null)
  const startedAt = Date.now()
  const startSize = await logSize()
  let up = null
  let failures = 0
  let lastCheck = 0
  let lastRefresh = 0
  // Lemon's mode refreshes need no reply, and remotely a reply costs a marker
  // command and log downloads, so they are sent without one.
  const send = command => (panel ? panel.command(command) : rcon(command)).catch(() => {})
  for (;;) {
    if (Date.now() - lastCheck >= 15000) {
      if ((await isUp()) !== null) {
        up = true
        failures = 0
      } else {
        failures++
        if (up === null || failures >= 2) up = false
      }
      lastCheck = Date.now()
    }
    if (player && up && Date.now() - lastRefresh >= 60000) {
      await send(`dungeon lemon mode ${player} llm`)
      lastRefresh = Date.now()
    }
    const events = await readNew(false).catch(() => [])
    // Lemon's own lines (the agent's echoes, mode refreshes) never wake wait; see wait-filter.mjs.
    const external = dedupeHeard(events).filter(wakesWait)
    const joined = player && events.some(e => new RegExp(`^\\S+ join ${player}$`).test(e))
    if (joined && up) {
      await send(`dungeon lemon mode ${player} llm`)
      lastRefresh = Date.now()
    }
    if (external.length) {
      for (const e of external) console.log(e)
      return
    }
    if (Date.now() - startedAt >= timeout) {
      const answers = () => (panel
        ? panel.state().then(s => s === 'running', () => false)
        : rcon('list', 8000).then(() => true, () => false))
      const finalCheck = (await answers()) || (await answers())
      const info = await logInfo().catch(() => null)
      const size = info?.size ?? -1
      const recentLog = info !== null && Date.now() - info.mtimeMs < 20000
      console.log(quietVerdict({ finalCheck, sawLines: size !== startSize, recentLog, worldLocked: worldLocked() }))
      return
    }
    await sleep(panel ? 2000 : 1000)
  }
}

async function context() {
  const [player] = rest
  if (!player) throw new Error('usage: context <player>')
  const reply = await run(`dungeon admin context ${player}`)
  // The snapshot is one line of JSON; remotely an unrelated line can share the reply.
  console.log(reply.split('\n').find(l => l.trimStart().startsWith('{')) ?? reply)
}

async function lemon() {
  const [action, player, ...words] = rest
  const text = words.join(' ')
  let command
  if ((action === 'say' || action === 'ask' || action === 'reply') && player && text) command = `dungeon lemon ${action} ${player} ${text}`
  else if (action === 'think' && player) command = `dungeon lemon think ${player}${text ? ' ' + text : ''}`
  else if (action === 'quiet' && player) command = `dungeon lemon quiet ${player}`
  else if (action === 'mode' && player && (text === 'guide' || text === 'llm')) command = `dungeon lemon mode ${player} ${text}`
  else throw new Error('usage: lemon say|ask|reply <player> <text...> | lemon think <player> [text...] | lemon quiet <player> | lemon mode <player> <guide|llm>')
  console.log((await run(command)) || '(no output)')
}

/**
 * Remote only: uploads the Pocket Dungeons jar that `gradlew build` (or `dist`) put in
 * the workspace's dist/ folder into the server's mods folder, replacing the old one.
 * A running server keeps the old jar until it restarts; --restart does that.
 */
async function deploy() {
  const restart = flag('--restart')
  if (!panel) throw new Error('deploy is for a remote server; the local server builds from source on start')
  const distDir = resolve(modDir, '..', 'dist')
  const jars = existsSync(distDir)
    ? readdirSync(distDir).filter(f => /^MrPinoys_Pocket_Dungeons-.*\.jar$/.test(f) && !f.endsWith('-sources.jar'))
    : []
  if (jars.length !== 1) throw new Error(`expected one Pocket Dungeons jar in ${distDir}, found ${jars.length}; run gradlew dist`)
  const bytes = readFileSync(join(distDir, jars[0]))
  announceTarget(`uploading ${jars[0]} to`)
  await panel.upload('/mods', [{ name: jars[0], bytes }])
  console.log(`Uploaded ${jars[0]} (${Math.round(bytes.length / 1024)} KB) to /mods on ${remote.serverId}.`)
  if (!restart) {
    console.log('The server loads it on its next start (deploy --restart, or stop then start).')
    return
  }
  if ((await isUp()) !== null) {
    await stop()
    if (process.exitCode) return
  }
  await startRemote()
}

const verbs = { start, stop, status, say, cmd, chat, sync, wait, context, lemon, deploy }
if (!verbs[verb]) {
  console.log('usage: node pdserver.mjs <start|stop|status|say|cmd|chat|sync|wait|context|lemon|deploy> [...]  (see README.md)')
  process.exitCode = 2
} else {
  verbs[verb]().catch(e => { console.error(`error: ${e.message}`); process.exitCode = 1 })
}
