import { strFromU8, unzip, unzipSync, type UnzipFileInfo } from 'fflate'

/**
 * Reads the block assets out of the user's own Minecraft client jar. Nothing
 * here is bundled or fetched: the bytes come from a file the user picked.
 */

export interface RawAssets {
  /** Game version id from the jar's version.json, when present. */
  versionId: string | null
  /** world_version from version.json: the DataVersion a new template should carry. */
  dataVersion: number | null
  /** "minecraft:oak_stairs" to parsed blockstate JSON. */
  blockstates: Record<string, unknown>
  /** "minecraft:block/stairs" to parsed model JSON. */
  models: Record<string, unknown>
  /** "minecraft:block/stone" to PNG bytes. */
  textures: Record<string, Uint8Array>
}

const PREFIX = 'assets/minecraft/'

/**
 * Entity textures the deepslate special renderers draw chests, beds, signs,
 * skulls and pots from. Everything under textures/block is taken as well.
 */
const ENTITY_TEXTURE_DIRS = [
  'entity/chest/', 'entity/bed/', 'entity/bell/', 'entity/conduit/', 'entity/decorated_pot/',
  'entity/shulker/', 'entity/signs/', 'entity/banner/banner_base', 'entity/skeleton/',
  'entity/zombie/', 'entity/creeper/', 'entity/piglin/', 'entity/enderdragon/', 'entity/player/wide/steve',
  'entity/copper_golem/',
]

export function wantEntry(name: string): boolean {
  if (name === 'version.json') return true
  if (!name.startsWith(PREFIX)) return false
  const rest = name.slice(PREFIX.length)
  if (rest.startsWith('blockstates/')) return rest.endsWith('.json')
  if (rest.startsWith('models/block/')) return rest.endsWith('.json')
  if (rest.startsWith('textures/block/')) return rest.endsWith('.png')
  if (rest.startsWith('textures/')) {
    const t = rest.slice('textures/'.length)
    return t.endsWith('.png') && ENTITY_TEXTURE_DIRS.some(d => t.startsWith(d))
  }
  return false
}

export function assetsFromEntries(entries: Record<string, Uint8Array>): RawAssets {
  const out: RawAssets = { versionId: null, dataVersion: null, blockstates: {}, models: {}, textures: {} }
  for (const [name, data] of Object.entries(entries)) {
    if (data.length === 0) continue
    if (name === 'version.json') {
      try {
        const v = JSON.parse(strFromU8(data)) as { id?: string; world_version?: number }
        out.versionId = v.id ?? null
        out.dataVersion = typeof v.world_version === 'number' ? v.world_version : null
      } catch {
        // A jar without a readable version.json still has usable assets.
      }
      continue
    }
    const rest = name.slice(PREFIX.length)
    if (rest.startsWith('blockstates/')) {
      out.blockstates['minecraft:' + rest.slice(12, -5)] = JSON.parse(strFromU8(data))
    } else if (rest.startsWith('models/')) {
      out.models['minecraft:' + rest.slice(7, -5)] = JSON.parse(strFromU8(data))
    } else if (rest.startsWith('textures/')) {
      out.textures['minecraft:' + rest.slice(9, -4)] = data
    }
  }
  return out
}

/** Synchronous extraction, for Node checks. */
export function extractAssetsSync(jar: Uint8Array): RawAssets {
  return assetsFromEntries(unzipSync(jar, { filter: (f: UnzipFileInfo) => wantEntry(f.name) }))
}

/** Raw filtered entries, the shape cached in IndexedDB. */
export function extractEntries(jar: Uint8Array): Promise<Record<string, Uint8Array>> {
  return new Promise((resolve, reject) => {
    unzip(jar, { filter: (f: UnzipFileInfo) => wantEntry(f.name) }, (err, data) => {
      if (err) reject(err)
      else resolve(data)
    })
  })
}
