// Kinetic Hosting panel (Pterodactyl client API) transport for pdserver.mjs, so the
// Lemon tools can drive a test server that runs on Kinetic instead of this PC. No
// dependencies: Node's own fetch, FormData and Blob.
//
// Configured by KINETIC_PANEL_URL, KINETIC_SERVER_ID and KINETIC_API_KEY, from the
// environment or from kinetic.env beside this file (gitignored). PD_TARGET=local
// forces the local server even when they are set. The key is a *client* API key
// from the panel's Account > API Credentials; it is only ever sent to the panel.

import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))

/** KEY=VALUE lines from kinetic.env, quotes optional; the environment wins over the file. */
function fileConfig() {
  const path = join(here, 'kinetic.env')
  if (!existsSync(path)) return {}
  const out = {}
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*"?([^"]*)"?\s*$/)
    if (m) out[m[1]] = m[2]
  }
  return out
}

/** The remote configuration, or null when pdserver should drive the local server. */
export function remoteConfig() {
  const file = fileConfig()
  const get = key => process.env[key] || file[key] || ''
  if ((get('PD_TARGET') || '').toLowerCase() === 'local') return null
  const serverId = get('KINETIC_SERVER_ID')
  const apiKey = get('KINETIC_API_KEY')
  if (!serverId || !apiKey) return null
  const source = process.env.KINETIC_SERVER_ID ? 'the environment' : 'kinetic.env'
  return { panelUrl: (get('KINETIC_PANEL_URL') || 'https://kineticpanel.net').replace(/\/+$/, ''), serverId, apiKey, source }
}

/** Turns a panel error into a readable line; never includes the key. */
function explain(status, body) {
  const hints = {
    401: 'Unauthorized: check that KINETIC_API_KEY is a valid client API key.',
    403: 'Forbidden: this key cannot reach that server.',
    404: 'Not found: check KINETIC_SERVER_ID (the short id in the panel URL).',
    409: 'Conflict: the server is busy (installing, transferring or suspended).',
    429: 'The panel is rate limiting requests; try again shortly.',
  }
  const detail = (body || '').trim().slice(0, 300)
  return `panel returned HTTP ${status}. ${hints[status] || ''}${detail ? ' ' + detail : ''}`.trim()
}

export class Kinetic {
  constructor({ panelUrl, serverId, apiKey }) {
    this.base = `${panelUrl}/api/client/servers/${encodeURIComponent(serverId)}`
    this.serverId = serverId
    this.headers = { Authorization: `Bearer ${apiKey}`, Accept: 'application/json' }
  }

  async request(method, path, { json, query, raw = false, okStatuses = [200, 204] } = {}) {
    const url = new URL(this.base + path)
    for (const [k, v] of Object.entries(query || {})) url.searchParams.set(k, v)
    const init = { method, headers: { ...this.headers } }
    if (json !== undefined) {
      init.headers['Content-Type'] = 'application/json'
      init.body = JSON.stringify(json)
    }
    const res = await fetch(url, init)
    if (!okStatuses.includes(res.status)) throw new Error(explain(res.status, await res.text().catch(() => '')))
    if (res.status === 204) return null
    return raw ? Buffer.from(await res.arrayBuffer()) : res.json()
  }

  /** 'running', 'starting', 'stopping' or 'offline'. */
  async state() {
    const data = await this.request('GET', '/resources')
    return data.attributes.current_state
  }

  power(signal) {
    return this.request('POST', '/power', { json: { signal } })
  }

  /** Sends a console command. The panel returns no output; read it from the log. */
  command(command) {
    return this.request('POST', '/command', { json: { command } })
  }

  /** A file's size and modification time, or null if it does not exist. */
  async stat(path) {
    const slash = path.lastIndexOf('/')
    const dir = path.slice(0, slash) || '/'
    const name = path.slice(slash + 1)
    let data
    try {
      data = await this.request('GET', '/files/list', { query: { directory: dir } })
    } catch (e) {
      if (/HTTP 404/.test(e.message)) return null
      throw e
    }
    const entry = (data.data || []).map(d => d.attributes).find(a => a.name === name && a.is_file !== false)
    return entry ? { size: entry.size, mtimeMs: Date.parse(entry.modified_at) } : null
  }

  /** A file's whole contents as bytes. */
  read(path) {
    return this.request('GET', '/files/contents', { query: { file: path }, raw: true })
  }

  /** Uploads local files (name and bytes) into `directory`, replacing any with the same name. */
  async upload(directory, files) {
    const signed = await this.request('GET', '/files/upload')
    const url = new URL(signed.attributes.url)
    url.searchParams.set('directory', directory)
    const form = new FormData()
    for (const { name, bytes } of files) form.append('files', new Blob([bytes]), name)
    const res = await fetch(url, { method: 'POST', body: form })
    if (!res.ok) throw new Error(explain(res.status, await res.text().catch(() => '')))
  }
}
