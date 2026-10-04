// Log line parsing and the `wait` wake filter, kept pure (no server, no files) so
// wait-filter.test.mjs can check them against real log lines.

// A log line's prefix: time, thread and level, then the logger. The local dev server
// (Loom) names the logger, "(PocketDungeons)"; a production server such as the Kinetic
// one writes vanilla's ": " instead, so there a mod line and a Minecraft line look
// alike and the body alone decides. Thread names can hold "/" ("RCON Client /127.0.0.1").
const PREFIX = /^\[(\d\d:\d\d:\d\d)\] \[(.*)\/(INFO|WARN|ERROR|DEBUG|TRACE|FATAL)\](?: \(([^)]+)\))?:? /
// Lemon's lines: chat routed to Lemon is not broadcast, so the mod logs it (and Lemon's own lines) itself.
// Matched against the body after the prefix, so chat that quotes one ("<name> Lemon says ...") cannot pass for one.
const LEMON = /^Lemon (\w+) <([^>]+)>(?: (.*))?$/
const REPORT = /^Report from ([^:]+): (.*)$/
const CHAT = /^(?:\[Not Secure\] )?<([^>]+)> (.*)$/
const NOISE = /oshi|SystemReport/
// Vanilla's routine warnings. A dev-format line names its logger and only the mod's
// WARN and ERROR lines count; a vanilla-format line cannot say whose it is, so these
// are the ones left out instead.
const VANILLA_WARN_NOISE = /Can't keep up!|moved too quickly|moved wrongly|Ambiguity between arguments/

/** Turns a raw log line into a compact event line, or null if it is not one agents care about. */
export function eventOf(line) {
  // Untimestamped lines are stack-trace continuations; the timestamped line above them is enough.
  const p = line.match(PREFIX)
  if (!p || NOISE.test(line)) return null
  const [prefix, time, , level, logger] = p
  const body = line.slice(prefix.length)
  const modLine = !logger || logger === 'PocketDungeons'
  let m
  if (modLine && level === 'INFO' && (m = body.match(LEMON))) return `${time} lemon ${m[1]} <${m[2]}>${m[3] ? ' ' + m[3] : ''}`
  if (modLine && level === 'INFO' && (m = body.match(REPORT))) return `${time} report <${m[1]}> ${m[2]}`
  if ((m = body.match(CHAT))) return `${time} chat <${m[1]}> ${m[2]}`
  if ((m = line.match(/(\S+) (joined|left) the game/))) return `${time} ${m[2] === 'joined' ? 'join' : 'leave'} ${m[1]}`
  if (line.includes('completed floor')) return `${time} floor ${body}`
  if (line.includes('Done (')) return `${time} server ready`
  if (line.includes('Stopping server')) return `${time} server stopping`
  const problem = (level === 'WARN' || level === 'ERROR')
    && (logger ? logger === 'PocketDungeons' : !VANILLA_WARN_NOISE.test(body))
  if (problem || line.includes('Exception')) return `${time} error ${logger ? `(${logger}) ` : ''}${body}`
  return null
}

// Lines Lemon itself emits (Lemon.java): the agent's own says/asks/replies/thinks
// echoes, lines held for a fight or quiet, hushes, and mode changes (including the llm
// refreshes `wait` sends every minute). None of these may wake `wait`. The player's own
// Lemon lines are `lemon ask` (spoke to Lemon), `lemon answer` (answered Lemon's
// question) and `lemon quiet` (typed /lemon quiet or /lemon on). `lemon unanswered`
// carries a player question that timed out unanswered, so it wakes too.
const LEMON_OWN = /^\S+ lemon (mode|says|asks|replies|thinks|held|hushed) /

/** True when a compact event line should wake `wait`: anything except Lemon's own lines. */
export function wakesWait(event) {
  return !LEMON_OWN.test(event)
}

/**
 * PD-79: splits freshly read log bytes into the whole lines to parse now and how many
 * bytes they took. A line still being written (no newline yet) is left for the next read:
 * the cursor must advance by `bytes`, never to the file size, or that line is skipped for
 * good and an event in it never wakes `wait`.
 */
export function wholeLines(buf) {
  const lastNl = buf.lastIndexOf(0x0a)
  const bytes = lastNl + 1
  return { text: buf.subarray(0, bytes).toString('utf8'), bytes }
}

/**
 * PD-80: what a quiet `wait` prints when its timeout passes. The checks made during the
 * wait only decide whether to refresh Lemon; they do not count here, because a stale
 * failure among them is what printed "server is down" over a running server. Any one
 * piece of fresh proof is enough to call the server up:
 *   finalCheck   the RCON check made at the reporting point answered
 *   sawLines     the log grew during the wait (events, Lemon's own lines, RCON accepts)
 *   recentLog    the log was written in the last 20 s (a crashed server goes quiet)
 *   worldLocked  a server process still holds the world's session.lock
 */
export function quietVerdict({ finalCheck, sawLines, recentLog, worldLocked }) {
  return finalCheck || sawLines || recentLog || worldLocked
    ? 'no new events (timeout)'
    : 'server is down (timeout)'
}
