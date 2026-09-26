import { BlockState, type StructureProvider } from 'deepslate/core'
import { StructureRenderer } from 'deepslate/render'
import { mat4, vec3, vec4 } from 'gl-matrix'
import { rotatePosInCell, type Vec3 } from '../core/geometry'
import type { RoomResources } from '../core/resources'
import { isAir, stateKey, type BlockEntry, type BlockStateLite, type RoomTemplate } from '../core/template'
import { rotateState } from '../core/transform'
import { OverlayBatch, OverlayRenderer } from './overlay'

/**
 * What deepslate renders: the template seen through the layer slice and the
 * rotation preview. Positions and states are transformed here so the renderer
 * and the picker agree on one coordinate space.
 */
export class TemplateView implements StructureProvider {
  maxY = Infinity
  rotation = 0
  private cache: { pos: Vec3; state: BlockState; entry: BlockEntry }[] | null = null
  private index = new Map<string, { pos: Vec3; state: BlockState; entry: BlockEntry }>()
  private readonly stateCache = new Map<string, BlockStateLite>()

  constructor(public template: RoomTemplate) {}

  invalidate(): void {
    this.cache = null
  }

  getSize(): Vec3 {
    return [...this.template.size] as Vec3
  }

  private build(): void {
    const list: { pos: Vec3; state: BlockState; entry: BlockEntry }[] = []
    this.index.clear()
    for (const { pos, entry } of this.template.entries()) {
      if (isAir(entry.state) || pos[1] > this.maxY) continue
      if (!this.template.inBounds(...pos)) continue
      let p = pos
      let s = entry.state
      if (this.rotation !== 0) {
        const [x, z] = rotatePosInCell(pos[0], pos[2], this.rotation)
        p = [x, pos[1], z]
        const k = `${this.rotation}|${stateKey(s)}`
        let rs = this.stateCache.get(k)
        if (!rs) { rs = rotateState(s, this.rotation); this.stateCache.set(k, rs) }
        s = rs
      }
      const item = { pos: p, state: new BlockState(s.name, { ...s.props }), entry }
      list.push(item)
      this.index.set(p.join(','), item)
    }
    this.cache = list
  }

  getBlocks() {
    if (!this.cache) this.build()
    return this.cache!.map(b => ({ pos: b.pos, state: b.state, nbt: b.entry.nbt }))
  }

  getBlock(pos: Vec3) {
    if (!this.cache) this.build()
    const b = this.index.get(`${pos[0]},${pos[1]},${pos[2]}`)
    return b ? { pos: b.pos, state: b.state, nbt: b.entry.nbt } : null
  }

  /** View space to template space (undoes the preview rotation). */
  toTemplate(p: Vec3): Vec3 {
    if (this.rotation === 0) return p
    const [x, z] = rotatePosInCell(p[0], p[2], 4 - this.rotation)
    return [x, p[1], z]
  }

  isSolid(x: number, y: number, z: number): boolean {
    if (!this.cache) this.build()
    return this.index.has(`${x},${y},${z}`)
  }
}

export interface PickResult {
  /** The block the ray hit, in view space. */
  hit: Vec3 | null
  /** Face normal of the hit. */
  normal: Vec3 | null
  /** Where a placed block would go (hit plus normal, or the floor cell under the cursor). */
  place: Vec3 | null
}

export class Viewer {
  readonly gl: WebGLRenderingContext
  private renderer: StructureRenderer | null = null
  private readonly overlayRenderer: OverlayRenderer
  readonly overlay = new OverlayBatch()
  readonly overlayOnTop = new OverlayBatch()
  view: TemplateView | null = null
  private resources: RoomResources | null = null
  yaw = Math.PI * 0.8
  pitch = 0.7
  dist = 30
  target: Vec3 = [8, 3, 8]
  private needsDraw = true
  private raf = 0
  onBeforeDraw: (() => void) | null = null
  showGrid = true

  constructor(readonly canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl', { preserveDrawingBuffer: true, antialias: true, alpha: false })
    if (!gl) throw new Error('WebGL is not available in this browser')
    this.gl = gl
    this.overlayRenderer = new OverlayRenderer(gl)
    new ResizeObserver(() => this.resize()).observe(canvas)
    this.loop()
  }

  setResources(res: RoomResources): void {
    this.resources = res
    if (this.view) this.setView(this.view)
  }

  setView(view: TemplateView): void {
    this.view = view
    if (!this.resources) return
    if (!this.renderer) {
      this.renderer = new StructureRenderer(this.gl, view, this.resources, { chunkSize: 8, useInvisibleBlockBuffer: false })
      this.resize()
    } else {
      this.renderer.setStructure(view)
    }
    this.requestDraw()
  }

  frame(template: RoomTemplate): void {
    const [sx, sy, sz] = template.size
    this.target = [sx / 2, sy / 2, sz / 2]
    this.dist = Math.max(sx, sy, sz) * 1.9
  }

  /** Rebuilds render buffers: all of them, or only the chunks holding the given view positions. */
  refresh(positions?: Vec3[]): void {
    if (!this.renderer || !this.view) return
    this.view.invalidate()
    if (positions && positions.length > 0 && positions.length < 64) {
      const chunks = new Map<string, Vec3>()
      for (const p of positions) {
        for (const d of [[0, 0, 0], [1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]) {
          const c: Vec3 = [Math.floor((p[0] + d[0]) / 8), Math.floor((p[1] + d[1]) / 8), Math.floor((p[2] + d[2]) / 8)]
          chunks.set(c.join(','), c)
        }
      }
      this.renderer.updateStructureBuffers([...chunks.values()])
    } else {
      this.renderer.updateStructureBuffers()
    }
    this.requestDraw()
  }

  requestDraw(): void {
    this.needsDraw = true
  }

  private resize(): void {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const w = Math.max(1, Math.floor(this.canvas.clientWidth * dpr))
    const h = Math.max(1, Math.floor(this.canvas.clientHeight * dpr))
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w
      this.canvas.height = h
    }
    this.renderer?.setViewport(0, 0, w, h)
    this.requestDraw()
  }

  private loop = () => {
    this.raf = requestAnimationFrame(this.loop)
    if (!this.needsDraw) return
    this.needsDraw = false
    this.draw()
  }

  destroy(): void {
    cancelAnimationFrame(this.raf)
  }

  eye(): Vec3 {
    const cp = Math.cos(this.pitch)
    return [
      this.target[0] + this.dist * cp * Math.sin(this.yaw),
      this.target[1] + this.dist * Math.sin(this.pitch),
      this.target[2] + this.dist * cp * Math.cos(this.yaw),
    ]
  }

  viewMatrix(): mat4 {
    const m = mat4.create()
    mat4.lookAt(m, this.eye(), this.target, [0, 1, 0])
    return m
  }

  projMatrix(): mat4 {
    const m = mat4.create()
    mat4.perspective(m, (70 * Math.PI) / 180, this.canvas.clientWidth / Math.max(1, this.canvas.clientHeight), 0.1, 500)
    return m
  }

  draw(): void {
    const gl = this.gl
    gl.clearColor(0.13, 0.14, 0.17, 1)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    this.onBeforeDraw?.()
    const view = this.viewMatrix()
    if (this.renderer) {
      gl.enable(gl.DEPTH_TEST)
      gl.enable(gl.CULL_FACE)
      this.renderer.drawStructure(view)
      if (this.showGrid) this.renderer.drawGrid(view)
    }
    const proj = this.projMatrix()
    this.overlayRenderer.draw(this.overlay, view, proj, true)
    this.overlayRenderer.draw(this.overlayOnTop, view, proj, false)
  }

  // ---- camera controls ---------------------------------------------------

  orbit(dx: number, dy: number): void {
    this.yaw -= dx * 0.008
    this.pitch = Math.max(-1.5, Math.min(1.5, this.pitch + dy * 0.008))
    this.requestDraw()
  }

  pan(dx: number, dy: number): void {
    const v = this.viewMatrix()
    const right: Vec3 = [v[0], v[4], v[8]]
    const up: Vec3 = [v[1], v[5], v[9]]
    const s = this.dist * 0.0018
    for (let i = 0; i < 3; i++) this.target[i] += (-dx * right[i] + dy * up[i]) * s
    this.requestDraw()
  }

  zoom(delta: number): void {
    this.dist = Math.max(4, Math.min(120, this.dist * Math.exp(delta * 0.001)))
    this.requestDraw()
  }

  // ---- picking -------------------------------------------------------------

  ray(clientX: number, clientY: number): { origin: Vec3; dir: Vec3 } {
    const rect = this.canvas.getBoundingClientRect()
    const nx = ((clientX - rect.left) / rect.width) * 2 - 1
    const ny = 1 - ((clientY - rect.top) / rect.height) * 2
    const inv = mat4.create()
    mat4.multiply(inv, this.projMatrix(), this.viewMatrix())
    mat4.invert(inv, inv)
    const a = vec4.transformMat4(vec4.create(), [nx, ny, -1, 1], inv)
    const b = vec4.transformMat4(vec4.create(), [nx, ny, 1, 1], inv)
    const o: Vec3 = [a[0] / a[3], a[1] / a[3], a[2] / a[3]]
    const f: Vec3 = [b[0] / b[3], b[1] / b[3], b[2] / b[3]]
    const d = vec3.normalize(vec3.create(), [f[0] - o[0], f[1] - o[1], f[2] - o[2]])
    return { origin: o, dir: [d[0], d[1], d[2]] }
  }

  pick(clientX: number, clientY: number, floorY = 0): PickResult {
    const none: PickResult = { hit: null, normal: null, place: null }
    if (!this.view) return none
    const { origin, dir } = this.ray(clientX, clientY)
    const size = this.view.getSize()
    const hit = voxelRaycast(origin, dir, size, (x, y, z) => this.view!.isSolid(x, y, z))
    if (hit) {
      const place: Vec3 = [hit.pos[0] + hit.normal[0], hit.pos[1] + hit.normal[1], hit.pos[2] + hit.normal[2]]
      return { hit: hit.pos, normal: hit.normal, place }
    }
    // Nothing hit: land on the floor plane of the current slice.
    if (Math.abs(dir[1]) > 1e-6) {
      const t = (floorY - origin[1]) / dir[1]
      if (t > 0) {
        const x = Math.floor(origin[0] + dir[0] * t)
        const z = Math.floor(origin[2] + dir[2] * t)
        if (x >= 0 && z >= 0 && x < size[0] && z < size[2]) {
          return { hit: null, normal: [0, 1, 0], place: [x, floorY, z] }
        }
      }
    }
    return none
  }

  screenshot(): string {
    this.draw()
    return this.canvas.toDataURL('image/png')
  }
}

/** Amanatides and Woo traversal through the template box. */
export function voxelRaycast(origin: Vec3, dir: Vec3, size: Vec3, solid: (x: number, y: number, z: number) => boolean):
  { pos: Vec3; normal: Vec3 } | null {
  let tmin = 0, tmax = 1e9
  let entryAxis = -1
  for (let i = 0; i < 3; i++) {
    if (Math.abs(dir[i]) < 1e-9) {
      if (origin[i] < 0 || origin[i] > size[i]) return null
      continue
    }
    let t1 = (0 - origin[i]) / dir[i]
    let t2 = (size[i] - origin[i]) / dir[i]
    if (t1 > t2) [t1, t2] = [t2, t1]
    if (t1 > tmin) { tmin = t1; entryAxis = i }
    tmax = Math.min(tmax, t2)
    if (tmin > tmax) return null
  }
  const eps = 1e-6
  const p: Vec3 = [origin[0] + dir[0] * (tmin + eps), origin[1] + dir[1] * (tmin + eps), origin[2] + dir[2] * (tmin + eps)]
  const cell: Vec3 = [Math.floor(p[0]), Math.floor(p[1]), Math.floor(p[2])]
  for (let i = 0; i < 3; i++) cell[i] = Math.max(0, Math.min(size[i] - 1, cell[i]))
  const step: Vec3 = [Math.sign(dir[0]) as number, Math.sign(dir[1]) as number, Math.sign(dir[2]) as number]
  const tDelta: Vec3 = [0, 0, 0]
  const tNext: Vec3 = [0, 0, 0]
  for (let i = 0; i < 3; i++) {
    if (step[i] === 0) { tDelta[i] = Infinity; tNext[i] = Infinity; continue }
    tDelta[i] = Math.abs(1 / dir[i])
    const boundary = step[i] > 0 ? cell[i] + 1 : cell[i]
    tNext[i] = tmin + (boundary - p[i]) / dir[i]
  }
  let normal: Vec3 = [0, 0, 0]
  if (entryAxis >= 0) normal[entryAxis] = -step[entryAxis]
  for (let guard = 0; guard < 512; guard++) {
    if (cell[0] < 0 || cell[1] < 0 || cell[2] < 0 || cell[0] >= size[0] || cell[1] >= size[1] || cell[2] >= size[2]) return null
    if (solid(cell[0], cell[1], cell[2])) return { pos: [...cell] as Vec3, normal }
    const axis = tNext[0] < tNext[1] ? (tNext[0] < tNext[2] ? 0 : 2) : (tNext[1] < tNext[2] ? 1 : 2)
    cell[axis] += step[axis]
    tNext[axis] += tDelta[axis]
    normal = [0, 0, 0]
    normal[axis] = -step[axis]
  }
  return null
}
