import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Where the headless checks read from. Both are read only: the room files are
 * parsed and re-encoded in memory, never written back.
 *
 *   ROOMS_DIR  a structure/rooms folder (defaults to the mod's own resources)
 *   MC_JAR     a Minecraft 26.2 client jar (defaults to the launcher's copy)
 */
const here = fileURLToPath(new URL('.', import.meta.url))

export const ROOMS_DIR = process.env.ROOMS_DIR
  ?? join(here, '../../../src/main/resources/data/pocketdungeons/structure/rooms')

export const META_DIR = join(ROOMS_DIR, '../../dungeon_room')

export const MC_JAR = process.env.MC_JAR
  ?? join(process.env.APPDATA ?? '', '.minecraft/versions/26.2/26.2.jar')

export function roomFiles(): string[] {
  return existsSync(ROOMS_DIR) ? readdirSync(ROOMS_DIR).filter(f => f.endsWith('.nbt')).sort() : []
}
