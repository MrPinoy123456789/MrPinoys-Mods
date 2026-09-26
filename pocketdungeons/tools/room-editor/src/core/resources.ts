import { Identifier } from 'deepslate/core'
import {
  BlockDefinition, BlockModel, TextureAtlas,
  type BlockFlags, type Resources, type UV,
} from 'deepslate/render'
import type { RawAssets } from './jar'
import { decodePng } from './png'

/**
 * Turns the raw jar assets into what deepslate's StructureRenderer consumes:
 * block definitions, flattened block models, one texture atlas, and the
 * per-block flags and property lists deepslate normally gets from misode's
 * generated data (derived here from the jar itself instead).
 */

export interface AtlasImage {
  width: number
  height: number
  data: Uint8ClampedArray
}

export interface TextureStats {
  /** Some pixel is fully transparent (cutout). */
  cutout: boolean
  /** Some pixel is partly transparent (translucent). */
  translucent: boolean
}

export interface BuildReport {
  blockstates: number
  definitionsOk: number
  definitionsFailed: string[]
  models: number
  modelsFlattened: number
  modelsFailed: string[]
  /** Model elements whose rotation used a form deepslate does not understand. */
  modelsPatched: string[]
  textures: number
  texturesDecoded: number
  texturesFailed: string[]
  atlasSize: number
}

const TILE = 16

function upperPow2(n: number): number {
  let p = 1
  while (p < n) p *= 2
  return p
}

/** First animation frame, scaled to one 16 x 16 tile by box averaging. */
function toTile(w: number, h: number, src: Uint8Array): Uint8Array {
  const frame = Math.min(w, h)
  const out = new Uint8Array(TILE * TILE * 4)
  const f = frame / TILE
  for (let ty = 0; ty < TILE; ty++) {
    for (let tx = 0; tx < TILE; tx++) {
      let r = 0, g = 0, b = 0, a = 0, n = 0
      const x0 = Math.floor(tx * f), x1 = Math.max(x0 + 1, Math.floor((tx + 1) * f))
      const y0 = Math.floor(ty * f), y1 = Math.max(y0 + 1, Math.floor((ty + 1) * f))
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) {
          const o = (y * w + x) * 4
          const al = src[o + 3]
          r += src[o] * al; g += src[o + 1] * al; b += src[o + 2] * al; a += al; n++
        }
      }
      const o = (ty * TILE + tx) * 4
      if (a > 0) {
        out[o] = Math.round(r / a); out[o + 1] = Math.round(g / a); out[o + 2] = Math.round(b / a)
      }
      out[o + 3] = Math.round(a / n)
    }
  }
  return out
}

export function buildAtlas(textures: Record<string, Uint8Array>, report?: BuildReport): {
  image: AtlasImage
  uvs: Record<string, UV>
  stats: Record<string, TextureStats>
} {
  const tiles: [string, Uint8Array][] = []
  const stats: Record<string, TextureStats> = {}
  for (const [id, bytes] of Object.entries(textures)) {
    try {
      const img = decodePng(bytes)
      const tile = toTile(img.width, img.height, img.data)
      let cutout = false, translucent = false
      for (let i = 3; i < tile.length; i += 4) {
        if (tile[i] === 0) cutout = true
        else if (tile[i] < 250) translucent = true
      }
      stats[id] = { cutout, translucent }
      tiles.push([id, tile])
      if (report) report.texturesDecoded++
    } catch (e) {
      report?.texturesFailed.push(`${id}: ${(e as Error).message}`)
    }
  }
  const perRow = upperPow2(Math.ceil(Math.sqrt(tiles.length + 1)))
  const px = perRow * TILE
  const data = new Uint8ClampedArray(px * px * 4)
  // Tile 0 is the missing texture: magenta and black checks.
  for (let y = 0; y < TILE; y++) {
    for (let x = 0; x < TILE; x++) {
      const o = (y * px + x) * 4
      const m = (x < 8) === (y < 8)
      data[o] = m ? 248 : 0; data[o + 1] = 0; data[o + 2] = m ? 248 : 0; data[o + 3] = 255
    }
  }
  const uvs: Record<string, UV> = {}
  const part = 1 / perRow
  tiles.forEach(([id, tile], i) => {
    const index = i + 1
    const u = index % perRow, v = Math.floor(index / perRow)
    for (let y = 0; y < TILE; y++) {
      const dst = ((v * TILE + y) * px + u * TILE) * 4
      data.set(tile.subarray(y * TILE * 4, (y + 1) * TILE * 4), dst)
    }
    uvs[id] = [part * u, part * v, part * u + part, part * v + part]
  })
  if (report) report.atlasSize = px
  return { image: { width: px, height: px, data }, uvs, stats }
}

/**
 * 26.x lets an element rotate about several axes at once
 * ({"x":..,"y":..,"z":..}); deepslate only knows the single axis form. A
 * rotation with one non zero axis converts exactly; anything else is dropped
 * (the element draws unrotated) and reported.
 */
function patchModel(id: string, json: Record<string, unknown>, report?: BuildReport): Record<string, unknown> {
  const elements = json.elements as Array<Record<string, unknown>> | undefined
  if (!Array.isArray(elements)) return json
  let patched = false
  const fixed = elements.map(e => {
    const r = e.rotation as Record<string, unknown> | undefined
    if (!r || 'axis' in r) return e
    patched = true
    const axes = (['x', 'y', 'z'] as const).filter(a => typeof r[a] === 'number' && r[a] !== 0)
    if (axes.length === 1) {
      return { ...e, rotation: { origin: r.origin ?? [8, 8, 8], axis: axes[0], angle: r[axes[0]], rescale: r.rescale } }
    }
    const { rotation: _drop, ...rest } = e
    void _drop
    return rest
  })
  if (patched) report?.modelsPatched.push(id)
  return { ...json, elements: fixed }
}

type Props = Record<string, string>

function parseVariantKey(key: string): Props {
  const props: Props = {}
  if (!key) return props
  for (const part of key.split(',')) {
    const [k, v] = part.split('=')
    if (k && v !== undefined) props[k] = v
  }
  return props
}

function collectWhen(cond: unknown, into: Map<string, Set<string>>): void {
  if (!cond || typeof cond !== 'object') return
  for (const [k, v] of Object.entries(cond as Record<string, unknown>)) {
    if (k === 'OR' || k === 'AND') {
      if (Array.isArray(v)) v.forEach(c => collectWhen(c, into))
    } else if (typeof v === 'string' || typeof v === 'boolean' || typeof v === 'number') {
      const set = into.get(k) ?? new Set<string>()
      String(v).replace(/^!/, '').split('|').forEach(x => set.add(x))
      into.set(k, set)
    }
  }
}

const PREFERRED_DEFAULT: Record<string, string[]> = {
  facing: ['north', 'down'],
  half: ['bottom', 'lower'],
  type: ['bottom', 'single'],
  shape: ['straight', 'north_south'],
  axis: ['y'],
  face: ['wall'],
  hinge: ['left'],
  attachment: ['floor'],
  orientation: ['north_up'],
  part: ['foot'],
}

function pickDefault(key: string, values: string[]): string {
  if (values.includes('false') && values.includes('true')) return 'false'
  if (['north', 'east', 'south', 'west'].includes(key) && values.includes('none')) return 'none'
  for (const pref of PREFERRED_DEFAULT[key] ?? []) if (values.includes(pref)) return pref
  if (values.every(v => /^\d+$/.test(v))) {
    const nums = values.map(Number).sort((a, b) => a - b)
    return String(key === 'distance' ? nums[nums.length - 1] : nums[0])
  }
  return values[0]
}

const SEMI_TRANSPARENT = /(stained_glass|^minecraft:ice$|slime_block|honey_block|^minecraft:water$|frosted_ice|nether_portal|tinted_glass)/
const SELF_CULLING = /(glass|^minecraft:ice$|frosted_ice)/
const NEVER_OPAQUE = /(glass|leaves|ice$|slime|honey|spawner|barrier|structure_void|light$|mangrove_roots|scaffolding)/

export class RoomResources implements Resources {
  readonly report: BuildReport
  readonly blockIds: string[]
  readonly dataVersion: number | null
  readonly versionId: string | null
  private readonly definitions = new Map<string, BlockDefinition>()
  private readonly models = new Map<string, BlockModel>()
  private readonly atlas: TextureAtlas
  private readonly atlasImage: AtlasImage
  private readonly uvs: Record<string, UV>
  private readonly textureStats: Record<string, TextureStats>
  private readonly properties = new Map<string, Record<string, string[]>>()
  private readonly defaults = new Map<string, Props>()
  private readonly flags = new Map<string, BlockFlags>()

  /**
   * @param makeImage wraps raw pixels into whatever the renderer needs
   *        (an ImageData in the browser, a plain object in Node).
   */
  constructor(raw: RawAssets, makeImage: (img: AtlasImage) => ImageData) {
    this.versionId = raw.versionId
    this.dataVersion = raw.dataVersion
    const report: BuildReport = {
      blockstates: Object.keys(raw.blockstates).length, definitionsOk: 0, definitionsFailed: [],
      models: Object.keys(raw.models).length, modelsFlattened: 0, modelsFailed: [], modelsPatched: [],
      textures: Object.keys(raw.textures).length, texturesDecoded: 0, texturesFailed: [], atlasSize: 0,
    }
    this.report = report

    const atlas = buildAtlas(raw.textures, report)
    this.atlasImage = atlas.image
    this.uvs = atlas.uvs
    this.textureStats = atlas.stats
    this.atlas = new TextureAtlas(makeImage(atlas.image), atlas.uvs)

    for (const [id, json] of Object.entries(raw.models)) {
      try {
        this.models.set(id, BlockModel.fromJson(patchModel(id, json as Record<string, unknown>, report)))
      } catch (e) {
        report.modelsFailed.push(`${id}: ${(e as Error).message}`)
      }
    }
    for (const [id, model] of this.models) {
      try {
        model.flatten(this)
        report.modelsFlattened++
      } catch (e) {
        report.modelsFailed.push(`${id}: ${(e as Error).message}`)
      }
    }

    for (const [id, json] of Object.entries(raw.blockstates)) {
      try {
        const j = json as { variants?: Record<string, unknown>; multipart?: Array<{ when?: unknown }> }
        this.definitions.set(id, BlockDefinition.fromJson(j))
        const values = new Map<string, Set<string>>()
        if (j.variants) {
          for (const key of Object.keys(j.variants)) {
            for (const [k, v] of Object.entries(parseVariantKey(key))) {
              const set = values.get(k) ?? new Set<string>()
              set.add(v)
              values.set(k, set)
            }
          }
        }
        for (const part of j.multipart ?? []) collectWhen(part.when, values)
        const props: Record<string, string[]> = {}
        const defaults: Props = {}
        for (const [k, set] of values) {
          const list = [...set]
          if (list.length === 1 && (list[0] === 'true' || list[0] === 'false')) list.push(list[0] === 'true' ? 'false' : 'true')
          props[k] = sortValues(list)
          defaults[k] = pickDefault(k, props[k])
        }
        // For a variants-only definition, prefer a default combination the
        // definition actually lists, so a freshly placed block renders.
        if (j.variants && Object.keys(j.variants).length > 0 && Object.keys(props).length > 0) {
          const keys = Object.keys(j.variants)
          const want = Object.entries(defaults)
          const exact = keys.find(k => { const p = parseVariantKey(k); return want.every(([a, b]) => p[a] === undefined || p[a] === b) })
          if (!exact) Object.assign(defaults, parseVariantKey(keys[0]))
        }
        this.properties.set(id, props)
        this.defaults.set(id, defaults)
        report.definitionsOk++
      } catch (e) {
        report.definitionsFailed.push(`${id}: ${(e as Error).message}`)
      }
    }
    this.blockIds = [...this.definitions.keys()].sort()
    for (const id of this.blockIds) this.flags.set(id, this.computeFlags(id))
  }

  private computeFlags(id: string): BlockFlags {
    const name = id
    const semi = SEMI_TRANSPARENT.test(name)
    const selfCulling = SELF_CULLING.test(name)
    let opaque = false
    if (!NEVER_OPAQUE.test(name)) {
      const def = this.definitions.get(id)
      const variants = def?.getModelVariants(this.defaults.get(id) ?? {}) ?? []
      if (variants.length > 0) {
        opaque = variants.every(v => this.isOpaqueCube(v.model))
      }
    }
    return { opaque, semi_transparent: semi, self_culling: selfCulling }
  }

  private isOpaqueCube(modelId: string): boolean {
    const model = this.models.get(Identifier.parse(modelId).toString()) as unknown as {
      elements?: Array<{ from: number[]; to: number[]; rotation?: unknown; faces?: Record<string, { texture: string }> }>
      getTexture?: (ref: string) => Identifier
    } | undefined
    if (!model?.elements || model.elements.length !== 1) return false
    const e = model.elements[0]
    if (e.rotation) return false
    if (!e.from.every(v => v === 0) || !e.to.every(v => v === 16)) return false
    const faces = e.faces ?? {}
    if (['up', 'down', 'north', 'south', 'east', 'west'].some(f => !faces[f])) return false
    for (const f of Object.values(faces)) {
      try {
        const tex = model.getTexture?.(f.texture)
        const st = tex ? this.textureStats[tex.toString()] : undefined
        if (!st || st.cutout || st.translucent) return false
      } catch {
        return false
      }
    }
    return true
  }

  // ---- deepslate Resources ---------------------------------------------

  getBlockDefinition(id: Identifier): BlockDefinition | null {
    return this.definitions.get(id.toString()) ?? null
  }

  getBlockModel(id: Identifier): BlockModel | null {
    return this.models.get(id.toString()) ?? null
  }

  getTextureAtlas(): ImageData {
    return this.atlas.getTextureAtlas()
  }

  getTextureUV(id: Identifier): UV {
    return this.atlas.getTextureUV(id)
  }

  getPixelSize(): number {
    return this.atlas.getPixelSize()
  }

  getBlockFlags(id: Identifier): BlockFlags | null {
    return this.flags.get(id.toString()) ?? null
  }

  getBlockProperties(id: Identifier): Record<string, string[]> | null {
    return this.properties.get(id.toString()) ?? null
  }

  getDefaultBlockProperties(id: Identifier): Record<string, string> | null {
    return this.defaults.get(id.toString()) ?? null
  }

  // ---- editor helpers --------------------------------------------------

  hasBlock(id: string): boolean {
    return this.definitions.has(id)
  }

  propertiesOf(id: string): Record<string, string[]> {
    return this.properties.get(id) ?? {}
  }

  defaultsOf(id: string): Props {
    return { ...(this.defaults.get(id) ?? {}) }
  }

  isOpaque(id: string): boolean {
    return this.flags.get(id)?.opaque ?? false
  }

  /** The texture a palette swatch shows for a block: its default model's first face. */
  swatchTexture(id: string): string | null {
    const def = this.definitions.get(id)
    const variants = def?.getModelVariants(this.defaults.get(id) ?? {}) ?? []
    for (const v of variants) {
      const model = this.models.get(Identifier.parse(v.model).toString()) as unknown as {
        elements?: Array<{ faces?: Record<string, { texture: string }> }>
        getTexture?: (ref: string) => Identifier
      } | undefined
      const faces = model?.elements?.[0]?.faces
      const face = faces?.north ?? faces?.up ?? (faces ? Object.values(faces)[0] : undefined)
      if (face && model?.getTexture) {
        try {
          return model.getTexture(face.texture).toString()
        } catch {
          // fall through to the next variant
        }
      }
    }
    const guess = `minecraft:block/${id.replace(/^minecraft:/, '')}`
    return this.uvs[guess] ? guess : null
  }

  textureUV(id: string): UV | null {
    return this.uvs[id] ?? null
  }

  atlasPixels(): AtlasImage {
    return this.atlasImage
  }
}

function sortValues(values: string[]): string[] {
  if (values.every(v => /^\d+$/.test(v))) return values.sort((a, b) => Number(a) - Number(b))
  const order = ['north', 'east', 'south', 'west', 'up', 'down', 'false', 'true']
  return values.sort((a, b) => {
    const ia = order.indexOf(a), ib = order.indexOf(b)
    if (ia >= 0 && ib >= 0) return ia - ib
    if (ia >= 0) return -1
    if (ib >= 0) return 1
    return a.localeCompare(b)
  })
}
