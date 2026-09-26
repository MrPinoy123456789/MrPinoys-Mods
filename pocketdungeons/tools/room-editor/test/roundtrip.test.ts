import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { gunzipSync } from 'node:zlib'
import { NbtFile } from 'deepslate/nbt'
import { describe, expect, it } from 'vitest'
import { History, airEntry } from '../src/core/edit'
import { diffNbt } from '../src/core/nbtdiff'
import { RoomTemplate } from '../src/core/template'
import { ROOMS_DIR, roomFiles } from './env'

const files = roomFiles()

describe('structure NBT round trip', () => {
  it('finds the room templates', () => {
    expect(files.length).toBeGreaterThan(0)
  })

  const results: { file: string; identicalBytes: boolean; diffs: string[] }[] = []

  for (const f of files) {
    it(`round trips ${f} through the editor model with zero structural diff`, () => {
      const buf = new Uint8Array(readFileSync(join(ROOMS_DIR, f)))
      const original = NbtFile.read(buf)
      const t = RoomTemplate.read(buf)
      // forceRebuild: the palette and block lists go through the same path an edited file takes.
      const out = t.write(true)
      const reread = NbtFile.read(out)
      const diffs = diffNbt(original.root, reread.root)
      const identicalBytes = Buffer.compare(gunzipSync(buf), gunzipSync(out)) === 0
      results.push({ file: f, identicalBytes, diffs })
      expect(diffs).toEqual([])
      expect(identicalBytes).toBe(true)
      expect(reread.compression).toBe('gzip')
    })
  }

  it('an edit that is undone writes back an identical tree', () => {
    const f = files.find(n => n === 'hall_tee.nbt') ?? files[0]
    const buf = new Uint8Array(readFileSync(join(ROOMS_DIR, f)))
    const t = RoomTemplate.read(buf)
    const h = new History(t)
    h.run('place', tx => {
      tx.set([5, 1, 5], { state: { name: 'minecraft:gold_block', props: {} } })
      tx.set([2, 1, 2], airEntry())
    })
    expect(t.get(5, 1, 5)?.state.name).toBe('minecraft:gold_block')
    h.undo()
    const diffs = diffNbt(NbtFile.read(buf).root, NbtFile.read(t.write(true)).root)
    expect(diffs).toEqual([])
  })

  it('an edit persists through write and re-read and keeps other block entities', () => {
    const f = files.find(n => n === 'hall_tee.nbt') ?? files[0]
    const buf = new Uint8Array(readFileSync(join(ROOMS_DIR, f)))
    const t = RoomTemplate.read(buf)
    const beBefore = [...t.entries()].filter(e => e.entry.nbt).length
    const h = new History(t)
    h.run('place', tx => tx.set([5, 1, 5], { state: { name: 'minecraft:oak_stairs', props: { facing: 'east', half: 'bottom', shape: 'straight', waterlogged: 'false' } } }))
    const t2 = RoomTemplate.read(t.write())
    expect(t2.get(5, 1, 5)?.state).toEqual({ name: 'minecraft:oak_stairs', props: { facing: 'east', half: 'bottom', shape: 'straight', waterlogged: 'false' } })
    expect([...t2.entries()].filter(e => e.entry.nbt).length).toBe(beBefore)
    expect(t2.size).toEqual(t.size)
    expect(t2.dataVersion).toBe(t.dataVersion)
  })

  it('reports the summary', () => {
    const identical = results.filter(r => r.identicalBytes && r.diffs.length === 0).length
    console.log(`ROUNDTRIP ${identical} of ${results.length} identical (structural diff zero and identical decompressed bytes)`)
    for (const r of results.filter(r => !r.identicalBytes || r.diffs.length)) console.log('  differs:', r.file, r.diffs.slice(0, 3))
  })
})
