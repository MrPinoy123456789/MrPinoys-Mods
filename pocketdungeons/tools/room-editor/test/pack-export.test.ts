import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it } from 'vitest'
import { History, airEntry, newRoomTemplate, sealDoor } from '../src/core/edit'
import { formatMeta, newRoomMeta } from '../src/core/meta'
import { RoomTemplate } from '../src/core/template'
/**
 * Opt in: PACK_OUT=<a COPY of a resources folder> npm test. Rewrites every
 * template in that copy through the editor's save path and adds a few test
 * rooms (an edit, a broken door, a new two story room, a sealed door, and a
 * versions/ backup in the in-game naming), so `gradlew validateRooms
 * -ProomsDir=<copy>` can prove the game's own loader accepts editor output.
 * Never point it at the real resources folder.
 */
const P = (process.env.PACK_OUT ?? '') + '/data/pocketdungeons'
describe.skipIf(!process.env.PACK_OUT)('editor output for the Java validator', () => it('rewrites every template through the editor and adds test rooms', () => {
  const dir = join(P, 'structure/rooms')
  for (const f of readdirSync(dir).filter(f => f.endsWith('.nbt'))) {
    const t = RoomTemplate.read(new Uint8Array(readFileSync(join(dir, f))))
    writeFileSync(join(dir, f), t.write(true))
  }
  // An edited room: gold block on the floor, a removed chest.
  const e = RoomTemplate.read(new Uint8Array(readFileSync(join(dir, 'hall_tee.nbt'))))
  const h = new History(e)
  h.run('edit', tx => { tx.set([5, 1, 5], { state: { name: 'minecraft:gold_block', props: {} } }); tx.set([2, 1, 2], airEntry()) })
  writeFileSync(join(dir, 'edited_tee.nbt'), e.write())
  writeFileSync(join(P, 'dungeon_room/edited_tee.json'), formatMeta({ ...newRoomMeta('pocketdungeons', 'edited_tee', 1), roles: ['encounter', 'loot', 'corridor'] }))
  // A broken door: one north slot sealed by hand.
  const b = RoomTemplate.read(new Uint8Array(readFileSync(join(dir, 'hall_tee.nbt'))))
  new History(b).run('break', tx => tx.set([7, 2, 0], { state: { name: 'minecraft:stone_bricks', props: {} } }))
  writeFileSync(join(dir, 'broken_door.nbt'), b.write())
  writeFileSync(join(P, 'dungeon_room/broken_door.json'), formatMeta(newRoomMeta('pocketdungeons', 'broken_door', 1)))
  // A brand new two story room from the editor's New room path.
  const n = newRoomTemplate(2, ['east', 'west'], 4903)
  writeFileSync(join(dir, 'new_two_story.nbt'), n.write())
  writeFileSync(join(P, 'dungeon_room/new_two_story.json'), formatMeta(newRoomMeta('pocketdungeons', 'new_two_story', 2)))
  // A sealed door on a copy of hall_cross: mask NESW becomes ESW.
  const c = RoomTemplate.read(new Uint8Array(readFileSync(join(dir, 'hall_cross.nbt'))))
  new History(c).run('seal', tx => sealDoor(c, tx, 'north', 1))
  writeFileSync(join(dir, 'sealed_cross.nbt'), c.write())
  writeFileSync(join(P, 'dungeon_room/sealed_cross.json'), formatMeta(newRoomMeta('pocketdungeons', 'sealed_cross', 1)))
  // The hazard in the in-game convention: a plain .json backup in versions/.
  mkdirSync(join(P, 'dungeon_room/versions'), { recursive: true })
  writeFileSync(join(P, 'dungeon_room/versions/hall_tee_2026-09-26T00-00-00Z.json'), readFileSync(join(P, 'dungeon_room/hall_tee.json')))
}))
