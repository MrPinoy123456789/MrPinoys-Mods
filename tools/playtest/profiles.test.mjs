import assert from 'node:assert/strict'
import { mergedContext, parseContext, profiles } from './profiles.mjs'
import { eventOf, wakesWait } from './wait-filter.mjs'
import { pocketDungeonsHooks } from './runtime.mjs'

assert.equal(profiles.length, 2)
assert.deepEqual(parseContext('noise\n[console] {"present":true}\n'), { present: true })
assert.equal(parseContext('Unknown command'), null)
const commands = []
const snapshot = await mergedContext('Alex', async command => {
  commands.push(command)
  if (command === 'lemon context Alex') return '{"present":true,"mode":"llm"}'
  if (command === 'dungeon admin context Alex') return '{"phase":"HOME"}'
  return 'Unknown or incomplete command'
})
assert.deepEqual(snapshot, { lemon: { present: true, mode: 'llm' }, pocketdungeons: { phase: 'HOME' } })
assert.deepEqual(commands, ['lemon context Alex', 'dungeon admin context Alex', 'charter admin context Alex'])
const charterOnly = await mergedContext('Alex', async command => command.startsWith('lemon ')
  ? '{"present":false}' : command.startsWith('charter ') ? '{"faction":"Test"}' : 'Unknown command')
assert.deepEqual(charterOnly, { lemon: { present: false }, charter: { faction: 'Test' } })
const both = await mergedContext('Alex', async command => command.startsWith('lemon ')
  ? '{"present":true}' : command.startsWith('dungeon ') ? '{"phase":"HOME"}' : '{"faction":"Test"}')
assert.deepEqual(Object.keys(both), ['lemon', 'pocketdungeons', 'charter'])
const noGames = await mergedContext('Alex', async command => command.startsWith('lemon ')
  ? '{"present":false}' : 'Unknown command')
assert.deepEqual(noGames, { lemon: { present: false } })
await assert.rejects(() => mergedContext('Alex', async () => 'No player was found'), /unavailable/)
await assert.rejects(() => mergedContext('Alex\nsay bad', async () => '{}'), /player name/)
for (const logger of ['Lemon', 'lemon']) {
  const event = eventOf(`[12:34:56] [Server thread/INFO] (${logger}) Lemon ask <Alex> hello`)
  assert.equal(event, '12:34:56 lemon ask <Alex> hello')
  assert.equal(wakesWait(event), true)
}
for (const kind of ['founding', 'claiming', 'camp raised', 'keep fallen', 'arrest']) {
  const event = eventOf(`[12:34:56] [Server thread/INFO] (Charter) Charter ${kind} <Alex> Test`)
  assert.equal(event, `12:34:56 charter Charter ${kind} <Alex> Test`)
  assert.equal(wakesWait(event), true)
}
assert.equal(eventOf('[12:34:56] [Server thread/INFO]: <Alex> Charter founding Test'), '12:34:56 chat <Alex> Charter founding Test')
const hooks = pocketDungeonsHooks({ lemonIdleSeconds: 33, onInteractHolding: { '#test:items': 'test click' } })
assert.equal(hooks.lemonIdleSeconds, 33)
assert.equal(hooks.onInteract, 'dungeon lemonmenu')
assert.equal(hooks.onInteractHolding['minecraft:written_book'], 'dungeon lemonarchive')
assert.equal(hooks.onInteractHolding['#test:items'], 'test click')
assert.equal(pocketDungeonsHooks({ onInteract: 'custom click' }).onInteract, 'custom click')
console.log('profiles.test.mjs passed')
