import { NbtCompound, NbtDouble, NbtInt, NbtString } from 'deepslate/nbt'
import {
  CEILING_Y, CELL, STORY_HEIGHT, WALL_HEIGHT, canonicalDoorSlots, doorOrientation, storyOffset,
  templateHeight, type Edge, type Vec3,
} from './geometry'
import { cloneEntry, cloneTag, isAir, RoomTemplate, type BlockEntry, type BlockStateLite, type EntityRef } from './template'
import { mirrorState, rotateState, type MirrorAxis } from './transform'
import { DOOR_NAME, jigsawName, SPAWN_NAME } from './validate'

/** Edit operations with undo and redo. Every mutation goes through a transaction. */

export interface Change {
  pos: Vec3
  before: BlockEntry | undefined
  after: BlockEntry | undefined
}

export interface Transaction {
  label: string
  changes: Change[]
  removedEntities: EntityRef[]
}

export interface Box {
  min: Vec3
  max: Vec3
}

export function boxOf(a: Vec3, b: Vec3): Box {
  return {
    min: [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])],
    max: [Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])],
  }
}

export function boxSize(b: Box): Vec3 {
  return [b.max[0] - b.min[0] + 1, b.max[1] - b.min[1] + 1, b.max[2] - b.min[2] + 1]
}

export interface Clipboard {
  size: Vec3
  blocks: { rel: Vec3; entry: BlockEntry }[]
}

export const AIR: BlockStateLite = { name: 'minecraft:air', props: {} }

export function airEntry(): BlockEntry {
  return { state: AIR }
}

export class TxBuilder {
  private readonly tx: Transaction
  private readonly seen = new Map<string, Change>()

  constructor(private readonly t: RoomTemplate, label: string) {
    this.tx = { label, changes: [], removedEntities: [] }
  }

  /**
   * Sets a block. A different block id drops the old block entity data unless
   * the new entry brings its own; the same id with new properties (a rotated
   * chest) keeps it.
   */
  set(pos: Vec3, entry: BlockEntry | undefined): void {
    if (!this.t.inBounds(...pos)) return
    const before = this.t.get(...pos)
    let next = entry
    if (next && before && !next.nbt && before.nbt && before.state.name === next.state.name) {
      next = { state: next.state, nbt: before.nbt }
    }
    if (before === next) return
    if (before && next && !before.nbt && !next.nbt && sameStateObj(before.state, next.state)) return
    this.t.set(pos[0], pos[1], pos[2], next)
    const k = pos.join(',')
    const prior = this.seen.get(k)
    if (prior) prior.after = next
    else {
      const c: Change = { pos: [...pos] as Vec3, before, after: next }
      this.seen.set(k, c)
      this.tx.changes.push(c)
    }
  }

  removeEntitiesIn(box: Box): void {
    this.tx.removedEntities.push(...this.t.removeEntitiesIn(box.min, box.max))
  }

  finish(): Transaction | null {
    return this.tx.changes.length > 0 || this.tx.removedEntities.length > 0 ? this.tx : null
  }
}

function sameStateObj(a: BlockStateLite, b: BlockStateLite): boolean {
  if (a.name !== b.name) return false
  const ka = Object.keys(a.props), kb = Object.keys(b.props)
  return ka.length === kb.length && ka.every(k => a.props[k] === b.props[k])
}

export class History {
  private undoStack: Transaction[] = []
  private redoStack: Transaction[] = []
  constructor(private readonly t: RoomTemplate, private readonly limit = 200) {}

  run(label: string, fn: (tx: TxBuilder) => void): Transaction | null {
    const b = new TxBuilder(this.t, label)
    fn(b)
    const tx = b.finish()
    if (tx) {
      this.undoStack.push(tx)
      if (this.undoStack.length > this.limit) this.undoStack.shift()
      this.redoStack = []
    }
    return tx
  }

  canUndo(): boolean { return this.undoStack.length > 0 }
  canRedo(): boolean { return this.redoStack.length > 0 }
  peekUndo(): string | null { return this.undoStack.at(-1)?.label ?? null }
  peekRedo(): string | null { return this.redoStack.at(-1)?.label ?? null }

  undo(): Transaction | null {
    const tx = this.undoStack.pop()
    if (!tx) return null
    for (let i = tx.changes.length - 1; i >= 0; i--) {
      const c = tx.changes[i]
      this.t.set(c.pos[0], c.pos[1], c.pos[2], c.before)
    }
    this.t.restoreEntities(tx.removedEntities)
    this.redoStack.push(tx)
    return tx
  }

  redo(): Transaction | null {
    const tx = this.redoStack.pop()
    if (!tx) return null
    for (const c of tx.changes) this.t.set(c.pos[0], c.pos[1], c.pos[2], c.after)
    this.t.removeEntityTags(tx.removedEntities.map(r => r.tag))
    this.undoStack.push(tx)
    return tx
  }
}

// ---- operations ----------------------------------------------------------

export function forBox(box: Box, fn: (p: Vec3) => void): void {
  for (let y = box.min[1]; y <= box.max[1]; y++)
    for (let z = box.min[2]; z <= box.max[2]; z++)
      for (let x = box.min[0]; x <= box.max[0]; x++) fn([x, y, z])
}

export function fillBox(tx: TxBuilder, box: Box, entry: BlockEntry): void {
  forBox(box, p => tx.set(p, entry.nbt ? cloneEntry(entry) : entry))
}

export function clearBox(tx: TxBuilder, box: Box): void {
  const air = airEntry()
  forBox(box, p => tx.set(p, air))
  tx.removeEntitiesIn(box)
}

export function copyBox(t: RoomTemplate, box: Box): Clipboard {
  const blocks: Clipboard['blocks'] = []
  forBox(box, p => {
    const e = t.get(...p)
    if (e) blocks.push({ rel: [p[0] - box.min[0], p[1] - box.min[1], p[2] - box.min[2]], entry: cloneEntry(e) })
  })
  return { size: boxSize(box), blocks }
}

export function pasteAt(tx: TxBuilder, clip: Clipboard, origin: Vec3, skipAir: boolean): void {
  for (const b of clip.blocks) {
    if (skipAir && isAir(b.entry.state)) continue
    tx.set([origin[0] + b.rel[0], origin[1] + b.rel[1], origin[2] + b.rel[2]], cloneEntry(b.entry))
  }
}

/** Rotates a clipboard clockwise (seen from above) by quarter turns. */
export function rotateClipboard(clip: Clipboard, quarterTurns: number): Clipboard {
  let c = clip
  const q = ((quarterTurns % 4) + 4) % 4
  for (let i = 0; i < q; i++) {
    const [sx, sy, sz] = c.size
    c = {
      size: [sz, sy, sx],
      blocks: c.blocks.map(b => ({
        rel: [sz - 1 - b.rel[2], b.rel[1], b.rel[0]] as Vec3,
        entry: { state: rotateState(b.entry.state, 1), nbt: b.entry.nbt },
      })),
    }
  }
  return c
}

export function mirrorClipboard(clip: Clipboard, axis: MirrorAxis): Clipboard {
  const [sx, , sz] = clip.size
  return {
    size: clip.size,
    blocks: clip.blocks.map(b => ({
      rel: (axis === 'x' ? [sx - 1 - b.rel[0], b.rel[1], b.rel[2]] : [b.rel[0], b.rel[1], sz - 1 - b.rel[2]]) as Vec3,
      entry: { state: mirrorState(b.entry.state, axis), nbt: b.entry.nbt },
    })),
  }
}

/** Rotates or mirrors a selection in place, anchored at its minimum corner. */
export function transformSelection(t: RoomTemplate, tx: TxBuilder, box: Box, op: { rotate?: number; mirror?: MirrorAxis }): Box {
  let clip = copyBox(t, box)
  if (op.rotate) clip = rotateClipboard(clip, op.rotate)
  if (op.mirror) clip = mirrorClipboard(clip, op.mirror)
  const air = airEntry()
  forBox(box, p => tx.set(p, air))
  pasteAt(tx, clip, box.min, false)
  const max: Vec3 = [box.min[0] + clip.size[0] - 1, box.min[1] + clip.size[1] - 1, box.min[2] + clip.size[2] - 1]
  return { min: box.min, max: [Math.min(max[0], t.size[0] - 1), Math.min(max[1], t.size[1] - 1), Math.min(max[2], t.size[2] - 1)] }
}

// ---- jigsaw presets --------------------------------------------------------

export function jigsawNbt(name: string, template?: NbtCompound): NbtCompound {
  if (template) {
    const c = cloneTag(template)
    c.set('name', new NbtString(name))
    return c
  }
  const c = new NbtCompound()
  c.set('components', new NbtCompound())
  c.set('joint', new NbtString('rollable'))
  c.set('name', new NbtString(name))
  c.set('pool', new NbtString('minecraft:empty'))
  c.set('final_state', new NbtString('minecraft:air'))
  c.set('placement_priority', new NbtInt(0))
  c.set('selection_priority', new NbtInt(0))
  c.set('id', new NbtString('minecraft:jigsaw'))
  c.set('target', new NbtString('minecraft:empty'))
  return c
}

/** An existing door jigsaw's block entity, so new ones match the file's own shape. */
export function findJigsawTemplate(t: RoomTemplate, name: string): NbtCompound | undefined {
  for (const { entry } of t.entries()) {
    if (jigsawName(entry) === name) return entry.nbt
  }
  return undefined
}

export function doorJigsawEntry(t: RoomTemplate, edge: Edge): BlockEntry {
  return {
    state: { name: 'minecraft:jigsaw', props: { orientation: doorOrientation(edge) } },
    nbt: jigsawNbt(DOOR_NAME, findJigsawTemplate(t, DOOR_NAME)),
  }
}

export function spawnJigsawEntry(t: RoomTemplate): BlockEntry {
  return {
    state: { name: 'minecraft:jigsaw', props: { orientation: 'up_north' } },
    nbt: jigsawNbt(SPAWN_NAME, findJigsawTemplate(t, SPAWN_NAME) ?? findJigsawTemplate(t, DOOR_NAME)),
  }
}

export function chestEntry(): BlockEntry {
  const nbt = new NbtCompound()
  nbt.set('LootTable', new NbtString('pocketdungeons:chests/tier_1'))
  nbt.set('components', new NbtCompound())
  nbt.set('id', new NbtString('minecraft:chest'))
  return { state: { name: 'minecraft:chest', props: { facing: 'south', type: 'single', waterlogged: 'false' } }, nbt }
}

export function stampDoor(t: RoomTemplate, tx: TxBuilder, edge: Edge, spanY: number): void {
  const entry = doorJigsawEntry(t, edge)
  for (const p of canonicalDoorSlots(edge, storyOffset(spanY))) tx.set(p, cloneEntry(entry))
}

/** Fills a doorway back in with the wall block found beside it. */
export function sealDoor(t: RoomTemplate, tx: TxBuilder, edge: Edge, spanY: number): void {
  const slots = canonicalDoorSlots(edge, storyOffset(spanY))
  const counts = new Map<string, { n: number; entry: BlockEntry }>()
  for (const [x, y, z] of slots) {
    for (const d of [-2, 2, -1, 1]) {
      const p: Vec3 = edge === 'north' || edge === 'south' ? [x + d, y, z] : [x, y, z + d]
      const e = t.get(...p)
      if (!e || isAir(e.state) || e.state.name === 'minecraft:jigsaw' || e.nbt) continue
      const k = e.state.name + JSON.stringify(e.state.props)
      const c = counts.get(k) ?? { n: 0, entry: e }
      c.n++
      counts.set(k, c)
    }
  }
  const best = [...counts.values()].sort((a, b) => b.n - a.n)[0]?.entry ?? { state: { name: 'minecraft:stone_bricks', props: {} } }
  for (const p of slots) tx.set(p, { state: best.state })
}

// ---- new rooms and story changes -------------------------------------------

const S = (name: string, props: Record<string, string> = {}): BlockEntry => ({ state: { name, props } })

/** RoomBuilder.stampShell's stone brick frame, one story, at a y offset. */
function stampShell(t: RoomTemplate, y0: number): void {
  const floor = S('minecraft:polished_andesite')
  const wall = S('minecraft:stone_bricks')
  const slab = S('minecraft:stone_brick_slab', { type: 'top', waterlogged: 'false' })
  const air = airEntry()
  for (let x = 0; x < CELL; x++) {
    for (let z = 0; z < CELL; z++) {
      const edge = x === 0 || x === CELL - 1 || z === 0 || z === CELL - 1
      t.set(x, y0, z, floor)
      t.set(x, y0 + CEILING_Y, z, edge ? wall : slab)
      for (let y = 1; y <= WALL_HEIGHT; y++) t.set(x, y0 + y, z, edge ? wall : air)
    }
  }
  const lamp = S('minecraft:sea_lantern')
  for (const [lx, lz] of [[4, 4], [4, CELL - 5], [CELL - 5, 4], [CELL - 5, CELL - 5]]) {
    t.set(lx, y0 + CEILING_Y, lz, lamp)
    const stair = (facing: string) => S('minecraft:stone_brick_stairs', { facing, half: 'bottom', shape: 'straight', waterlogged: 'false' })
    t.set(lx, y0 + CEILING_Y, lz - 1, stair('north'))
    t.set(lx, y0 + CEILING_Y, lz + 1, stair('south'))
    t.set(lx - 1, y0 + CEILING_Y, lz, stair('west'))
    t.set(lx + 1, y0 + CEILING_Y, lz, stair('east'))
  }
}

/** RoomTemplateGenerator.buildLowerStories: a shell per lower story plus the filler above it. */
function stampLowerStories(t: RoomTemplate, spanY: number): void {
  const top = storyOffset(spanY)
  const wall = S('minecraft:stone_bricks')
  for (let story = 1; story < spanY; story++) {
    const y0 = top - story * STORY_HEIGHT
    stampShell(t, y0)
    for (let y = y0 + CEILING_Y + 1; y <= top - (story - 1) * STORY_HEIGHT - 1; y++) {
      for (let x = 0; x < CELL; x++) for (let z = 0; z < CELL; z++) t.set(x, y, z, wall)
    }
  }
}

export function newRoomTemplate(spanY: number, doors: Edge[], dataVersion: number): RoomTemplate {
  const t = RoomTemplate.create([CELL, templateHeight(spanY), CELL], dataVersion)
  stampShell(t, storyOffset(spanY))
  stampLowerStories(t, spanY)
  const door = doorJigsawEntry(t, 'north')
  for (const edge of doors) {
    for (const p of canonicalDoorSlots(edge, storyOffset(spanY))) {
      t.set(p[0], p[1], p[2], {
        state: { name: 'minecraft:jigsaw', props: { orientation: doorOrientation(edge) } },
        nbt: cloneTag(door.nbt!),
      })
    }
  }
  return t
}

/**
 * A copy of the template resized for a new spanY: the upper story keeps its
 * blocks (shifted to the new story offset), a new lower story gets the
 * generator's plain shell, a removed lower story is dropped. Entities shift
 * with the blocks.
 */
export function respanTemplate(src: RoomTemplate, fromSpan: number, toSpan: number): RoomTemplate {
  const shift = storyOffset(toSpan) - storyOffset(fromSpan)
  const t = RoomTemplate.create([src.size[0], templateHeight(toSpan), src.size[2]], src.dataVersion)
  if (toSpan > fromSpan) stampLowerStories(t, toSpan)
  for (const { pos, entry } of src.entries()) {
    const y = pos[1] + shift
    if (y < 0 || y >= t.size[1]) continue
    t.set(pos[0], y, pos[2], cloneEntry(entry))
  }
  for (const e of src.getEntities()) {
    const c = cloneTag(e)
    const bp = c.hasList('blockPos') ? c.getList('blockPos') : null
    if (bp) {
      const y = bp.getNumber(1) + shift
      if (y < 0 || y >= t.size[1]) continue
      bp.set(1, new NbtInt(y))
    }
    if (c.hasList('pos')) {
      const p = c.getList('pos')
      const v = p.get(1)
      if (v) p.set(1, new NbtDouble(v.getAsNumber() + shift))
    }
    t.addEntity(c)
  }
  return t
}

