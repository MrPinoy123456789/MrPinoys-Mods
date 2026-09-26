import {
  CELL, EDGES, canonicalDoorSlots, doorwayLane, maskFromEdges, storyOffset, templateHeight,
  wallDirection, windowBandSlots, type Edge, type Vec3,
} from './geometry'
import { isAir, type BlockEntry, type RoomTemplate } from './template'

/**
 * Cheap client side checks. The authoritative gate is the game's own Java
 * (RoomManifest.buildEntry at load, RoomValidator at in-game save, run
 * headlessly by the validateRooms Gradle task); this mirrors those rules so an
 * author sees problems while editing, and adds a few geometry warnings the
 * game only discovers when a room is stamped.
 */

export type Severity = 'error' | 'warning' | 'info'

export interface Finding {
  severity: Severity
  /** Which rule, for grouping: manifest, roomvalidator, geometry, meta. */
  source: 'manifest' | 'roomvalidator' | 'geometry' | 'meta'
  message: string
  positions?: Vec3[]
}

export interface RoomMetaLike {
  template?: string
  roles?: string[]
  spanY?: number
  window?: string
  [k: string]: unknown
}

export interface ValidationResult {
  findings: Finding[]
  /** Door mask as the manifest derives it: every edge carrying a door jigsaw. */
  mask: number
  doorJigsaws: { pos: Vec3; facing: string }[]
}

export const DOOR_NAME = 'pocketdungeons:door'
export const SPAWN_NAME = 'pocketdungeons:spawn'

export function jigsawName(e: BlockEntry | undefined): string | null {
  if (!e || e.state.name !== 'minecraft:jigsaw' || !e.nbt) return null
  return e.nbt.hasString('name') ? e.nbt.getString('name') : ''
}

/** JigsawBlock.getFrontFacing: the first half of the orientation value. */
export function jigsawFront(e: BlockEntry): string {
  return (e.state.props.orientation ?? 'north_up').split('_')[0]
}

const CONTAINERS = /(^minecraft:(chest|trapped_chest|barrel|hopper|dispenser|dropper|decorated_pot)$)|shulker_box$|copper_chest$/

function roleIs(roles: string[] | undefined, bare: string): boolean {
  return (roles ?? []).some(r => r === bare || r === `pocketdungeons:${bare}`)
}

export function validateRoom(t: RoomTemplate, meta: RoomMetaLike | null, templateId?: string): ValidationResult {
  const findings: Finding[] = []
  const add = (severity: Severity, source: Finding['source'], message: string, positions?: Vec3[]) =>
    findings.push({ severity, source, message, positions })

  const spanY = typeof meta?.spanY === 'number' ? meta.spanY : 1
  const offset = storyOffset(spanY)
  const [sx, sy, sz] = t.size

  // ---- size and bounds -------------------------------------------------
  if (sx !== CELL || sz !== CELL) {
    add('error', 'geometry', `template footprint is ${sx} x ${sz}; a room cell is ${CELL} x ${CELL}`)
  }
  const wantY = templateHeight(spanY)
  if (sy !== wantY) {
    const impliedSpan = sy === templateHeight(2) ? 2 : sy === templateHeight(1) ? 1 : null
    add('error', 'geometry', `spanY mismatch: metadata declares spanY ${spanY} (template height ${wantY}) but the template is ${sy} tall`
      + (impliedSpan ? `, which is the height of a spanY ${impliedSpan} room` : ''))
  }

  const outside: Vec3[] = []
  const doorJigsaws: { pos: Vec3; facing: string; entry: BlockEntry }[] = []
  let doorLike = 0
  let trialSpawners = 0, vaults = 0, containers = 0, spawnJigsaws = 0
  for (const { pos, entry } of t.entries()) {
    if (!t.inBounds(...pos)) {
      if (!isAir(entry.state)) outside.push(pos)
      continue
    }
    const name = jigsawName(entry)
    if (name !== null) {
      if (name === DOOR_NAME) doorJigsaws.push({ pos, facing: jigsawFront(entry), entry })
      if (name.includes('door')) doorLike++
      if (name === SPAWN_NAME) spawnJigsaws++
    }
    const id = entry.state.name
    if (id === 'minecraft:trial_spawner') trialSpawners++
    else if (id === 'minecraft:vault') vaults++
    else if (CONTAINERS.test(id)) containers++
  }
  if (outside.length > 0) {
    add('error', 'geometry', `${outside.length} block(s) sit outside the ${sx} x ${sy} x ${sz} template box`, outside)
  }

  // ---- RoomManifest.buildEntry (load time gate) ------------------------
  const edges = new Set<Edge>()
  const byPos = new Map<string, { facing: string }>()
  for (const d of doorJigsaws) {
    const edge = wallDirection(d.pos[0], d.pos[2])
    if (!edge) {
      add('error', 'manifest', `door jigsaw at ${d.pos.join(', ')} is not on a cell edge`, [d.pos])
      continue
    }
    if (d.facing !== edge) {
      add('error', 'manifest', `door jigsaw at ${d.pos.join(', ')} faces ${d.facing} but sits on ${edge} wall`, [d.pos])
    }
    edges.add(edge)
    byPos.set(d.pos.join(','), { facing: d.facing })
  }
  for (const edge of EDGES) {
    if (!edges.has(edge)) continue
    for (const slot of canonicalDoorSlots(edge, offset)) {
      const info = byPos.get(slot.join(','))
      if (!info) {
        add('error', 'manifest', `partial door on ${edge} wall: missing jigsaw at ${slot.join(', ')}`, [slot])
      } else if (info.facing !== edge) {
        add('error', 'manifest', `partial door on ${edge} wall: jigsaw at ${slot.join(', ')} faces ${info.facing}`, [slot])
      }
    }
  }
  const mask = maskFromEdges(edges)

  // ---- RoomValidator (in-game save gate) -------------------------------
  const roles = meta?.roles
  if (meta) {
    if (!roles || roles.length === 0) add('error', 'roomvalidator', 'No roles selected. Set at least one.')
    if (doorLike === 0 && !roleIs(roles, 'entrance')) {
      add('error', 'roomvalidator', 'Room has no door jigsaws. It will be unreachable in a dungeon. Place door jigsaws on at least one wall, or set the role to entrance.')
    }
    if (roleIs(roles, 'encounter') && trialSpawners === 0 && spawnJigsaws === 0) {
      add('warning', 'roomvalidator', 'Encounter room has no trial spawner or spawn anchor. Stamp will skip the encounter.')
    }
    if (roleIs(roles, 'loot') && vaults === 0 && containers === 0) {
      add('warning', 'roomvalidator', 'Loot room has no vault or chest. Stamp will skip the loot.')
    }
  }
  if (trialSpawners > 1) add('info', 'roomvalidator', `Room has ${trialSpawners} trial spawners. All count toward the completion gate.`)
  if (vaults > 1) add('info', 'roomvalidator', `Room has ${vaults} vaults. All use the same tiered loot table.`)
  const entityCount = t.getEntities().length
  if (entityCount > 50) add('warning', 'roomvalidator', `Room has ${entityCount} entities. High entity counts may cause lag when stamped.`)

  // ---- geometry the stamper relies on ----------------------------------
  for (const edge of edges) {
    const blocked: Vec3[] = []
    for (const p of doorwayLane(edge, offset)) {
      const e = t.get(...p)
      if (e && !isAir(e.state)) blocked.push(p)
    }
    if (blocked.length > 0) {
      add('warning', 'geometry', `${edge} doorway lane has ${blocked.length} non-air block(s) within three blocks of the wall`, blocked)
    }
    if ((meta?.window ?? 'bars') !== 'none') {
      const band = windowBandSlots(edge, offset)
      const odd = band.filter(p => {
        const e = t.get(...p)
        return e && jigsawName(e) === DOOR_NAME
      })
      if (odd.length > 0) add('warning', 'geometry', `${edge} window band overlaps a door jigsaw`, odd)
    }
  }
  if (spanY > 1 && sy === wantY) {
    const low = doorJigsaws.filter(d => d.pos[1] < offset)
    if (low.length > 0) {
      add('error', 'geometry', `${low.length} door jigsaw(s) on the lower story; a lower story is private interior with no doorways`, low.map(d => d.pos))
    }
  }

  // ---- metadata cross checks -------------------------------------------
  if (meta && templateId && meta.template && meta.template !== templateId) {
    add('warning', 'meta', `metadata template is ${meta.template} but this file is ${templateId}`)
  }

  return { findings, mask, doorJigsaws: doorJigsaws.map(d => ({ pos: d.pos, facing: d.facing })) }
}
