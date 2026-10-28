import pocketdungeons from './profiles/pocketdungeons.mjs'
import charter from './profiles/charter.mjs'

export const profiles = [pocketdungeons, charter]

export function profileEvent(body, logger) {
  for (const profile of profiles) {
    if (logger && !profile.loggers.includes(logger)) continue
    for (const event of profile.events) {
      if (event.pattern.test(body)) return `${event.kind} ${body}`
    }
  }
  return null
}

export function parseContext(reply) {
  for (const line of reply.split(/\r?\n/).reverse()) {
    const start = line.indexOf('{')
    if (start < 0) continue
    try {
      const value = JSON.parse(line.slice(start))
      if (value && typeof value === 'object' && !Array.isArray(value)) return value
    } catch {}
  }
  return null
}

export async function mergedContext(player, run) {
  if (!/^[A-Za-z0-9_]{1,16}$/.test(player)) throw new Error('expected a Minecraft player name')
  const lemon = parseContext(await run(`lemon context ${player}`))
  if (!lemon) throw new Error('Lemon context unavailable: is the Lemon mod installed and the player online?')
  const result = { lemon }
  for (const profile of profiles) {
    const snapshot = parseContext(await run(profile.context(player)))
    if (snapshot) result[profile.id] = snapshot
  }
  return result
}
