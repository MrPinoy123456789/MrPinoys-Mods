/**
 * The authored cell geometry contract, mirrored from the mod's
 * RoomGeometry.java, DoorMask.java, RoomManifest.canonicalDoorSlots and
 * TemplateStamper.java. If any of those change, change this file with them.
 */

export const CELL = 16
export const WALL_HEIGHT = 5
export const CEILING_Y = WALL_HEIGHT + 1
export const DOOR_MIN = 7
export const DOOR_MAX = 8
export const DOOR_HEIGHT = 3
/** Floor to floor pitch between stories: interior plus one shared bedrock layer. */
export const STORY_HEIGHT = CEILING_Y + 2
export const MAX_SPAN_Y = 2
export const WINDOW_MIN = DOOR_MIN - 1
export const WINDOW_MAX = DOOR_MAX + 1
export const WINDOW_Y = 2

/** Blocks a spanY room's capture origin sits below its own cell origin. */
export function storyOffset(spanY: number): number {
  return (spanY - 1) * STORY_HEIGHT
}

/** Template y extent for a room of this span: 7 for one story, 15 for two. */
export function templateHeight(spanY: number): number {
  return CEILING_Y + 1 + storyOffset(Math.max(1, spanY))
}

export type Edge = 'north' | 'east' | 'south' | 'west'
export const EDGES: Edge[] = ['north', 'east', 'south', 'west']

export const MASK_BIT: Record<Edge, number> = { north: 0x1, east: 0x2, south: 0x4, west: 0x8 }

export function maskFromEdges(edges: Iterable<Edge>): number {
  let m = 0
  for (const e of edges) m |= MASK_BIT[e]
  return m
}

export function edgesFromMask(mask: number): Edge[] {
  return EDGES.filter(e => (mask & MASK_BIT[e]) !== 0)
}

/** DoorMask.rotateClockwise: a clockwise quarter turn is a cyclic left rotate. */
export function rotateMaskClockwise(mask: number, quarterTurns: number): number {
  const q = ((quarterTurns % 4) + 4) % 4
  if (q === 0) return mask & 0xf
  return ((mask << q) | (mask >>> (4 - q))) & 0xf
}

export function maskLetters(mask: number): string {
  let s = ''
  if (mask & 1) s += 'N'
  if (mask & 2) s += 'E'
  if (mask & 4) s += 'S'
  if (mask & 8) s += 'W'
  return s || 'none'
}

/** RoomGeometry.wallDirection: which wall a boundary coordinate sits on. */
export function wallDirection(x: number, z: number): Edge | null {
  if (z === 0) return 'north'
  if (z === CELL - 1) return 'south'
  if (x === 0) return 'west'
  if (x === CELL - 1) return 'east'
  return null
}

export type Vec3 = [number, number, number]

/** RoomManifest.canonicalDoorSlots: the six jigsaw positions of one doorway. */
export function canonicalDoorSlots(edge: Edge, doorYOffset: number): Vec3[] {
  const out: Vec3[] = []
  const far = CELL - 1
  for (let y = 1; y <= DOOR_HEIGHT; y++) {
    const yy = y + doorYOffset
    for (let i = DOOR_MIN; i <= DOOR_MAX; i++) {
      switch (edge) {
        case 'north': out.push([i, yy, 0]); break
        case 'south': out.push([i, yy, far]); break
        case 'west': out.push([0, yy, i]); break
        case 'east': out.push([far, yy, i]); break
      }
    }
  }
  return out
}

/**
 * The window band's outer columns on one wall (RoomBuilder.windowBand): the
 * middle two columns are the doorway's own, so only WINDOW_MIN and WINDOW_MAX
 * are cut, at eye height.
 */
export function windowBandSlots(edge: Edge, doorYOffset: number): Vec3[] {
  const y = WINDOW_Y + doorYOffset
  const far = CELL - 1
  const out: Vec3[] = []
  for (const i of [WINDOW_MIN, WINDOW_MAX]) {
    switch (edge) {
      case 'north': out.push([i, y, 0]); break
      case 'south': out.push([i, y, far]); break
      case 'west': out.push([0, y, i]); break
      case 'east': out.push([far, y, i]); break
    }
  }
  return out
}

/**
 * The doorway lane (RoomTemplateGenerator class note): interior positions a
 * doorway needs clear, x or z in [7,8] for the three blocks inside the wall.
 */
export function doorwayLane(edge: Edge, doorYOffset: number): Vec3[] {
  const out: Vec3[] = []
  for (let y = 1; y <= DOOR_HEIGHT; y++) {
    const yy = y + doorYOffset
    for (let i = DOOR_MIN; i <= DOOR_MAX; i++) {
      for (let d = 1; d <= 3; d++) {
        switch (edge) {
          case 'north': out.push([i, yy, d]); break
          case 'south': out.push([i, yy, CELL - 1 - d]); break
          case 'west': out.push([d, yy, i]); break
          case 'east': out.push([CELL - 1 - d, yy, i]); break
        }
      }
    }
  }
  return out
}

/** Jigsaw orientation whose front faces this wall (RoomTemplateGenerator.orientationFor). */
export function doorOrientation(edge: Edge): string {
  return `${edge}_up`
}

/**
 * TemplateStamper's placement mapping per clockwise quarter turn:
 * NONE (x,z), CW90 (15-z, x), CW180 (15-x, 15-z), CCW90 (z, 15-x).
 */
export function rotatePosInCell(x: number, z: number, quarterTurns: number): [number, number] {
  const q = ((quarterTurns % 4) + 4) % 4
  const f = CELL - 1
  switch (q) {
    case 1: return [f - z, x]
    case 2: return [f - x, f - z]
    case 3: return [z, f - x]
    default: return [x, z]
  }
}
