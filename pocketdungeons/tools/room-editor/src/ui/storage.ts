/**
 * Folder access and the local asset cache.
 *
 * Writable mode uses the File System Access API (Chromium). The picked folder
 * can be a namespace folder (data/pocketdungeons), the data folder, or a
 * resources root; the namespace is found by looking for dungeon_room/ and
 * structure/rooms/. Read-only mode takes a directory upload input instead
 * and offers downloads for anything edited.
 */

export type RoomKind = 'dungeon_room' | 'anomaly_room'

export interface RoomRecord {
  /** File name without .json, which is also the in-game room id path. */
  name: string
  kind: RoomKind
  /** Parsed metadata, or null for a template with no metadata file. */
  meta: Record<string, unknown> | null
  metaText: string | null
  metaError: string | null
  /** Template id from the metadata (or derived for an orphan template). */
  templateId: string
  /** The template path relative to the namespace folder, or null if it points outside it. */
  templatePath: string | null
  templateExists: boolean
}

interface Backend {
  readonly writable: boolean
  readonly label: string
  list(dir: string): Promise<string[]>
  read(path: string): Promise<Uint8Array | null>
  write(path: string, data: Uint8Array | string): Promise<void>
  exists(path: string): Promise<boolean>
}

async function dirHandle(root: FileSystemDirectoryHandle, path: string, create: boolean): Promise<FileSystemDirectoryHandle | null> {
  let d = root
  for (const part of path.split('/').filter(Boolean)) {
    try {
      d = await d.getDirectoryHandle(part, { create })
    } catch {
      return null
    }
  }
  return d
}

class FsaBackend implements Backend {
  readonly writable = true
  constructor(private readonly root: FileSystemDirectoryHandle, readonly label: string) {}

  async list(dir: string): Promise<string[]> {
    const d = await dirHandle(this.root, dir, false)
    if (!d) return []
    const out: string[] = []
    for await (const [name, h] of d.entries()) if (h.kind === 'file') out.push(name)
    return out.sort()
  }

  async read(path: string): Promise<Uint8Array | null> {
    const i = path.lastIndexOf('/')
    const d = await dirHandle(this.root, path.slice(0, Math.max(0, i)), false)
    if (!d) return null
    try {
      const f = await (await d.getFileHandle(path.slice(i + 1))).getFile()
      return new Uint8Array(await f.arrayBuffer())
    } catch {
      return null
    }
  }

  async exists(path: string): Promise<boolean> {
    const i = path.lastIndexOf('/')
    const d = await dirHandle(this.root, path.slice(0, Math.max(0, i)), false)
    if (!d) return false
    try {
      await d.getFileHandle(path.slice(i + 1))
      return true
    } catch {
      return false
    }
  }

  async write(path: string, data: Uint8Array | string): Promise<void> {
    const i = path.lastIndexOf('/')
    const d = await dirHandle(this.root, path.slice(0, Math.max(0, i)), true)
    if (!d) throw new Error(`cannot create folder for ${path}`)
    const fh = await d.getFileHandle(path.slice(i + 1), { create: true })
    const w = await fh.createWritable()
    await w.write(typeof data === 'string' ? data : new Blob([data as BlobPart]))
    await w.close()
  }
}

class MemoryBackend implements Backend {
  readonly writable = false
  constructor(private readonly files: Map<string, Blob>, readonly label: string) {}

  async list(dir: string): Promise<string[]> {
    const prefix = dir.endsWith('/') ? dir : dir + '/'
    return [...this.files.keys()].filter(k => k.startsWith(prefix) && !k.slice(prefix.length).includes('/'))
      .map(k => k.slice(prefix.length)).sort()
  }

  async read(path: string): Promise<Uint8Array | null> {
    const f = this.files.get(path)
    return f ? new Uint8Array(await f.arrayBuffer()) : null
  }

  async exists(path: string): Promise<boolean> {
    return this.files.has(path)
  }

  async write(): Promise<void> {
    throw new Error('this folder was opened read only; use the download buttons instead')
  }
}

/** A pack folder served by the local dev server (see vite.config.ts), writable when started by "Room Editor.cmd". */
class HttpBackend implements Backend {
  constructor(private readonly base: string, readonly writable: boolean, readonly label: string) {}

  private url(path: string): string {
    return `${this.base}/file/${path.split('/').map(encodeURIComponent).join('/')}`
  }

  async list(dir: string): Promise<string[]> {
    const res = await fetch(`${this.base}/list?dir=${encodeURIComponent(dir)}`)
    return res.ok ? ((await res.json()) as string[]) : []
  }

  async read(path: string): Promise<Uint8Array | null> {
    const res = await fetch(this.url(path))
    return res.ok ? new Uint8Array(await res.arrayBuffer()) : null
  }

  async exists(path: string): Promise<boolean> {
    return (await fetch(this.url(path), { method: 'HEAD' })).ok
  }

  async write(path: string, data: Uint8Array | string): Promise<void> {
    const res = await fetch(this.url(path), { method: 'PUT', body: typeof data === 'string' ? data : new Blob([data as BlobPart]) })
    if (!res.ok) throw new Error(`save failed for ${path}: ${await res.text()}`)
  }
}

export function stripBom(s: string): string {
  return s.charCodeAt(0) === 0xfeff ? s.slice(1) : s
}

/** Instant.now() truncated to seconds with ':' replaced, as RoomBuilderCommands.saveRoom stamps backups. */
export function versionStamp(d = new Date()): string {
  return d.toISOString().replace(/\.\d{3}Z$/, 'Z').replace(/:/g, '-')
}

export class PackFolder {
  private constructor(private readonly be: Backend, readonly namespace: string, private readonly nsPrefix: string) {}

  get writable(): boolean { return this.be.writable }
  get label(): string { return this.be.label }

  static supportsWritable(): boolean {
    return typeof (window as unknown as { showDirectoryPicker?: unknown }).showDirectoryPicker === 'function'
  }

  static async pickWritable(): Promise<PackFolder> {
    const picker = (window as unknown as { showDirectoryPicker: (o: object) => Promise<FileSystemDirectoryHandle> }).showDirectoryPicker
    const root = await picker({ id: 'pd-room-pack', mode: 'readwrite' })
    return PackFolder.locate(new FsaBackend(root, root.name), root.name)
  }

  /** From a directory handle the caller already holds (also used by the tests). */
  static async fromHandle(root: FileSystemDirectoryHandle): Promise<PackFolder> {
    return PackFolder.locate(new FsaBackend(root, root.name), root.name)
  }

  /** From an <input type=file webkitdirectory> selection. */
  static async fromFileList(list: FileList): Promise<PackFolder> {
    const files = new Map<string, Blob>()
    let top = ''
    for (const f of Array.from(list)) {
      const rel = (f as File & { webkitRelativePath: string }).webkitRelativePath || f.name
      const parts = rel.split('/')
      top = parts[0]
      files.set(parts.slice(1).join('/'), f)
    }
    return PackFolder.locate(new MemoryBackend(files, `${top} (read only)`), top)
  }

  /** The pack folder the local dev server was started with (see vite.config.ts and "Room Editor.cmd"). */
  static async fromDevServer(base: string): Promise<PackFolder> {
    const info = (await (await fetch(`${base}/info`)).json()) as { writable: boolean }
    const label = info.writable ? 'mod source' : 'mod source (read only)'
    return PackFolder.locate(new HttpBackend(base, info.writable, label), 'dev')
  }

  private static async locate(be: Backend, rootName: string): Promise<PackFolder> {
    const isNs = async (prefix: string) =>
      (await be.list(prefix + 'dungeon_room')).length > 0 || (await be.list(prefix + 'structure/rooms')).length > 0
    if (await isNs('')) return new PackFolder(be, await PackFolder.guessNamespace(be, rootName), '')
    for (const base of ['data/', 'src/main/resources/data/', '']) {
      for (const ns of ['pocketdungeons']) {
        if (await isNs(`${base}${ns}/`)) return new PackFolder(be, ns, `${base}${ns}/`)
      }
    }
    throw new Error('no dungeon_room/ or structure/rooms/ folder found; pick data/pocketdungeons or the resources root')
  }

  /**
   * A namespace folder picked directly carries its namespace only in its name,
   * which a copy may not keep, so the templates its rooms point at decide.
   */
  private static async guessNamespace(be: Backend, fallback: string): Promise<string> {
    const counts = new Map<string, number>()
    for (const kind of ['dungeon_room', 'anomaly_room']) {
      for (const f of (await be.list(kind)).filter(n => n.endsWith('.json')).slice(0, 20)) {
        const bytes = await be.read(`${kind}/${f}`)
        try {
          const t = (JSON.parse(stripBom(new TextDecoder().decode(bytes ?? new Uint8Array()))) as { template?: string }).template
          const ns = typeof t === 'string' && t.includes(':') ? t.slice(0, t.indexOf(':')) : null
          if (ns) counts.set(ns, (counts.get(ns) ?? 0) + 1)
        } catch {
          // an unreadable file does not vote
        }
      }
    }
    const best = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]
    return best ? best[0] : fallback
  }

  private p(rel: string): string {
    return this.nsPrefix + rel
  }

  /** "pocketdungeons:rooms/hall_tee" to "structure/rooms/hall_tee.nbt" within this namespace. */
  templatePathFor(templateId: string): string | null {
    const i = templateId.indexOf(':')
    const ns = i >= 0 ? templateId.slice(0, i) : 'minecraft'
    const path = i >= 0 ? templateId.slice(i + 1) : templateId
    if (ns !== this.namespace) return null
    return `structure/${path}.nbt`
  }

  async listRooms(): Promise<RoomRecord[]> {
    const out: RoomRecord[] = []
    const templates = new Set((await this.be.list(this.p('structure/rooms'))).filter(f => f.endsWith('.nbt')))
    const referenced = new Set<string>()
    for (const kind of ['dungeon_room', 'anomaly_room'] as RoomKind[]) {
      for (const f of await this.be.list(this.p(kind))) {
        if (!f.endsWith('.json')) continue
        const name = f.slice(0, -5)
        const bytes = await this.be.read(this.p(`${kind}/${f}`))
        const text = bytes ? stripBom(new TextDecoder().decode(bytes)) : ''
        let meta: Record<string, unknown> | null = null
        let metaError: string | null = null
        try {
          meta = JSON.parse(text) as Record<string, unknown>
        } catch (e) {
          metaError = (e as Error).message
        }
        const templateId = typeof meta?.template === 'string' ? meta.template : `${this.namespace}:rooms/${name}`
        const templatePath = this.templatePathFor(templateId)
        if (templatePath?.startsWith('structure/rooms/')) referenced.add(templatePath.slice(16))
        const templateExists = templatePath ? await this.be.exists(this.p(templatePath)) : false
        out.push({ name, kind, meta, metaText: text, metaError, templateId, templatePath, templateExists })
      }
    }
    for (const f of templates) {
      if (referenced.has(f)) continue
      const name = f.slice(0, -4)
      out.push({
        name, kind: 'dungeon_room', meta: null, metaText: null, metaError: null,
        templateId: `${this.namespace}:rooms/${name}`, templatePath: `structure/rooms/${f}`, templateExists: true,
      })
    }
    return out.sort((a, b) => a.name.localeCompare(b.name))
  }

  async readTemplate(path: string): Promise<Uint8Array | null> {
    return this.be.read(this.p(path))
  }

  async exists(rel: string): Promise<boolean> {
    return this.be.exists(this.p(rel))
  }

  /**
   * Writes a file, first copying any existing file into versions/ beside it:
   * the in-game editor's convention (RoomBuilderCommands.saveRoom), same
   * folder, same {name}_{stamp} naming. JSON backups get a .bak suffix: the
   * room manifest lists dungeon_room/ recursively, so a plain .json in
   * versions/ would load as an extra live room.
   */
  async writeWithBackup(rel: string, data: Uint8Array | string): Promise<string | null> {
    if (!this.be.writable) throw new Error('read only folder')
    let backup: string | null = null
    const existing = await this.be.read(this.p(rel))
    if (existing) {
      const i = rel.lastIndexOf('/')
      const dir = rel.slice(0, i)
      const file = rel.slice(i + 1)
      const dot = file.lastIndexOf('.')
      const base = file.slice(0, dot)
      const ext = file.slice(dot)
      const suffix = ext === '.json' ? '.json.bak' : ext
      const stamp = versionStamp()
      let candidate = `${dir}/versions/${base}_${stamp}${suffix}`
      for (let n = 2; await this.be.exists(this.p(candidate)); n++) candidate = `${dir}/versions/${base}_${stamp}_${n}${suffix}`
      await this.be.write(this.p(candidate), existing)
      backup = candidate
    }
    await this.be.write(this.p(rel), data)
    return backup
  }
}

// ---- IndexedDB cache for the extracted jar assets ----------------------------

const DB = 'pd-room-editor'
const STORE = 'assets'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(STORE)
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function tx<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode)
    const req = fn(t.objectStore(STORE))
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

export interface CachedAssets {
  jarName: string
  jarSize: number
  savedAt: number
  entries: Record<string, Uint8Array>
}

export async function saveAssetCache(value: CachedAssets): Promise<void> {
  try {
    await tx('readwrite', s => s.put(value, 'current'))
  } catch {
    // A browser that blocks storage just loses the cache.
  }
}

export async function loadAssetCache(): Promise<CachedAssets | null> {
  try {
    return (await tx<CachedAssets | undefined>('readonly', s => s.get('current'))) ?? null
  } catch {
    return null
  }
}

export async function clearAssetCache(): Promise<void> {
  try {
    await tx('readwrite', s => s.delete('current'))
  } catch {
    // nothing to clear
  }
}

export function download(name: string, data: Uint8Array | string, type = 'application/octet-stream'): void {
  const blob = new Blob([typeof data === 'string' ? data : (data as BlobPart)], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 5000)
}
