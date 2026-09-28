// Log line parsing and the `wait` wake filter, kept pure (no server, no files) so
// wait-filter.test.mjs can check them against real log lines.

const EVENT = /\) (\[Not Secure\] )?<([^>]+)> (.*)$|(\S+) (joined|left) the game|completed floor|\/(WARN|ERROR)\] \(PocketDungeons\)|\(PocketDungeons\) (Lemon \w+ <|Report from )|Exception|Stopping server|Done \(/
// Lemon's lines: chat routed to Lemon is not broadcast, so the mod logs it (and Lemon's own lines) itself.
// Anchored to the line's own logger prefix, so chat that quotes one cannot pass for one.
const LEMON = /^\[\d\d:\d\d:\d\d\] \[[^\]]+\/INFO\] \(PocketDungeons\) Lemon (\w+) <([^>]+)>(?: (.*))?$/
const REPORT = /^\[\d\d:\d\d:\d\d\] \[[^\]]+\/INFO\] \(PocketDungeons\) Report from ([^:]+): (.*)$/
const NOISE = /oshi|SystemReport/

/** Turns a raw log line into a compact event line, or null if it is not one agents care about. */
export function eventOf(line) {
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
