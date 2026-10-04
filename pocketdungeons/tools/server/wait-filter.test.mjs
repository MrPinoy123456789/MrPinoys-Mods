// Checks which log lines wake `server wait` (PD-79). Run: node wait-filter.test.mjs
// The raw lines are copied from pocketdungeons/run/logs (latest.log and the 2026-09 .gz
// files), trimmed. Kinds with no sample in those logs are built from the format
// strings in Lemon.java and marked "format".

import assert from 'node:assert/strict'
import { eventOf, quietVerdict, wakesWait, wholeLines } from './wait-filter.mjs'

const P = 'MrPinoy123456789'
const INFO = '[Server thread/INFO]'

// [raw log line, should it wake wait?]
const cases = [
  // The player: these must wake.
  [`[23:20:21] ${INFO} (PocketDungeons) Lemon ask <${P}> You shouldn't float and follow me around`, true],
  [`[19:44:49] ${INFO} (PocketDungeons) Lemon answer <${P}> There's overlapping words under "Echo shards", same yellow font`, true],
  [`[23:21:06] ${INFO} (PocketDungeons) Lemon unanswered <${P}> You shouldn't float and follow me around`, true],
  [`[12:00:00] ${INFO} (PocketDungeons) Lemon quiet <${P}> on`, true], // format
  [`[12:00:00] ${INFO} (PocketDungeons) Lemon summoned <${P}>`, true], // format
  [`[19:03:56] ${INFO} (Minecraft) <${P}> hey`, true],
  [`[23:19:08] ${INFO} (Minecraft) ${P} joined the game`, true],
  [`[22:43:32] ${INFO} (net.minecraft.server.MinecraftServer) ${P} left the game`, true],
  [`[20:06:03] ${INFO} (PocketDungeons) ${P} completed floor 1 of slot 0 (run #11, chests 2, tier 1)`, true],
  [`[22:46:24] ${INFO} (PocketDungeons) Report from ${P}: blaze_cellar fails to stamp`, true],
  [`[23:17:36] ${INFO} (Minecraft) Done (1.522s)! For help, type "help"`, true],
  // Lemon itself: these must not wake.
  [`[23:19:08] ${INFO} (PocketDungeons) Lemon mode <${P}> llm`, false],
  [`[12:00:00] ${INFO} (PocketDungeons) Lemon mode <${P}> guide (lapsed)`, false], // format
  [`[23:19:12] ${INFO} (PocketDungeons) Lemon says <${P}> Hi, I'm Lemon. Talk to me anytime, just type.`, false],
  [`[19:44:10] ${INFO} (PocketDungeons) Lemon asks <${P}> Thanks! What does it overlap with?`, false],
  [`[23:22:42] ${INFO} (PocketDungeons) Lemon replies <${P}> Logged both.`, false],
  [`[12:00:00] ${INFO} (PocketDungeons) Lemon thinks <${P}> Hmm, let me think.`, false], // format
  [`[19:40:47] ${INFO} (PocketDungeons) Lemon held <${P}> (fight) Hi, I'm Lemon!`, false],
  [`[12:00:00] ${INFO} (PocketDungeons) Lemon hushed <${P}> until they speak to Lemon`, false], // format
  // A production server (the Kinetic test server) writes vanilla's prefix, with no
  // logger name. The Done line is copied from its log; the rest follow the same format.
  [`[05:27:33] ${INFO}: Done (0.993s)! For help, type "help"`, true],
  [`[12:00:00] ${INFO}: Lemon ask <${P}> where is the lever`, true], // format
  [`[12:00:00] ${INFO}: <${P}> hey`, true], // format
  [`[12:00:00] ${INFO}: ${P} joined the game`, true], // format
  [`[12:00:00] ${INFO}: ${P} completed floor 1 of slot 0 (run #11, chests 2, tier 1)`, true], // format
  [`[12:00:00] ${INFO}: Report from ${P}: blaze_cellar fails to stamp`, true], // format
  [`[12:00:00] ${INFO}: Lemon mode <${P}> llm`, false], // format
  [`[12:00:00] ${INFO}: Lemon replies <${P}> Logged both.`, false], // format
]

let failed = 0
for (const [line, expected] of cases) {
  const event = eventOf(line)
  try {
    assert.ok(event, 'not parsed as an event')
    assert.equal(wakesWait(event), expected, `wakes should be ${expected}`)
    console.log(`ok    ${expected ? 'wake ' : 'quiet'}  ${event.slice(0, 70)}`)
  } catch (e) {
    failed++
    console.log(`FAIL  ${e.message}: ${event ?? line}`)
  }
}
// Chat that quotes a Lemon line is still the player's chat, and still wakes, in either format.
assert.equal(wakesWait(eventOf(`[12:00:00] ${INFO} (Minecraft) <${P}> Lemon says <x> hi`)), true)
assert.equal(wakesWait(eventOf(`[12:00:00] ${INFO}: <${P}> Lemon says <x> hi`)), true)
// Vanilla format: a routine vanilla warning is not an event, a dungeon one is; command
// feedback and the remote reply marker are not events either.
assert.equal(eventOf(`[12:00:00] [Server thread/WARN]: Can't keep up! Is the server overloaded?`), null)
assert.equal(eventOf(`[12:00:00] [Server thread/WARN]: Room blaze_cellar failed its return path check`),
  '12:00:00 error Room blaze_cellar failed its return path check')
assert.equal(eventOf(`[12:00:00] ${INFO}: There are 1 of a max of 20 players online: ${P}`), null)
assert.equal(eventOf(`[05:27:53] ${INFO}: pdmark-muryao15crkdq4<--[HERE]`), null)
// A thread name holding "/" still parses (RCON client threads).
assert.equal(eventOf(`[22:09:41] [RCON Client /127.0.0.1 #3212/INFO] (Minecraft) Thread RCON Client /127.0.0.1 shutting down`), null)

// PD-80: a quiet timeout calls the server down only when nothing fresh says it is up.
const quiet = 'no new events (timeout)'
const down = 'server is down (timeout)'
const none = { finalCheck: false, sawLines: false, recentLog: false, worldLocked: false }
assert.equal(quietVerdict(none), down)
assert.equal(quietVerdict({ ...none, finalCheck: true }), quiet)
assert.equal(quietVerdict({ ...none, sawLines: true }), quiet)
assert.equal(quietVerdict({ ...none, recentLog: true }), quiet)
assert.equal(quietVerdict({ ...none, worldLocked: true }), quiet)

// PD-79: a line caught mid-write is left for the next read, not skipped.
const ask = `[00:46:25] ${INFO} (PocketDungeons) Lemon ask <${P}> Mining up torches`
const read1 = wholeLines(Buffer.from(`${ask}\n[00:46:26] ${INFO} (PocketDung`))
assert.equal(read1.text, `${ask}\n`)
assert.equal(read1.bytes, Buffer.byteLength(`${ask}\n`))
assert.equal(wholeLines(Buffer.from('half a line')).bytes, 0)
assert.equal(wholeLines(Buffer.from('caf\u00e9 \u2713\r\n')).bytes, Buffer.byteLength('caf\u00e9 \u2713\r\n'))

console.log(failed ? `${failed} of ${cases.length} failed` : `all ${cases.length + 16} passed`)
process.exitCode = failed ? 1 : 0
