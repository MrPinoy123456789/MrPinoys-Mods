import { createReadStream, existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'
import { defineConfig } from 'vitest/config'

// The schema files live in the mod's docs folder, outside this tool, so the
// dev server has to be allowed to read them. Nothing from a Minecraft jar is
// ever imported here: block assets are read at runtime from a jar the user
// picks in the browser.
const docsDir = fileURLToPath(new URL('../../docs', import.meta.url))

/**
 * Development harness, dev server only (apply: 'serve', so never part of a
 * build). With MC_JAR and PACK_DIR set, it serves the user's own jar and a
 * read only copy of a pack folder to the page at ?dev=1, so an automated
 * browser can exercise the renderer without file pickers. With SHOT_DIR set,
 * POST /__dev/shot?name=x saves a PNG of the view there.
 */
function devHarness(): Plugin {
  const jar = process.env.MC_JAR
  const pack = process.env.PACK_DIR ? resolve(process.env.PACK_DIR) : null
  // PACK_WRITABLE=1 (set by "Room Editor.cmd") lets the page save straight
  // into PACK_DIR. The server only listens on localhost.
  const writable = process.env.PACK_WRITABLE === '1'
  /** Resolves a pack-relative path, or null if it would escape the pack folder. */
  const inPack = (rel: string): string | null => {
    if (!pack) return null
    const full = resolve(pack, rel)
    return full === pack || full.startsWith(pack + sep) ? full : null
  }
  const listPack = (): string[] => {
    if (!pack) return []
    const out: string[] = []
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const full = join(dir, name)
        if (statSync(full).isDirectory()) {
          if (name !== 'versions') walk(full)
        } else if (/\.(json|nbt)$/.test(name)) {
          out.push(relative(pack, full).split(sep).join('/'))
        }
      }
    }
    for (const sub of ['dungeon_room', 'anomaly_room', 'structure/rooms']) if (existsSync(join(pack, sub))) walk(join(pack, sub))
    return out
  }
  return {
    name: 'room-editor-dev-harness',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = decodeURIComponent((req.url ?? '').split('?')[0])
        if (url === '/__dev/jar' && jar && existsSync(jar)) {
          res.setHeader('Content-Type', 'application/java-archive')
          createReadStream(jar).pipe(res)
          return
        }
        if (url === '/__dev/pack/index.json' && pack) {
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify(listPack()))
          return
        }
        const shots = process.env.SHOT_DIR
        if (url === '/__dev/shot' && req.method === 'POST' && shots) {
          const name = (new URL(req.url ?? '', 'http://x').searchParams.get('name') ?? 'shot').replace(/[^a-z0-9_-]/gi, '_')
          const chunks: Buffer[] = []
          req.on('data', (c: Buffer) => chunks.push(c))
          req.on('end', () => {
            mkdirSync(shots, { recursive: true })
            writeFileSync(join(shots, `${name}.png`), Buffer.concat(chunks))
            res.end('ok')
          })
          return
        }
        if (url === '/__dev/pack/info' && pack) {
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify({ writable, dir: pack }))
          return
        }
        if (url === '/__dev/pack/list' && pack) {
          const dir = inPack(new URL(req.url ?? '', 'http://x').searchParams.get('dir') ?? '')
          const names = dir && existsSync(dir) && statSync(dir).isDirectory()
            ? readdirSync(dir).filter(n => statSync(join(dir, n)).isFile()).sort()
            : []
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify(names))
          return
        }
        if (url.startsWith('/__dev/pack/file/') && pack) {
          const full = inPack(url.slice('/__dev/pack/file/'.length))
          if (!full) {
            res.statusCode = 403
            res.end('outside the pack folder')
            return
          }
          if (req.method === 'PUT') {
            if (!writable) {
              res.statusCode = 403
              res.end('the dev server was started read only')
              return
            }
            const chunks: Buffer[] = []
            req.on('data', (c: Buffer) => chunks.push(c))
            req.on('end', () => {
              mkdirSync(dirname(full), { recursive: true })
              writeFileSync(full, Buffer.concat(chunks))
              res.end('ok')
            })
            return
          }
          if (existsSync(full) && statSync(full).isFile()) {
            createReadStream(full).pipe(res)
          } else {
            res.statusCode = 404
            res.end('not found')
          }
          return
        }
        next()
      })
    },
  }
}

export default defineConfig({
  plugins: [devHarness()],
  server: {
    port: 5178,
    strictPort: false,
    fs: { allow: ['.', docsDir] },
  },
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 2000,
  },
  test: {
    include: ['test/**/*.test.ts'],
    testTimeout: 180000,
    hookTimeout: 180000,
  },
})
