import type { BlockStateLite } from './template'

/**
 * Block state rotation and mirroring for the properties vanilla rotates.
 * Mirrors the per-block rotate/mirror overrides closely enough for authoring:
 * horizontal facing, axis, the 16 step rotation of signs, banners and skulls,
 * the four side booleans of fences, walls, panes and vines, rail shapes, jigsaw
 * orientation, and the left/right swaps vanilla applies to stairs, doors and
 * chests when mirrored.
 */

const H = ['north', 'east', 'south', 'west'] as const
type HDir = (typeof H)[number]

function isH(d: string): d is HDir {
  return (H as readonly string[]).includes(d)
}

export function rotateDir(d: string, q: number): string {
  if (!isH(d)) return d
  return H[(H.indexOf(d) + q + 400) % 4]
}

/** 'x' flips east and west (mirror across the x axis of travel), 'z' flips north and south. */
export type MirrorAxis = 'x' | 'z'

export function mirrorDir(d: string, axis: MirrorAxis): string {
  if (axis === 'z') {
    if (d === 'north') return 'south'
    if (d === 'south') return 'north'
  } else {
    if (d === 'east') return 'west'
    if (d === 'west') return 'east'
  }
  return d
}

const RAIL_ROT: Record<string, string> = {
  north_south: 'east_west', east_west: 'north_south',
  ascending_north: 'ascending_east', ascending_east: 'ascending_south',
  ascending_south: 'ascending_west', ascending_west: 'ascending_north',
  south_east: 'south_west', south_west: 'north_west', north_west: 'north_east', north_east: 'south_east',
}

function rotateRail(shape: string, q: number): string {
  let s = shape
  for (let i = 0; i < q; i++) s = RAIL_ROT[s] ?? s
  return s
}

function mirrorRail(shape: string, axis: MirrorAxis): string {
  if (shape.startsWith('ascending_')) return 'ascending_' + mirrorDir(shape.slice(10), axis)
  const parts = shape.split('_')
  if (parts.length === 2 && isH(parts[0]) && isH(parts[1])) {
    const a = mirrorDir(parts[0], axis)
    const b = mirrorDir(parts[1], axis)
    const ordered = [a, b].sort((m, n) => (m === 'north' || m === 'south' ? -1 : 1) - (n === 'north' || n === 'south' ? -1 : 1))
    const joined = ordered.join('_')
    return RAIL_ROT[joined] !== undefined ? joined : shape
  }
  return shape
}

function swapLeftRight(v: string): string {
  if (v.endsWith('_left')) return v.slice(0, -5) + '_right'
  if (v.endsWith('_right')) return v.slice(0, -6) + '_left'
  if (v === 'left') return 'right'
  if (v === 'right') return 'left'
  return v
}

export function rotateState(s: BlockStateLite, quarterTurns: number): BlockStateLite {
  const q = ((quarterTurns % 4) + 4) % 4
  if (q === 0) return s
  const p = s.props
  const out: Record<string, string> = {}
  const isRail = s.name.endsWith('rail')
  for (const k of Object.keys(p)) {
    const v = p[k]
    if (k === 'facing' || k === 'horizontal_facing') out[k] = rotateDir(v, q)
    else if (k === 'axis') out[k] = q % 2 === 1 ? (v === 'x' ? 'z' : v === 'z' ? 'x' : v) : v
    else if (k === 'rotation' && /^\d+$/.test(v)) out[k] = String((Number(v) + 4 * q) % 16)
    else if (k === 'shape' && isRail) out[k] = rotateRail(v, q)
    else if (k === 'orientation') out[k] = v.split('_').map(d => rotateDir(d, q)).join('_')
    else out[k] = v
  }
  // Side-keyed properties move with the rotation.
  const sides = H.filter(d => d in p)
  if (sides.length > 0) {
    for (const d of sides) delete out[d]
    for (const d of H) {
      if (d in p) out[rotateDir(d, q)] = p[d]
    }
  }
  return { name: s.name, props: orderLike(p, out) }
}

export function mirrorState(s: BlockStateLite, axis: MirrorAxis): BlockStateLite {
  const p = s.props
  const out: Record<string, string> = { ...p }
  const isRail = s.name.endsWith('rail')
  const isStairs = s.name.endsWith('_stairs')
  const isDoor = s.name.endsWith('_door') && 'hinge' in p
  const isChest = /(^|:|_)chest$/.test(s.name) && 'type' in p
  let facingChanged = false
  for (const k of Object.keys(p)) {
    const v = p[k]
    if (k === 'facing' || k === 'horizontal_facing') {
      out[k] = mirrorDir(v, axis)
      facingChanged = out[k] !== v
    } else if (k === 'rotation' && /^\d+$/.test(v)) {
      const r = Number(v)
      out[k] = String(axis === 'z' ? (16 - r) % 16 : (8 - r + 16) % 16)
    } else if (k === 'shape' && isRail) {
      out[k] = mirrorRail(v, axis)
    } else if (k === 'orientation') {
      out[k] = v.split('_').map(d => mirrorDir(d, axis)).join('_')
    }
  }
  if (isStairs && facingChanged && 'shape' in p) out.shape = swapLeftRight(p.shape)
  if (isDoor) out.hinge = swapLeftRight(p.hinge)
  if (isChest && p.type !== 'single') out.type = swapLeftRight(p.type)
  const sides = H.filter(d => d in p)
  if (sides.length > 0) {
    for (const d of sides) delete out[d]
    for (const d of H) {
      if (d in p) out[mirrorDir(d, axis)] = p[d]
    }
  }
  return { name: s.name, props: orderLike(p, out) }
}

/** Keeps the original property order so a rotate then rotate back is byte stable. */
function orderLike(orig: Record<string, string>, next: Record<string, string>): Record<string, string> {
  const res: Record<string, string> = {}
  for (const k of Object.keys(orig)) if (k in next) res[k] = next[k]
  for (const k of Object.keys(next)) if (!(k in res)) res[k] = next[k]
  return res
}
