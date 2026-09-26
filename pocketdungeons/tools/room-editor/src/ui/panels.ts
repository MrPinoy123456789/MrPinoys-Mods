import { NbtCompound } from 'deepslate/nbt'
import { maskLetters } from '../core/geometry'
import { ROOM_SCHEMA, enumFor, type Meta, type SchemaProp } from '../core/meta'
import { stateKey, type BlockEntry } from '../core/template'
import { h } from './dom'
import type { App } from './app'

/** The right hand panels: palette, metadata form, checks, inspector. */

function swatch(app: App, blockId: string): HTMLElement {
  const el = h('span', { class: 'swatch' })
  const res = app.res
  const url = app.atlasDataUrl()
  const tex = res?.swatchTexture(blockId)
  const uv = tex ? res?.textureUV(tex) : null
  if (url && uv) {
    const perRow = Math.round(1 / (uv[2] - uv[0]))
    const s = 20
    el.style.backgroundImage = `url(${url})`
    el.style.backgroundSize = `${perRow * s}px ${perRow * s}px`
    el.style.backgroundPosition = `-${Math.round(uv[0] * perRow) * s}px -${Math.round(uv[1] * perRow) * s}px`
  }
  return el
}

export function renderPalette(app: App): HTMLElement {
  const root = h('div')
  const res = app.res
  if (!res) {
    root.append(h('p', { class: 'muted' }, 'Load your client jar to build the block palette.'))
    return root
  }
  // Current brush and its properties.
  const b = app.brush
  root.append(h('h3', {}, 'Brush'))
  root.append(h('div', { class: 'palette-item sel' }, swatch(app, b.state.name), h('span', {}, b.state.name), b.nbt ? h('span', { class: 'muted' }, ' + block entity') : null))
  if (app.brushLabel) root.append(h('div', { class: 'muted' }, app.brushLabel))
  const props = res.propertiesOf(b.state.name)
  const keys = new Set([...Object.keys(props), ...Object.keys(b.state.props)])
  for (const k of keys) {
    const values = props[k] ?? []
    const current = b.state.props[k] ?? res.defaultsOf(b.state.name)[k] ?? ''
    let input: HTMLInputElement | HTMLSelectElement
    if (values.length > 0) {
      input = h('select', {}, ...values.map(v => h('option', { value: v }, v))) as HTMLSelectElement
      if (current && !values.includes(current)) input.append(h('option', { value: current }, current))
      input.value = current
    } else {
      input = h('input', { value: current }) as HTMLInputElement
    }
    input.onchange = () => {
      app.brush = { state: { name: b.state.name, props: { ...b.state.props, [k]: input.value } }, nbt: b.nbt }
      app.renderPanel()
    }
    root.append(h('div', { class: 'field' }, h('span', {}, k), input))
  }
  const extra = h('input', { placeholder: 'key=value, for example waterlogged=false' }) as HTMLInputElement
  extra.onchange = () => {
    const [k, v] = extra.value.split('=').map(s => s.trim())
    if (k && v !== undefined) app.brush = { state: { name: b.state.name, props: { ...b.state.props, [k]: v } }, nbt: b.nbt }
    app.renderPanel()
  }
  root.append(h('div', { class: 'field' }, h('span', { title: 'Properties not listed in the blockstate file (waterlogged, powered) can be added here. A property left out takes the block default when the game loads the template.' }, 'add property'), extra))
  if (b.nbt) {
    root.append(h('button', { class: 'btn small', onclick: () => { app.brush = { state: b.state }; app.brushLabel = ''; app.renderPanel() } }, 'Drop block entity data'))
  }

  root.append(h('h3', {}, 'Presets'))
  root.append(h('div', { class: 'checks' }, ...app.presets().map(p =>
    h('button', { class: 'btn small', title: p.title, onclick: () => app.setBrush(p.entry(), p.title) }, p.label))))

  root.append(h('h3', {}, `Blocks (${res.blockIds.length} from the jar)`))
  const search = h('input', { type: 'search', placeholder: 'Search blocks', value: app.paletteFilter }) as HTMLInputElement
  const list = h('div', { class: 'palette-list' })
  const fill = () => {
    list.replaceChildren()
    const q = search.value.toLowerCase().trim()
    app.paletteFilter = search.value
    const ids = res.blockIds.filter(id => !q || id.includes(q))
    for (const id of ids.slice(0, 400)) {
      list.append(h('div', {
        class: `palette-item${id === b.state.name ? ' sel' : ''}`,
        onclick: () => app.setBrush({ state: { name: id, props: res.defaultsOf(id) } }),
      }, swatch(app, id), h('span', {}, id.replace(/^minecraft:/, ''))))
    }
    if (ids.length > 400) list.append(h('div', { class: 'muted' }, `${ids.length - 400} more; refine the search`))
  }
  search.oninput = fill
  fill()
  root.append(search, list)
  return root
}

// ---- metadata ------------------------------------------------------------------

export function renderMetaForm(app: App): HTMLElement {
  const root = h('div')
  const room = app.room
  if (!room) {
    root.append(h('p', { class: 'muted' }, 'Open a room to edit its metadata.'))
    return root
  }
  root.append(h('div', { class: 'muted' }, `${room.kind}/${room.name}.json${room.isNew ? ' (new)' : ''}${room.metaDirty ? ', unsaved' : ''}`))
  if (!room.meta) {
    root.append(h('p', {}, 'This template has no metadata file, so the game never offers it.'))
    root.append(h('button', { class: 'btn', onclick: () => app.createMetaForOrphan() }, 'Create metadata'))
    return root
  }
  const meta = room.meta
  const set = (k: string, v: unknown) => {
    const next: Meta = { ...(app.room?.meta ?? meta) }
    if (v === undefined || v === '' || (Array.isArray(v) && v.length === 0 && !ROOM_SCHEMA.required.includes(k))) delete next[k]
    else next[k] = v
    void app.updateMeta(next).then(() => refreshErrors())
  }
  const errorsEl = h('div')
  const refreshErrors = () => {
    errorsEl.replaceChildren()
    if (app.metaErrors.length === 0) errorsEl.append(h('div', { class: 'ok' }, 'Valid against dungeon_room.schema.json'))
    for (const e of app.metaErrors) errorsEl.append(h('div', { class: 'err' }, e))
  }
  refreshErrors()
  root.append(errorsEl)

  for (const [key, prop] of Object.entries(ROOM_SCHEMA.properties)) {
    root.append(fieldFor(key, prop, meta[key], v => set(key, v)))
  }
  const unknown = Object.keys(meta).filter(k => !(k in ROOM_SCHEMA.properties))
  if (unknown.length > 0) {
    root.append(h('p', { class: 'err' }, `Fields not in the schema (kept as is): ${unknown.join(', ')}`))
  }

  root.append(h('h3', {}, 'Raw JSON'))
  const raw = h('textarea', { rows: 10 }) as HTMLTextAreaElement
  raw.value = JSON.stringify(meta, null, 2)
  const rawErr = h('div', { class: 'err' })
  raw.onchange = () => {
    try {
      const parsed = JSON.parse(raw.value) as Meta
      rawErr.textContent = ''
      void app.updateMeta(parsed).then(() => app.renderPanel())
    } catch (e) {
      rawErr.textContent = (e as Error).message
    }
  }
  root.append(raw, rawErr)
  return root
}

function fieldFor(key: string, prop: SchemaProp, value: unknown, onChange: (v: unknown) => void): HTMLElement {
  const required = ROOM_SCHEMA.required.includes(key)
  const label = h('span', { title: prop.$comment ?? '' }, key + (required ? ' *' : ''))
  const def = prop.default !== undefined ? `default ${JSON.stringify(prop.default)}` : ''
  const en = enumFor(prop)
  if (prop.type === 'array' && en) {
    const cur = Array.isArray(value) ? (value as string[]) : []
    const boxes = en.map(v => {
      const cb = h('input', { type: 'checkbox', checked: cur.includes(v) || cur.includes(`pocketdungeons:${v}`) }) as HTMLInputElement
      cb.onchange = () => {
        const next = en.filter((x, i) => (x === v ? cb.checked : (boxes[i].firstChild as HTMLInputElement).checked))
        onChange(next)
      }
      return h('label', {}, cb, ` ${v}`)
    })
    return h('div', { class: 'field' }, label, h('span', { class: 'checks' }, ...boxes))
  }
  if (prop.type === 'array' && prop.items?.type === 'integer' && prop.minItems === 2 && prop.maxItems === 2) {
    const cur = Array.isArray(value) ? (value as number[]) : []
    const a = h('input', { type: 'number', value: cur[0] ?? '', placeholder: '1', style: 'width:60px' }) as HTMLInputElement
    const b = h('input', { type: 'number', value: cur[1] ?? '', placeholder: '1', style: 'width:60px' }) as HTMLInputElement
    const ch = () => onChange(a.value === '' && b.value === '' ? undefined : [Number(a.value || 1), Number(b.value || 1)])
    a.onchange = ch
    b.onchange = ch
    return h('div', { class: 'field' }, label, h('span', {}, a, ' x ', b), def ? h('span', { class: 'hint' }, def) : null)
  }
  if (prop.type === 'array') {
    const cur = Array.isArray(value) ? (value as string[]).join(', ') : ''
    const inp = h('input', { value: cur, placeholder: 'comma separated' }) as HTMLInputElement
    inp.onchange = () => onChange(inp.value.split(',').map(s => s.trim()).filter(Boolean))
    return h('div', { class: 'field' }, label, inp)
  }
  if (en) {
    const sel = h('select', {}, h('option', { value: '' }, def ? `(${def})` : '(unset)'), ...en.map(v => h('option', { value: v }, v))) as HTMLSelectElement
    sel.value = typeof value === 'string' ? value : ''
    sel.onchange = () => onChange(sel.value || undefined)
    return h('div', { class: 'field' }, label, sel)
  }
  if (prop.type === 'integer') {
    const inp = h('input', { type: 'number', value: typeof value === 'number' ? value : '', placeholder: def, min: prop.minimum ?? '', max: prop.maximum ?? '' }) as HTMLInputElement
    inp.onchange = () => onChange(inp.value === '' ? undefined : Number(inp.value))
    return h('div', { class: 'field' }, label, inp)
  }
  const inp = h('input', { value: typeof value === 'string' ? value : '', placeholder: prop.pattern ? 'namespace:path' : '' }) as HTMLInputElement
  inp.onchange = () => onChange(inp.value.trim() || undefined)
  return h('div', { class: 'field' }, label, inp)
}

// ---- checks ----------------------------------------------------------------------

export function renderChecks(app: App): HTMLElement {
  const root = h('div')
  const room = app.room
  const v = app.validation
  if (!room || !v) {
    root.append(h('p', { class: 'muted' }, 'Open a room to check it.'))
    return root
  }
  const errors = v.findings.filter(f => f.severity === 'error').length + app.metaErrors.length
  root.append(h('p', {}, h('strong', { class: errors ? 'err' : 'ok' }, errors ? `${errors} error(s)` : 'No errors'),
    ` . Door mask ${maskLetters(v.mask)}, ${v.doorJigsaws.length} door jigsaws, template ${room.template.size.join(' x ')}.`))
  for (const e of app.metaErrors) root.append(h('div', { class: 'finding error' }, e, h('small', {}, 'metadata schema')))
  for (const f of v.findings) {
    root.append(h('div', {
      class: `finding ${f.severity}`,
      title: f.positions ? 'Click to highlight' : '',
      onclick: () => app.showFinding(f),
    }, f.message, h('small', {}, `${f.source}${f.positions ? `, ${f.positions.length} position(s)` : ''}`)))
  }
  root.append(h('h3', {}, 'Authoritative validation'))
  root.append(h('p', { class: 'muted' },
    'These checks mirror RoomManifest.buildEntry (the load gate) and RoomValidator (the in-game save gate). The game itself is the authority: run ',
    h('code', {}, 'gradlew validateRooms'), ' from the pocketdungeons folder, or ', h('code', {}, '/reload'), ' in game and read ', h('code', {}, '/dungeon admin manifest list'), '.'))
  return root
}

// ---- inspector -------------------------------------------------------------------

function describe(entry: BlockEntry | undefined): string {
  return entry ? stateKey(entry.state) : '(no block stored: structure void)'
}

export function renderInspector(app: App): HTMLElement {
  const root = h('div')
  const room = app.room
  if (!room) {
    root.append(h('p', { class: 'muted' }, 'Open a room, then use the Inspect tool (6) and click a block.'))
    return root
  }
  const p = app.inspected
  if (p) {
    const e = room.template.get(...p)
    root.append(h('h3', {}, `Block at ${p.join(', ')}`))
    root.append(h('div', {}, describe(e)))
    if (e?.nbt) {
      root.append(h('h3', {}, `Block entity (${e.nbt.getString('id') || 'unknown'}), read only`))
      root.append(h('pre', { class: 'nbt' }, prettyNbt(e.nbt)))
    } else {
      root.append(h('p', { class: 'muted' }, 'No block entity data.'))
    }
  } else {
    root.append(h('p', { class: 'muted' }, 'Use the Inspect tool (6) and click a block to read its block entity data (chests, jigsaws, trial spawners, vaults).'))
  }
  const ents = room.template.getEntities()
  root.append(h('h3', {}, `Entities (${ents.length}), read only`))
  if (ents.length === 0) root.append(h('p', { class: 'muted' }, 'None. Entities in a template are carried through untouched; Clear drops those inside the cleared box.'))
  for (const ent of ents) {
    const nbt = ent.hasCompound('nbt') ? ent.getCompound('nbt') : new NbtCompound()
    const bp = ent.hasList('blockPos') ? ent.getList('blockPos') : null
    const where = bp ? `${bp.getNumber(0)}, ${bp.getNumber(1)}, ${bp.getNumber(2)}` : '?'
    const det = h('details', {}, h('summary', {}, `${nbt.getString('id') || 'entity'} at ${where}`), h('pre', { class: 'nbt' }, prettyNbt(nbt)))
    root.append(det)
  }
  const bes = [...room.template.entries()].filter(x => x.entry.nbt)
  root.append(h('h3', {}, `Block entities (${bes.length})`))
  const counts = new Map<string, number>()
  for (const x of bes) {
    const id = x.entry.nbt!.getString('id') + (x.entry.state.name === 'minecraft:jigsaw' ? ` ${x.entry.nbt!.getString('name')}` : '')
    counts.set(id, (counts.get(id) ?? 0) + 1)
  }
  for (const [id, n] of counts) root.append(h('div', {}, `${n} x ${id}`))
  return root
}

function prettyNbt(tag: NbtCompound): string {
  try {
    return tag.toPrettyString('  ')
  } catch {
    return tag.toString()
  }
}
