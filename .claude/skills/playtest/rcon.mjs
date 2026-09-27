#!/usr/bin/env node
// Minimal Minecraft RCON client for the /playtest live interview. No
// dependencies. Reads the port and password from the test server's
// server.properties (default pocketdungeons/run/server.properties).
//
// Usage:
//   node rcon.mjs "<command>"            run a console command, print the reply
//   node rcon.mjs --say "<text>"         chat to everyone as [Interviewer]
//   node rcon.mjs --to <player> "<text>" chat to one player as [Interviewer]
//   options: --props <server.properties path>, --host <host>

import { readFileSync } from 'node:fs'
import { createConnection } from 'node:net'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
let props = resolve(here, '..', '..', '..', 'pocketdungeons', 'run', 'server.properties')
let host = '127.0.0.1'
let mode = 'cmd'
let target = '@a'
const rest = []
for (let i = 0; i < args.length; i++) {
  const a = args[i]
  if (a === '--props') props = resolve(args[++i])
  else if (a === '--host') host = args[++i]
  else if (a === '--say') mode = 'say'
  else if (a === '--to') { mode = 'say'; target = args[++i] }
  else rest.push(a)
}
const text = rest.join(' ')
if (!text) {
  console.error('Nothing to send. Usage: node rcon.mjs "<command>" | --say "<text>" | --to <player> "<text>"')
  process.exit(2)
}

const conf = Object.fromEntries(readFileSync(props, 'utf8').split(/\r?\n/)
  .filter(l => l.includes('=') && !l.startsWith('#'))
  .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]))
if (conf['enable-rcon'] !== 'true' || !conf['rcon.password']) {
  console.error(`RCON is not enabled in ${props} (need enable-rcon=true and rcon.password).`)
  process.exit(2)
}
const port = Number(conf['rcon.port'] || 25575)

/** tellraw with an [Interviewer] tag; JSON escaping keeps quotes and backslashes safe. */
const command = mode === 'say'
  ? `tellraw ${target} [{"text":"[Interviewer] ","color":"light_purple"},{"text":${JSON.stringify(text)},"color":"white"}]`
  : text

function packet(id, type, body) {
  const payload = Buffer.from(body, 'utf8')
  const buf = Buffer.alloc(14 + payload.length)
  buf.writeInt32LE(10 + payload.length, 0)
  buf.writeInt32LE(id, 4)
  buf.writeInt32LE(type, 8)
  payload.copy(buf, 12)
  return buf // last two bytes stay 0: body terminator and empty string
}

const sock = createConnection({ host, port })
sock.setTimeout(8000, () => { console.error('RCON timed out.'); sock.destroy(); process.exit(1) })
let pending = Buffer.alloc(0)
let authed = false
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
      if (id === -1) { console.error('RCON authentication failed.'); sock.destroy(); process.exit(1) }
      authed = true
      sock.write(packet(2, 2, command))
    } else {
      if (body) console.log(body)
      sock.end()
    }
  }
})
sock.on('error', e => { console.error(`RCON connection failed: ${e.message}. Is the test server running with RCON enabled?`); process.exit(1) })
