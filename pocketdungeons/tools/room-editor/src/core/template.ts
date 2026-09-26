import { NbtCompound, NbtFile, NbtInt, NbtList, NbtString, NbtTag } from 'deepslate/nbt'
import type { Vec3 } from './geometry'

/**
 * An editable view over a vanilla structure template (the gzipped NBT the game
 * writes with StructureTemplate.save).
 *
 * Fidelity is the design constraint. Everything the editor does not
 * understand is carried through untouched: the root's other keys (DataVersion,
 * pd_author, pd_saved_at, anything a later version adds), each original block
 * compound, each palette entry, and the entity list. On save the block and
 * palette lists are rebuilt so that an unedited cell reuses its original
 * compound object and its original palette index, which is what makes an
 * unmodified file round trip to an identical tree.
 */

export interface BlockStateLite {
  name: string
  props: Record<string, string>
}

export interface BlockEntry {
  state: BlockStateLite
  /** Block entity data (chests, jigsaws, spawners). Dropped when the block id changes. */
  nbt?: NbtCompound
}

interface Cell {
  pos: Vec3
  entry: BlockEntry
  /** The entry as loaded; identity comparison tells an untouched cell apart. */
  origEntry?: BlockEntry
  origCompound?: NbtCompound
  origStateIndex?: number
}

export function posKey(x: number, y: number, z: number): number {
  return ((x + 512) * 2048 + (y + 512)) * 2048 + (z + 512)
}

export function stateKey(s: BlockStateLite): string {
  const keys = Object.keys(s.props).sort()
  if (keys.length === 0) return s.name
  return `${s.name}[${keys.map(k => `${k}=${s.props[k]}`).join(',')}]`
}

export function parseStateString(str: string): BlockStateLite {
  const m = /^([^[\]]+)(?:\[(.*)\])?$/.exec(str.trim())
  if (!m) throw new Error(`not a block state: ${str}`)
  let name = m[1].trim()
  if (!name.includes(':')) name = `minecraft:${name}`
  const props: Record<string, string> = {}
  if (m[2]) {
    for (const part of m[2].split(',')) {
      if (!part.trim()) continue
      const [k, v] = part.split('=')
      props[k.trim()] = (v ?? '').trim()
    }
  }
  return { name, props }
}

export function isAir(s: BlockStateLite | undefined | null): boolean {
  return !s || s.name === 'minecraft:air' || s.name === 'minecraft:cave_air' || s.name === 'minecraft:void_air'
}

export function sameState(a: BlockStateLite, b: BlockStateLite): boolean {
  return stateKey(a) === stateKey(b)
}

export function cloneTag<T extends NbtTag>(tag: T): T {
  return NbtTag.fromJsonWithId(tag.toJsonWithId()) as T
}

export function cloneEntry(e: BlockEntry): BlockEntry {
  return { state: { name: e.state.name, props: { ...e.state.props } }, nbt: e.nbt ? cloneTag(e.nbt) : undefined }
}

function stateFromPaletteCompound(c: NbtCompound): BlockStateLite {
  const props: Record<string, string> = {}
  if (c.hasCompound('Properties')) {
    c.getCompound('Properties').forEach((k, v) => { props[k] = v.getAsString() })
  }
  return { name: c.getString('Name'), props }
}

function paletteCompound(s: BlockStateLite): NbtCompound {
  const c = new NbtCompound()
  const keys = Object.keys(s.props)
  if (keys.length > 0) {
    const p = new NbtCompound()
    for (const k of keys) p.set(k, new NbtString(s.props[k]))
    c.set('Properties', p)
  }
  c.set('Name', new NbtString(s.name))
  return c
}

function intList(v: Vec3): NbtList<NbtInt> {
  return new NbtList(v.map(n => new NbtInt(n)), 3 /* TAG_Int */)
}

export interface EntityRef {
  index: number
  tag: NbtCompound
}

export class RoomTemplate {
  readonly file: NbtFile
  readonly size: Vec3
  /** True for multi-palette templates ("palettes"), which this version shows read only. */
  readonly readOnlyReason: string | null
  private readonly cells = new Map<number, Cell>()
  private readonly order: number[] = []
  private readonly orderSet = new Set<number>()
  private readonly origPalette: NbtCompound[] = []
  private readonly origPaletteStates: BlockStateLite[] = []
  private entities: NbtCompound[]
  private dirty = false

  private constructor(file: NbtFile) {
    this.file = file
    const root = file.root
    const sizeList = root.getList('size')
    this.size = [sizeList.getNumber(0), sizeList.getNumber(1), sizeList.getNumber(2)]
    this.readOnlyReason = null
    let paletteList: NbtList
    if (root.hasList('palette')) {
      paletteList = root.getList('palette')
    } else if (root.hasList('palettes')) {
      paletteList = root.getList('palettes').getList(0, 10 as never) as unknown as NbtList
      ;(this as { readOnlyReason: string | null }).readOnlyReason =
        'this template has several palettes (random variants); the editor shows the first one read only'
    } else {
      paletteList = new NbtList()
    }
    for (let i = 0; i < paletteList.length; i++) {
      const c = paletteList.getCompound(i)
      this.origPalette.push(c)
      this.origPaletteStates.push(stateFromPaletteCompound(c))
    }
    const blocks = root.getList('blocks')
    for (let i = 0; i < blocks.length; i++) {
      const b = blocks.getCompound(i)
      const p = b.getList('pos')
      const pos: Vec3 = [p.getNumber(0), p.getNumber(1), p.getNumber(2)]
      const si = b.getNumber('state')
      const base = this.origPaletteStates[si] ?? { name: 'minecraft:air', props: {} }
      const entry: BlockEntry = { state: base, nbt: b.hasCompound('nbt') ? b.getCompound('nbt') : undefined }
      const key = posKey(...pos)
      if (!this.orderSet.has(key)) { this.order.push(key); this.orderSet.add(key) }
      this.cells.set(key, { pos, entry, origEntry: entry, origCompound: b, origStateIndex: si })
    }
    const ents = root.getList('entities')
    this.entities = []
    for (let i = 0; i < ents.length; i++) this.entities.push(ents.getCompound(i))
  }

  static read(bytes: Uint8Array): RoomTemplate {
    return new RoomTemplate(NbtFile.read(bytes))
  }

  /** A blank template of the given size, filled with nothing (all positions absent). */
  static create(size: Vec3, dataVersion: number): RoomTemplate {
    const file = NbtFile.create({ compression: 'gzip' })
    const root = file.root
    root.set('size', intList(size))
    root.set('entities', new NbtList([], 10))
    root.set('blocks', new NbtList([], 10))
    root.set('palette', new NbtList([], 10))
    root.set('DataVersion', new NbtInt(dataVersion))
    const t = new RoomTemplate(file)
    t.dirty = true
    return t
  }

  get dataVersion(): number {
    return this.file.root.hasNumber('DataVersion') ? this.file.root.getNumber('DataVersion') : 0
  }

  isDirty(): boolean {
    return this.dirty
  }

  markClean(): void {
    this.dirty = false
  }

  inBounds(x: number, y: number, z: number): boolean {
    return x >= 0 && y >= 0 && z >= 0 && x < this.size[0] && y < this.size[1] && z < this.size[2]
  }

  get(x: number, y: number, z: number): BlockEntry | undefined {
    return this.cells.get(posKey(x, y, z))?.entry
  }

  /** Every stored position, air included, in file order then insertion order. */
  *entries(): Generator<{ pos: Vec3; entry: BlockEntry }> {
    for (const key of this.order) {
      const c = this.cells.get(key)
      if (c) yield { pos: c.pos, entry: c.entry }
    }
  }

  blockCount(): number {
    return this.cells.size
  }

  /**
   * Sets or removes (entry undefined) the block at a position and returns the
   * previous entry, for undo. Removing drops the position from the template
   * entirely (a structure void); the editor's "remove" tool writes air instead,
   * matching what a capture of an empty block produces.
   */
  set(x: number, y: number, z: number, entry: BlockEntry | undefined): BlockEntry | undefined {
    const key = posKey(x, y, z)
    const cell = this.cells.get(key)
    const prev = cell?.entry
    if (entry === undefined) {
      if (cell) {
        this.cells.delete(key)
        this.dirty = true
      }
      return prev
    }
    if (cell) {
      if (cell.entry !== entry) {
        cell.entry = entry
        this.dirty = true
      }
    } else {
      this.cells.set(key, { pos: [x, y, z], entry })
      if (!this.orderSet.has(key)) { this.order.push(key); this.orderSet.add(key) }
      this.dirty = true
    }
    return prev
  }

  getEntities(): readonly NbtCompound[] {
    return this.entities
  }

  /** Removes entities whose blockPos lies inside the box; returns them with their indices for undo. */
  removeEntitiesIn(min: Vec3, max: Vec3): EntityRef[] {
    const removed: EntityRef[] = []
    const kept: NbtCompound[] = []
    this.entities.forEach((e, index) => {
      const bp = e.hasList('blockPos') ? e.getList('blockPos') : null
      const p = bp ? [bp.getNumber(0), bp.getNumber(1), bp.getNumber(2)] : null
      const inside = p && p[0] >= min[0] && p[0] <= max[0] && p[1] >= min[1] && p[1] <= max[1] && p[2] >= min[2] && p[2] <= max[2]
      if (inside) removed.push({ index, tag: e })
      else kept.push(e)
    })
    if (removed.length > 0) {
      this.entities = kept
      this.dirty = true
    }
    return removed
  }

  addEntity(tag: NbtCompound): void {
    this.entities.push(tag)
    this.dirty = true
  }

  removeEntityTags(tags: NbtCompound[]): void {
    const drop = new Set(tags)
    const before = this.entities.length
    this.entities = this.entities.filter(e => !drop.has(e))
    if (this.entities.length !== before) this.dirty = true
  }

  restoreEntities(refs: EntityRef[]): void {
    const sorted = [...refs].sort((a, b) => a.index - b.index)
    for (const r of sorted) this.entities.splice(r.index, 0, r.tag)
    if (refs.length > 0) this.dirty = true
  }

  /**
   * Builds the NBT tree to write. With {@code forceRebuild} false and no edits,
   * the original root is returned as is. With it true, the block, palette and
   * entity lists are rebuilt through the same path an edited file takes, which
   * is what the round trip test exercises.
   */
  toNbtFile(forceRebuild = false): NbtFile {
    if (!this.dirty && !forceRebuild) return this.file
    if (this.readOnlyReason) throw new Error(this.readOnlyReason)

    const palette: NbtCompound[] = [...this.origPalette]
    const paletteIndex = new Map<string, number>()
    this.origPaletteStates.forEach((s, i) => {
      const k = stateKey(s)
      if (!paletteIndex.has(k)) paletteIndex.set(k, i)
    })
    const indexFor = (s: BlockStateLite): number => {
      const k = stateKey(s)
      let i = paletteIndex.get(k)
      if (i === undefined) {
        i = palette.length
        palette.push(paletteCompound(s))
        paletteIndex.set(k, i)
      }
      return i
    }

    const blocks: NbtCompound[] = []
    for (const key of this.order) {
      const c = this.cells.get(key)
      if (!c) continue
      if (c.origCompound && c.entry === c.origEntry) {
        blocks.push(c.origCompound)
        continue
      }
      const b = new NbtCompound()
      if (c.entry.nbt) b.set('nbt', c.entry.nbt)
      b.set('pos', intList(c.pos))
      b.set('state', new NbtInt(indexFor(c.entry.state)))
      blocks.push(b)
    }

    const src = this.file.root
    const root = new NbtCompound()
    const put = (k: string) => {
      switch (k) {
        case 'blocks': root.set('blocks', new NbtList(blocks, 10)); break
        case 'palette': root.set('palette', new NbtList(palette, 10)); break
        case 'entities': root.set('entities', new NbtList(this.entities, 10)); break
        default: root.set(k, src.get(k)!)
      }
    }
    for (const k of src.keys()) put(k)
    for (const k of ['size', 'entities', 'blocks', 'palette']) {
      if (!root.has(k)) put(k)
    }
    return new NbtFile(this.file.name, root, this.file.compression === 'none' ? 'gzip' : this.file.compression,
      this.file.littleEndian, this.file.bedrockHeader)
  }

  write(forceRebuild = false): Uint8Array {
    return this.toNbtFile(forceRebuild).write()
  }
}
