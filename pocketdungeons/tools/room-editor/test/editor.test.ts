import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  History, airEntry, boxOf, clearBox, copyBox, fillBox, newRoomTemplate, pasteAt, respanTemplate, sealDoor,
  stampDoor, transformSelection, type Box,
} from '../src/core/edit'
import { maskLetters, rotateMaskClockwise } from '../src/core/geometry'
import { formatMeta, newRoomMeta, validateMeta } from '../src/core/meta'
import { RoomTemplate } from '../src/core/template'
import { mirrorState, rotateState } from '../src/core/transform'
import { validateRoom } from '../src/core/validate'
import { PackFolder, versionStamp } from '../src/ui/storage'
import { META_DIR, ROOMS_DIR } from './env'

const load = (name: string) => RoomTemplate.read(new Uint8Array(readFileSync(join(ROOMS_DIR, `${name}.nbt`))))
const meta = (name: string) => JSON.parse(readFileSync(join(META_DIR, `${name}.json`), 'utf8').replace(/^﻿/, ''))
const errors = (r: ReturnType<typeof validateRoom>) => r.findings.filter(f => f.severity === 'error').map(f => f.message)

describe('client side checks mirror the game', () => {
  it('derives the manifest door masks', () => {
    expect(maskLetters(validateRoom(load('hall_tee'), meta('hall_tee')).mask)).toBe('NES')
    expect(maskLetters(validateRoom(load('hall_cross'), meta('hall_cross')).mask)).toBe('NESW')
    expect(maskLetters(validateRoom(load('sump'), meta('sump')).mask)).toBe('EW')
    expect(maskLetters(rotateMaskClockwise(0b0111, 1))).toBe('ESW')
  })

  it('reports a partial door with the manifest wording', () => {
    const t = load('hall_tee')
    new History(t).run('break', tx => tx.set([7, 2, 0], { state: { name: 'minecraft:stone_bricks', props: {} } }))
    expect(errors(validateRoom(t, meta('hall_tee')))).toContain('partial door on north wall: missing jigsaw at 7, 2, 0')
  })

  it('reports a spanY mismatch', () => {
    const e = errors(validateRoom(load('sump'), { ...meta('sump'), spanY: 1 }))
    expect(e.some(m => m.startsWith('spanY mismatch'))).toBe(true)
  })

  it('seals and stamps doors on the canonical slots', () => {
    const t = load('hall_cross')
    const h = new History(t)
    h.run('seal', tx => sealDoor(t, tx, 'north', 1))
    expect(maskLetters(validateRoom(t, meta('hall_cross')).mask)).toBe('ESW')
    h.run('stamp', tx => stampDoor(t, tx, 'north', 1))
    const r = validateRoom(t, meta('hall_cross'))
    expect(maskLetters(r.mask)).toBe('NESW')
    expect(errors(r)).toEqual([])
  })

  it('builds new rooms and resizes spanY without errors', () => {
    const one = newRoomTemplate(1, ['north', 'south'], 4903)
    expect(one.size).toEqual([16, 7, 16])
    expect(errors(validateRoom(one, newRoomMeta('pocketdungeons', 'x', 1)))).toEqual([])
    const two = newRoomTemplate(2, ['east'], 4903)
    expect(two.size).toEqual([16, 15, 16])
    const r2 = validateRoom(two, newRoomMeta('pocketdungeons', 'x', 2))
    expect(errors(r2)).toEqual([])
    expect(maskLetters(r2.mask)).toBe('E')
    const grown = respanTemplate(load('hall_tee'), 1, 2)
    expect(grown.size).toEqual([16, 15, 16])
    const rg = validateRoom(grown, { ...meta('hall_tee'), spanY: 2 })
    expect(errors(rg)).toEqual([])
    expect(maskLetters(rg.mask)).toBe('NES')
    const shrunk = respanTemplate(load('sump'), 2, 1)
    expect(errors(validateRoom(shrunk, { ...meta('sump'), spanY: 1 }))).toEqual([])
  })
})

describe('editing', () => {
  it('fills, clears, copies, pastes, rotates and undoes', () => {
    const t = newRoomTemplate(1, [], 4903)
    const h = new History(t)
    const box = boxOf([2, 1, 2], [4, 1, 3])
    h.run('fill', tx => fillBox(tx, box, { state: { name: 'minecraft:oak_stairs', props: { facing: 'north', half: 'bottom', shape: 'inner_left', waterlogged: 'false' } } }))
    expect(t.get(4, 1, 3)?.state.name).toBe('minecraft:oak_stairs')
    const clip = copyBox(t, box)
    h.run('paste', tx => pasteAt(tx, clip, [8, 2, 8], true))
    expect(t.get(10, 2, 9)?.state.props.facing).toBe('north')
    let next: Box = box
    h.run('rotate', tx => { next = transformSelection(t, tx, box, { rotate: 1 }) })
    expect(next.max).toEqual([3, 1, 4])
    expect(t.get(2, 1, 2)?.state.props.facing).toBe('east')
    h.run('clear', tx => clearBox(tx, boxOf([0, 1, 0], [15, 5, 15])))
    expect(t.get(2, 1, 2)?.state.name).toBe('minecraft:air')
    h.undo(); h.undo(); h.undo(); h.undo()
    expect(t.get(4, 1, 3)?.state.name).toBe('minecraft:air')
    h.redo()
    expect(t.get(4, 1, 3)?.state.name).toBe('minecraft:oak_stairs')
  })

  it('keeps block entity data on a same block change and drops it on a different block', () => {
    const t = load('hall_tee')
    const chest = t.get(2, 1, 2)!
    expect(chest.nbt).toBeDefined()
    const h = new History(t)
    h.run('turn', tx => tx.set([2, 1, 2], { state: { ...chest.state, props: { ...chest.state.props, facing: 'east' } } }))
    expect(t.get(2, 1, 2)!.nbt).toBe(chest.nbt)
    h.run('replace', tx => tx.set([2, 1, 2], { state: { name: 'minecraft:barrel', props: { facing: 'up', open: 'false' } } }))
    expect(t.get(2, 1, 2)!.nbt).toBeUndefined()
    h.run('remove', tx => tx.set([5, 1, 5], airEntry()))
  })

  it('drops entities inside a cleared box and restores them on undo', () => {
    const t = load('rotation_lock')
    const before = t.getEntities().length
    expect(before).toBeGreaterThan(0)
    const h = new History(t)
    h.run('clear all', tx => clearBox(tx, boxOf([0, 0, 0], [15, 6, 15])))
    expect(t.getEntities().length).toBe(0)
    h.undo()
    expect(t.getEntities().length).toBe(before)
  })

  it('rotates and mirrors block states', () => {
    const s = { name: 'minecraft:oak_fence', props: { east: 'true', north: 'false', south: 'false', waterlogged: 'false', west: 'false' } }
    expect(rotateState(s, 1).props).toMatchObject({ south: 'true', east: 'false' })
    expect(rotateState({ name: 'minecraft:jigsaw', props: { orientation: 'north_up' } }, 1).props.orientation).toBe('east_up')
    expect(rotateState({ name: 'minecraft:oak_sign', props: { rotation: '14' } }, 1).props.rotation).toBe('2')
    expect(mirrorState({ name: 'minecraft:oak_door', props: { facing: 'north', hinge: 'left', half: 'lower' } }, 'z').props).toMatchObject({ facing: 'south', hinge: 'right' })
    expect(mirrorState({ name: 'minecraft:oak_stairs', props: { facing: 'east', shape: 'inner_left' } }, 'x').props).toMatchObject({ facing: 'west', shape: 'inner_right' })
  })
})

describe('metadata', () => {
  it('validates shipped room files against the schema and rejects bad ones', () => {
    const bad: string[] = []
    for (const n of ['hall_tee', 'sump', 'blaze_cellar', 'the_store', 'bazaar']) {
      const errs = validateMeta(meta(n))
      if (errs.length) bad.push(`${n}: ${errs.join('; ')}`)
    }
    expect(bad).toEqual([])
    expect(validateMeta({ template: 'pocketdungeons:rooms/x', roles: ['boss'] }).length).toBeGreaterThan(0)
    expect(validateMeta({ template: 'pocketdungeons:rooms/x', roles: ['loot'], spanY: 3 }).length).toBeGreaterThan(0)
  })

  it('formats like the shipped files', () => {
    const text = readFileSync(join(META_DIR, 'hall_tee.json'), 'utf8').replace(/^﻿/, '')
    expect(formatMeta(JSON.parse(text), text)).toBe(text)
  })
})

// ---- the save path over a fake File System Access directory ------------------

class FakeFile {
  constructor(public data: Uint8Array) {}
}

class FakeDir {
  kind = 'directory' as const
  children = new Map<string, FakeDir | FakeFile>()
  constructor(public name: string) {}

  async getDirectoryHandle(n: string, o?: { create?: boolean }): Promise<FakeDir> {
    let c = this.children.get(n)
    if (!c && o?.create) { c = new FakeDir(n); this.children.set(n, c) }
    if (!(c instanceof FakeDir)) throw new Error('NotFoundError')
    return c
  }

  async getFileHandle(n: string, o?: { create?: boolean }) {
    let c = this.children.get(n)
    if (!c && o?.create) { c = new FakeFile(new Uint8Array()); this.children.set(n, c) }
    if (!(c instanceof FakeFile)) throw new Error('NotFoundError')
    const file = c
    return {
      kind: 'file' as const,
      getFile: async () => new Blob([file.data as BlobPart]),
      createWritable: async () => {
        const parts: Uint8Array[] = []
        return {
          write: async (d: Blob | string) => { parts.push(typeof d === 'string' ? new TextEncoder().encode(d) : new Uint8Array(await d.arrayBuffer())) },
          close: async () => { file.data = new Uint8Array(Buffer.concat(parts)) },
        }
      },
    }
  }

  async *entries(): AsyncGenerator<[string, { kind: string }]> {
    for (const [k, v] of this.children) yield [k, { kind: v instanceof FakeDir ? 'directory' : 'file' }]
  }
}

describe('saving into a folder', () => {
  it('lists rooms and backs up into versions/ with the in-game naming', async () => {
    const root = new FakeDir('pocketdungeons')
    const rooms = await (await root.getDirectoryHandle('structure', { create: true })).getDirectoryHandle('rooms', { create: true })
    const metaDir = await root.getDirectoryHandle('dungeon_room', { create: true })
    rooms.children.set('hall_tee.nbt', new FakeFile(new Uint8Array(readFileSync(join(ROOMS_DIR, 'hall_tee.nbt')))))
    metaDir.children.set('hall_tee.json', new FakeFile(new Uint8Array(readFileSync(join(META_DIR, 'hall_tee.json')))))
    const folder = await PackFolder.fromHandle(root as unknown as FileSystemDirectoryHandle)
    const list = await folder.listRooms()
    expect(list.map(r => [r.name, r.templateExists])).toEqual([['hall_tee', true]])
    const t = RoomTemplate.read((await folder.readTemplate('structure/rooms/hall_tee.nbt'))!)
    new History(t).run('edit', tx => tx.set([5, 1, 5], { state: { name: 'minecraft:gold_block', props: {} } }))
    const nbtBackup = await folder.writeWithBackup('structure/rooms/hall_tee.nbt', t.write())
    const jsonBackup = await folder.writeWithBackup('dungeon_room/hall_tee.json', formatMeta({ ...meta('hall_tee'), weight: 3 }))
    expect(nbtBackup).toMatch(/^structure\/rooms\/versions\/hall_tee_\d{4}-\d\d-\d\dT\d\d-\d\d-\d\dZ\.nbt$/)
    expect(jsonBackup).toMatch(/^dungeon_room\/versions\/hall_tee_.*Z\.json\.bak$/)
    const again = await folder.writeWithBackup('structure/rooms/hall_tee.nbt', t.write(true))
    expect(again).not.toBe(nbtBackup)
    const reread = RoomTemplate.read((await folder.readTemplate('structure/rooms/hall_tee.nbt'))!)
    expect(reread.get(5, 1, 5)?.state.name).toBe('minecraft:gold_block')
    expect((await folder.listRooms()).map(r => r.name)).toEqual(['hall_tee'])
    expect(versionStamp(new Date('2026-09-26T12:34:56.789Z'))).toBe('2026-09-26T12-34-56Z')
  })
})
