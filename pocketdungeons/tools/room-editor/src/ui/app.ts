import {
  EDGES, canonicalDoorSlots, maskLetters, rotateMaskClockwise, rotatePosInCell, storyOffset, templateHeight,
  windowBandSlots, type Edge, type Vec3,
} from '../core/geometry'
import {
  History, airEntry, boxOf, boxSize, chestEntry, clearBox, copyBox, fillBox, newRoomTemplate, pasteAt,
  respanTemplate, rotateClipboard, sealDoor, spawnJigsawEntry, stampDoor, transformSelection, doorJigsawEntry,
  type Box, type Clipboard, type Transaction,
} from '../core/edit'
import { assetsFromEntries, extractEntries } from '../core/jar'
import { NAME_PATTERN, formatMeta, newRoomMeta, validateMeta, type Meta } from '../core/meta'
import { RoomResources } from '../core/resources'
import { cloneEntry, stateKey, RoomTemplate, type BlockEntry } from '../core/template'
import { DOOR_NAME, jigsawName, validateRoom, type Finding, type ValidationResult } from '../core/validate'
import { $, h, modal } from './dom'
import { renderChecks, renderInspector, renderMetaForm, renderPalette } from './panels'
import {
  PackFolder, clearAssetCache, download, loadAssetCache, saveAssetCache, type RoomRecord,
} from './storage'
import { TemplateView, Viewer, type PickResult } from './viewer'

export type Tool = 'place' | 'remove' | 'pick' | 'select' | 'paste' | 'inspect'
type Tab = 'palette' | 'meta' | 'checks' | 'inspector'

interface OpenRoom {
  name: string
  kind: RoomRecord['kind']
  record: RoomRecord | null
  templatePath: string
  templateId: string
  template: RoomTemplate
  history: History
  meta: Meta | null
  metaOriginalText: string | null
  metaDirty: boolean
  isNew: boolean
}

const TOOL_KEYS: Record<string, Tool> = { '1': 'place', '2': 'remove', '3': 'pick', '4': 'select', '5': 'paste', '6': 'inspect' }

export class App {
  res: RoomResources | null = null
  folder: PackFolder | null = null
  rooms: RoomRecord[] = []
  room: OpenRoom | null = null
  viewer!: Viewer
  view: TemplateView | null = null
  tool: Tool = 'place'
  tab: Tab = 'palette'
  brush: BlockEntry = { state: { name: 'minecraft:stone_bricks', props: {} } }
  brushLabel = ''
  selA: Vec3 | null = null
  selB: Vec3 | null = null
  clipboard: Clipboard | null = null
  pasteSkipAir = true
  slice = 99
  rotation = 0
  showDoors = true
  showEnvelope = true
  showWindows = true
  hover: PickResult | null = null
  inspected: Vec3 | null = null
  highlight: Vec3[] = []
  validation: ValidationResult | null = null
  metaErrors: string[] = []
  private validateTimer = 0
  paletteFilter = ''
  private atlasUrl: string | null = null

  async start(): Promise<void> {
    this.viewer = new Viewer($('view') as HTMLCanvasElement)
    this.viewer.onBeforeDraw = () => this.buildOverlay()
    this.bindTop()
    this.bindViewport()
    this.renderToolbar()
    this.renderTabs()
    this.renderPanel()
    this.status('Load your client jar to begin.')
    const cached = await loadAssetCache()
    const btn = $('jar-cached') as HTMLButtonElement
    const forget = $('jar-cache-clear') as HTMLButtonElement
    forget.onclick = () => void this.clearCache()
    if (cached) {
      forget.hidden = false
      btn.hidden = false
      btn.textContent = `Use cached assets (${cached.jarName})`
      btn.onclick = () => void this.loadAssetsFromEntries(cached.entries, cached.jarName, false)
    }
    if (import.meta.env.DEV) await this.devHarness()
  }

  // ---- setup: jar and folder ---------------------------------------------

  private bindTop(): void {
    const jarInput = $('jar-input') as HTMLInputElement
    jarInput.onchange = async () => {
      const f = jarInput.files?.[0]
      if (!f) return
      this.status(`Reading ${f.name} (${(f.size / 1e6).toFixed(1)} MB)...`)
      const entries = await extractEntries(new Uint8Array(await f.arrayBuffer()))
      await this.loadAssetsFromEntries(entries, f.name, true, f.size)
      jarInput.value = ''
    }
    const openBtn = $('open-folder') as HTMLButtonElement
    if (!PackFolder.supportsWritable()) {
      openBtn.disabled = true
      openBtn.title = 'This browser has no File System Access API; use a Chromium browser (Chrome, Edge) to save, or open read only.'
    }
    openBtn.onclick = async () => {
      try {
        await this.setFolder(await PackFolder.pickWritable())
      } catch (e) {
        if ((e as Error).name !== 'AbortError') this.status(`Could not open folder: ${(e as Error).message}`, true)
      }
    }
    const folderInput = $('folder-input') as HTMLInputElement
    folderInput.onchange = async () => {
      if (!folderInput.files?.length) return
      try {
        await this.setFolder(await PackFolder.fromFileList(folderInput.files))
      } catch (e) {
        this.status(`Could not open folder: ${(e as Error).message}`, true)
      }
    }
    ;($('save') as HTMLButtonElement).onclick = () => void this.save()
    ;($('new-room') as HTMLButtonElement).onclick = () => void this.newRoom()
    ;($('room-filter') as HTMLInputElement).oninput = () => this.renderRoomList()
    window.addEventListener('beforeunload', e => {
      if (this.isDirty()) e.preventDefault()
    })
  }

  async loadAssetsFromEntries(entries: Record<string, Uint8Array>, jarName: string, cache: boolean, size = 0): Promise<void> {
    try {
      const raw = assetsFromEntries(entries)
      if (Object.keys(raw.blockstates).length === 0) throw new Error('no assets/minecraft/blockstates in this jar; pick the client jar, not the server jar')
      this.res = new RoomResources(raw, img => new ImageData(img.data as unknown as Uint8ClampedArray<ArrayBuffer>, img.width, img.height))
      this.atlasUrl = null
      const r = this.res.report
      const js = $('jar-status')
      js.textContent = `${raw.versionId ?? jarName}: ${r.definitionsOk} blocks`
      js.className = 'pill ok'
      js.title = `blockstates ${r.definitionsOk}/${r.blockstates}, models ${r.modelsFlattened}/${r.models}, textures ${r.texturesDecoded}/${r.textures}`
        + (r.modelsPatched.length ? `; multi axis rotations flattened in ${r.modelsPatched.join(', ')}` : '')
      if (raw.versionId && raw.versionId !== '26.2') this.status(`Jar is ${raw.versionId}; the mod targets 26.2. Rendering may differ.`, true)
      else this.status(`Assets loaded from ${jarName}.`)
      if (cache) await saveAssetCache({ jarName, jarSize: size, savedAt: Date.now(), entries })
      this.viewer.setResources(this.res)
      if (this.view) this.viewer.setView(this.view)
      this.renderPanel()
      this.renderToolbar()
    } catch (e) {
      this.status(`Could not read the jar: ${(e as Error).message}`, true)
    }
  }

  async clearCache(): Promise<void> {
    await clearAssetCache()
    ;($('jar-cached') as HTMLButtonElement).hidden = true
    ;($('jar-cache-clear') as HTMLButtonElement).hidden = true
    this.status('Cached assets removed from this browser.')
  }

  async setFolder(folder: PackFolder): Promise<void> {
    this.folder = folder
    const fs = $('folder-status')
    fs.textContent = `${folder.label} (${folder.namespace})${folder.writable ? '' : ', read only'}`
    fs.className = 'pill ok'
    ;($('new-room') as HTMLButtonElement).disabled = false
    await this.reloadRooms()
    this.status(`${this.rooms.length} rooms in ${folder.label}.`)
  }

  async reloadRooms(): Promise<void> {
    if (!this.folder) return
    this.rooms = await this.folder.listRooms()
    this.renderRoomList()
  }

  private renderRoomList(): void {
    const ul = $('room-list')
    ul.replaceChildren()
    const q = ($('room-filter') as HTMLInputElement).value.toLowerCase()
    for (const r of this.rooms) {
      const roles = Array.isArray(r.meta?.roles) ? (r.meta!.roles as string[]).join(', ') : ''
      const text = `${r.name} ${roles} ${r.meta?.content ?? ''}`.toLowerCase()
      if (q && !text.includes(q)) continue
      const tags: string[] = []
      if (r.kind === 'anomaly_room') tags.push('anomaly')
      if (r.meta?.spanY === 2) tags.push('2 story')
      if (!r.meta) tags.push('no metadata')
      const bad = !r.templateExists || !!r.metaError
      if (!r.templateExists) tags.push('no template')
      if (r.metaError) tags.push('bad json')
      const li = h('li', {
        class: this.room?.name === r.name && this.room.kind === r.kind ? 'sel' : '',
        title: `${r.templateId}${roles ? `\nroles: ${roles}` : ''}`,
        onclick: () => void this.openRoom(r),
      }, h('span', {}, r.name), h('span', { class: bad ? 'tag bad' : 'tag' }, tags.join(', ')))
      ul.append(li)
    }
  }

  // ---- opening and creating rooms ------------------------------------------

  isDirty(): boolean {
    return !!this.room && (this.room.template.isDirty() || this.room.metaDirty || this.room.isNew)
  }

  private async confirmDiscard(): Promise<boolean> {
    if (!this.isDirty()) return true
    return modal('Discard unsaved changes?', h('p', {}, `${this.room!.name} has unsaved changes.`), 'Discard')
  }

  async openRoom(r: RoomRecord): Promise<void> {
    if (!this.folder) return
    if (!(await this.confirmDiscard())) return
    if (!r.templatePath || !r.templateExists) {
      this.status(`The template ${r.templateId} for ${r.name} is not in this folder.`, true)
      return
    }
    const bytes = await this.folder.readTemplate(r.templatePath)
    if (!bytes) {
      this.status(`Could not read ${r.templatePath}.`, true)
      return
    }
    let template: RoomTemplate
    try {
      template = RoomTemplate.read(bytes)
    } catch (e) {
      this.status(`Could not parse ${r.templatePath}: ${(e as Error).message}`, true)
      return
    }
    this.setRoom({
      name: r.name, kind: r.kind, record: r, templatePath: r.templatePath, templateId: r.templateId, template,
      history: new History(template), meta: r.meta ? structuredClone(r.meta) : null, metaOriginalText: r.metaText,
      metaDirty: false, isNew: false,
    })
    const shared = this.rooms.filter(o => o !== r && o.templatePath === r.templatePath).map(o => o.name)
    this.status(`Opened ${r.name}: ${template.size.join(' x ')}, ${template.blockCount()} blocks, ${template.getEntities().length} entities, DataVersion ${template.dataVersion}`
      + (shared.length ? `. Template shared with ${shared.join(', ')}.` : '.')
      + (template.readOnlyReason ? ` Read only: ${template.readOnlyReason}.` : ''))
  }

  private setRoom(room: OpenRoom): void {
    this.room = room
    this.selA = this.selB = null
    this.inspected = null
    this.highlight = []
    this.rotation = 0
    this.slice = room.template.size[1] - 1
    this.view = new TemplateView(room.template)
    this.view.maxY = this.slice
    this.viewer.frame(room.template)
    this.viewer.setView(this.view)
    $('empty-hint').style.display = 'none'
    this.renderRoomList()
    this.renderToolbar()
    this.revalidate(true)
    this.renderPanel()
    this.updateSaveButton()
  }

  async newRoom(): Promise<void> {
    if (!this.folder) return
    if (!(await this.confirmDiscard())) return
    const nameIn = h('input', { value: 'new_room', pattern: '[a-z0-9_-]+' }) as HTMLInputElement
    const spanIn = h('select', {}, h('option', { value: '1' }, '1 (one story, 16 x 7 x 16)'), h('option', { value: '2' }, '2 (with lower story, 16 x 15 x 16)')) as HTMLSelectElement
    const doorBoxes = EDGES.map(e => h('input', { type: 'checkbox', checked: e === 'north' || e === 'south' }) as HTMLInputElement)
    const body = h('div', {},
      h('div', { class: 'field' }, h('span', {}, 'Room id'), nameIn, h('span', { class: 'hint' }, `lowercase a-z, 0-9, _ and -; becomes ${this.folder.namespace}:<id>`)),
      h('div', { class: 'field' }, h('span', {}, 'spanY'), spanIn),
      h('div', { class: 'field' }, h('span', {}, 'Doors'), h('span', { class: 'checks' },
        ...EDGES.map((e, i) => h('label', {}, doorBoxes[i], ` ${e}`)))))
    if (!(await modal('New room', body, 'Create'))) return
    const name = nameIn.value.trim()
    if (!NAME_PATTERN.test(name)) {
      this.status(`"${name}" is not a valid room id: use lowercase a-z, 0-9, _ and -.`, true)
      return
    }
    const clash = this.rooms.some(r => r.name === name)
      || await this.folder.exists(`dungeon_room/${name}.json`) || await this.folder.exists(`anomaly_room/${name}.json`)
      || await this.folder.exists(`structure/rooms/${name}.nbt`)
    if (clash) {
      this.status(`A room or template named ${name} already exists; pick a unique id.`, true)
      return
    }
    const spanY = Number(spanIn.value)
    const doors = EDGES.filter((_, i) => doorBoxes[i].checked)
    const dv = this.res?.dataVersion ?? this.mostCommonDataVersion()
    const template = newRoomTemplate(spanY, doors, dv)
    const meta = newRoomMeta(this.folder.namespace, name, spanY)
    this.setRoom({
      name, kind: 'dungeon_room', record: null, templatePath: `structure/rooms/${name}.nbt`,
      templateId: `${this.folder.namespace}:rooms/${name}`, template, history: new History(template),
      meta, metaOriginalText: null, metaDirty: true, isNew: true,
    })
    this.status(`New room ${name}: ${template.size.join(' x ')}, doors ${maskLetters(this.validation?.mask ?? 0)}. Not saved yet.`)
  }

  private mostCommonDataVersion(): number {
    return this.room?.template.dataVersion || 4903
  }

  // ---- saving ------------------------------------------------------------------

  private updateSaveButton(): void {
    const b = $('save') as HTMLButtonElement
    b.disabled = !this.room
    b.textContent = this.folder && !this.folder.writable ? 'Download' : this.isDirty() ? 'Save *' : 'Save'
  }

  async save(): Promise<void> {
    const room = this.room
    if (!room || !this.folder) return
    if (room.template.readOnlyReason) {
      this.status(`Cannot save: ${room.template.readOnlyReason}.`, true)
      return
    }
    this.revalidate(true)
    const errors = [...(this.validation?.findings ?? []).filter(f => f.severity === 'error').map(f => f.message), ...this.metaErrors]
    if (errors.length > 0) {
      const ok = await modal('Save with errors?', h('div', {},
        h('p', {}, `The client side checks found ${errors.length} error(s). The game will reject or mis-stamp this room until they are fixed:`),
        h('ul', {}, ...errors.slice(0, 8).map(e => h('li', {}, e))),
        h('p', { class: 'muted' }, 'The authoritative check is the game itself: run gradlew validateRooms, or /dungeon admin manifest reload in game.')), 'Save anyway')
      if (!ok) return
    }
    const nbtBytes = room.template.isDirty() || room.isNew ? room.template.write() : null
    const metaText = room.meta && (room.metaDirty || room.isNew) ? formatMeta(room.meta, room.metaOriginalText) : null
    if (!this.folder.writable) {
      if (nbtBytes || !metaText) download(`${room.templatePath.split('/').pop()}`, nbtBytes ?? room.template.write(true))
      if (metaText) download(`${room.name}.json`, metaText, 'application/json')
      this.status('Folder is read only: downloaded the edited files instead.')
      return
    }
    const done: string[] = []
    try {
      if (nbtBytes) {
        const backup = await this.folder.writeWithBackup(room.templatePath, nbtBytes)
        room.template.markClean()
        done.push(`${room.templatePath}${backup ? ` (previous copy in ${backup})` : ''}`)
      }
      if (metaText) {
        const rel = `${room.kind}/${room.name}.json`
        const backup = await this.folder.writeWithBackup(rel, metaText)
        room.metaOriginalText = metaText
        room.metaDirty = false
        done.push(`${rel}${backup ? ` (previous copy in ${backup})` : ''}`)
      }
      room.isNew = false
      await this.reloadRooms()
      room.record = this.rooms.find(r => r.name === room.name && r.kind === room.kind) ?? room.record
      this.status(done.length ? `Saved ${done.join('; ')}. In game: /reload, then /dungeon roombuilder load ${room.name}.` : 'Nothing to save.')
    } catch (e) {
      this.status(`Save failed: ${(e as Error).message}`, true)
    }
    this.updateSaveButton()
    this.renderRoomList()
  }

  // ---- editing -------------------------------------------------------------

  canEdit(): string | null {
    if (!this.room) return 'no room open'
    if (this.room.template.readOnlyReason) return this.room.template.readOnlyReason
    if (this.rotation !== 0) return 'rotation preview is on; return to 0 degrees to edit'
    return null
  }

  run(label: string, fn: Parameters<History['run']>[1]): Transaction | null {
    const why = this.canEdit()
    if (why) {
      this.status(`Cannot edit: ${why}.`, true)
      return null
    }
    const tx = this.room!.history.run(label, fn)
    if (tx) this.afterChange(tx.changes.map(c => c.pos))
    return tx
  }

  afterChange(positions?: Vec3[]): void {
    this.viewer.refresh(positions)
    this.revalidate()
    this.updateSaveButton()
    this.renderToolbar()
  }

  undo(): void {
    const tx = this.room?.history.undo()
    if (tx) { this.afterChange(tx.changes.map(c => c.pos)); this.status(`Undid ${tx.label}.`) }
  }

  redo(): void {
    const tx = this.room?.history.redo()
    if (tx) { this.afterChange(tx.changes.map(c => c.pos)); this.status(`Redid ${tx.label}.`) }
  }

  selection(): Box | null {
    if (!this.selA) return null
    return boxOf(this.selA, this.selB ?? this.selA)
  }

  brushEntry(target?: Vec3): BlockEntry {
    const b = this.brush
    if (b.state.name === 'minecraft:jigsaw' && b.nbt && jigsawName(b) === DOOR_NAME && target && this.room) {
      // Door jigsaws face the wall they sit on.
      const t = this.room.template
      const edge = target[2] === 0 ? 'north' : target[2] === t.size[2] - 1 ? 'south' : target[0] === 0 ? 'west' : target[0] === t.size[0] - 1 ? 'east' : null
      if (edge) return doorJigsawEntry(t, edge)
    }
    return cloneEntry(b)
  }

  setBrush(entry: BlockEntry, label = ''): void {
    this.brush = entry
    this.brushLabel = label
    if (this.tool !== 'place' && this.tool !== 'select') this.setTool('place')
    this.renderPanel()
    this.renderToolbar()
  }

  setTool(t: Tool): void {
    this.tool = t
    this.renderToolbar()
    this.viewer.requestDraw()
    if (t === 'inspect') this.setTab('inspector')
  }

  setTab(t: Tab): void {
    this.tab = t
    this.renderTabs()
    this.renderPanel()
  }

  fillSelection(): void {
    const box = this.selection()
    if (!box) return this.status('Select a box first (tool 4).', true)
    const entry = this.brushEntry()
    this.run(`fill ${stateKey(entry.state)}`, tx => fillBox(tx, box, entry))
  }

  clearSelection(): void {
    const box = this.selection()
    if (!box) return this.status('Select a box first (tool 4).', true)
    this.run('clear', tx => clearBox(tx, box))
  }

  copySelection(): void {
    const box = this.selection()
    if (!box || !this.room) return this.status('Select a box first (tool 4).', true)
    this.clipboard = copyBox(this.room.template, box)
    this.status(`Copied ${this.clipboard.blocks.length} blocks (${boxSize(box).join(' x ')}). Tool 5 pastes.`)
    this.renderToolbar()
  }

  transformSel(op: { rotate?: number; mirror?: 'x' | 'z' }): void {
    const box = this.selection()
    if (!box || !this.room) return this.status('Select a box first (tool 4).', true)
    let next: Box | null = null
    this.run(op.rotate ? 'rotate selection' : `mirror selection ${op.mirror}`, tx => { next = transformSelection(this.room!.template, tx, box, op) })
    if (next) { this.selA = (next as Box).min; this.selB = (next as Box).max }
  }

  rotateClipboard(): void {
    if (!this.clipboard) return
    this.clipboard = rotateClipboard(this.clipboard, 1)
    this.status(`Clipboard rotated: ${this.clipboard.size.join(' x ')}.`)
    this.viewer.requestDraw()
  }

  toggleDoor(edge: Edge): void {
    const room = this.room
    if (!room) return
    const spanY = this.spanY()
    const has = ((this.validation?.mask ?? 0) & ({ north: 1, east: 2, south: 4, west: 8 }[edge])) !== 0
    this.run(`${has ? 'seal' : 'open'} ${edge} door`, tx => (has ? sealDoor(room.template, tx, edge, spanY) : stampDoor(room.template, tx, edge, spanY)))
  }

  spanY(): number {
    const s = this.room?.meta?.spanY
    return typeof s === 'number' ? s : 1
  }

  /** Metadata edits come through here so spanY can resize the template. */
  async updateMeta(next: Meta): Promise<void> {
    const room = this.room
    if (!room) return
    const prevSpan = this.spanY()
    const nextSpan = typeof next.spanY === 'number' ? next.spanY : 1
    if (nextSpan !== prevSpan && (nextSpan === 1 || nextSpan === 2) && room.template.size[1] === templateHeight(prevSpan)) {
      const ok = await modal('Resize the template?', h('p', {},
        nextSpan > prevSpan
          ? `spanY ${nextSpan} adds a lower story: the template grows to ${templateHeight(nextSpan)} tall, the current room becomes the upper story, and the new lower story gets a plain shell.`
          : `spanY ${nextSpan} removes the lower story: its blocks are dropped and the template shrinks to ${templateHeight(nextSpan)} tall.`,
        ' Undo history is cleared by a resize.'), 'Resize')
      if (!ok) { this.renderPanel(); return }
      const t = respanTemplate(room.template, prevSpan, nextSpan)
      room.template = t
      room.history = new History(t)
      room.meta = next
      room.metaDirty = true
      this.setRoom(room)
      return
    }
    room.meta = next
    room.metaDirty = true
    this.revalidate()
    this.updateSaveButton()
  }

  createMetaForOrphan(): void {
    const room = this.room
    if (!room || !this.folder) return
    room.meta = newRoomMeta(this.folder.namespace, room.name, room.template.size[1] === templateHeight(2) ? 2 : 1)
    room.meta.template = room.templateId
    room.metaDirty = true
    this.revalidate(true)
    this.renderPanel()
    this.updateSaveButton()
  }

  // ---- validation ----------------------------------------------------------------

  revalidate(now = false): void {
    clearTimeout(this.validateTimer)
    const go = () => {
      if (!this.room) return
      this.validation = validateRoom(this.room.template, this.room.meta, this.room.templateId)
      this.metaErrors = this.room.meta ? validateMeta(this.room.meta) : []
      if (this.tab === 'checks') this.renderPanel()
      this.renderToolbar()
      this.viewer.requestDraw()
    }
    if (now) go()
    else this.validateTimer = window.setTimeout(go, 120)
  }

  showFinding(f: Finding): void {
    this.highlight = f.positions ?? []
    if (this.highlight.length > 0) {
      const top = Math.max(...this.highlight.map(p => p[1]))
      if (top > this.slice) this.setSlice(top)
    }
    this.viewer.requestDraw()
  }

  // ---- view state ------------------------------------------------------------

  setSlice(y: number): void {
    if (!this.room) return
    this.slice = Math.max(0, Math.min(this.room.template.size[1] - 1, y))
    if (this.view) this.view.maxY = this.slice
    this.viewer.refresh()
    this.renderToolbar()
  }

  setRotation(q: number): void {
    this.rotation = ((q % 4) + 4) % 4
    if (this.view) this.view.rotation = this.rotation
    this.viewer.refresh()
    this.renderToolbar()
    this.status(this.rotation ? `Previewing the ${this.rotation * 90} degree clockwise rotation the stamper uses (door mask ${maskLetters(rotateMaskClockwise(this.validation?.mask ?? 0, this.rotation))}). Editing is off.` : 'Back at rotation 0.')
  }

  // ---- viewport input ------------------------------------------------------------

  private bindViewport(): void {
    const c = $('view') as HTMLCanvasElement
    c.tabIndex = 0
    let down: { x: number; y: number; button: number; shift: boolean; moved: boolean } | null = null
    c.addEventListener('contextmenu', e => e.preventDefault())
    c.addEventListener('pointerdown', e => {
      c.focus()
      c.setPointerCapture(e.pointerId)
      down = { x: e.clientX, y: e.clientY, button: e.button, shift: e.shiftKey, moved: false }
    })
    c.addEventListener('pointermove', e => {
      if (down) {
        const dx = e.clientX - down.x, dy = e.clientY - down.y
        if (!down.moved && Math.hypot(dx, dy) > 4) down.moved = true
        if (down.moved) {
          if (down.button === 2 && e.shiftKey || down.button === 1) this.viewer.pan(e.movementX, e.movementY)
          else this.viewer.orbit(e.movementX, e.movementY)
        }
        return
      }
      this.hover = this.pick(e.clientX, e.clientY)
      this.updateHoverStatus()
      this.viewer.requestDraw()
    })
    c.addEventListener('pointerup', e => {
      const d = down
      down = null
      if (d && !d.moved && d.button === 0) this.click(e.clientX, e.clientY, e.shiftKey)
    })
    c.addEventListener('wheel', e => { e.preventDefault(); this.viewer.zoom(e.deltaY) }, { passive: false })
    c.addEventListener('pointerleave', () => { this.hover = null; this.viewer.requestDraw() })
    window.addEventListener('keydown', e => this.key(e))
  }

  private pick(x: number, y: number): PickResult {
    return this.viewer.pick(x, y, 0)
  }

  private toTemplate(p: Vec3 | null): Vec3 | null {
    return p && this.view ? this.view.toTemplate(p) : p
  }

  private click(x: number, y: number, shift: boolean): void {
    if (!this.room) return
    const pick = this.pick(x, y)
    const hit = this.toTemplate(pick.hit)
    const place = this.toTemplate(pick.place)
    const t = this.room.template
    switch (this.tool) {
      case 'place': {
        if (!place || !t.inBounds(...place)) return this.status('Outside the template box: that is the bedrock envelope, off limits.', true)
        const entry = this.brushEntry(place)
        this.run(`place ${entry.state.name}`, tx => tx.set(place, entry))
        break
      }
      case 'remove':
        if (!hit) return
        this.run(`remove ${t.get(...hit)?.state.name ?? 'block'}`, tx => tx.set(hit, airEntry()))
        break
      case 'pick': {
        if (!hit) return
        const e = t.get(...hit)
        if (e) this.setBrush(cloneEntry(e), e.nbt ? 'picked, with block entity data' : 'picked')
        break
      }
      case 'select': {
        const p = hit ?? place
        if (!p) return
        if (!this.selA || (this.selB && !shift)) { this.selA = p; this.selB = null }
        else this.selB = p
        const box = this.selection()!
        this.status(`Selection ${box.min.join(',')} to ${box.max.join(',')} (${boxSize(box).join(' x ')}). F fill, Delete clear, Ctrl+C copy, R/M rotate/mirror.`)
        this.renderToolbar()
        break
      }
      case 'paste': {
        if (!this.clipboard) return this.status('Nothing copied yet.', true)
        const o = place ?? hit
        if (!o) return
        const clip = this.clipboard
        this.run('paste', tx => pasteAt(tx, clip, o, this.pasteSkipAir))
        break
      }
      case 'inspect':
        this.inspected = hit
        this.setTab('inspector')
        break
    }
    this.viewer.requestDraw()
  }

  private updateHoverStatus(): void {
    if (!this.room || !this.hover) return
    const p = this.toTemplate(this.hover.hit)
    const e = p ? this.room.template.get(...p) : undefined
    const place = this.toTemplate(this.hover.place)
    const where = p ? `${p.join(', ')}  ${e ? stateKey(e.state) : ''}${e?.nbt ? '  {block entity}' : ''}` : place ? `floor ${place.join(', ')}` : ''
    $('status').textContent = where
  }

  private key(e: KeyboardEvent): void {
    const target = e.target as HTMLElement
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT')) return
    const ctrl = e.ctrlKey || e.metaKey
    if (ctrl && e.key.toLowerCase() === 'z') { e.preventDefault(); if (e.shiftKey) this.redo(); else this.undo(); return }
    if (ctrl && e.key.toLowerCase() === 'y') { e.preventDefault(); this.redo(); return }
    if (ctrl && e.key.toLowerCase() === 's') { e.preventDefault(); void this.save(); return }
    if (ctrl && e.key.toLowerCase() === 'c') { this.copySelection(); return }
    if (ctrl && e.key.toLowerCase() === 'v') { this.setTool('paste'); return }
    if (ctrl) return
    if (TOOL_KEYS[e.key]) { this.setTool(TOOL_KEYS[e.key]); return }
    switch (e.key) {
      case 'Delete': case 'Backspace': this.clearSelection(); break
      case 'f': this.fillSelection(); break
      case 'r': if (this.tool === 'paste') this.rotateClipboard(); else this.transformSel({ rotate: 1 }); break
      case 'm': this.transformSel({ mirror: 'x' }); break
      case 'n': this.transformSel({ mirror: 'z' }); break
      case '[': case 'PageDown': this.setSlice(this.slice - 1); break
      case ']': case 'PageUp': this.setSlice(this.slice + 1); break
      case 'Escape': this.selA = this.selB = null; this.highlight = []; this.renderToolbar(); this.viewer.requestDraw(); break
    }
  }

  // ---- overlays --------------------------------------------------------------------

  private buildOverlay(): void {
    const o = this.viewer.overlay
    const top = this.viewer.overlayOnTop
    o.clear()
    top.clear()
    if (!this.room || !this.view) return
    const t = this.room.template
    const [sx, sy, sz] = t.size
    const q = this.rotation
    const rot = (p: Vec3): Vec3 => {
      if (q === 0) return p
      const [x, z] = rotatePosInCell(p[0], p[2], q)
      return [x, p[1], z]
    }
    const spanY = this.spanY()
    const off = storyOffset(spanY)
    const mask = this.validation?.mask ?? 0

    if (this.showEnvelope) {
      // The bedrock envelope: one block outside the template on every side.
      // Filled below and around, outlined only above so it never hides the room.
      const c: [number, number, number, number] = [0.55, 0.12, 0.12, 0.10]
      const e: [number, number, number, number] = [0.85, 0.3, 0.3, 0.55]
      o.box(-1, -1, -1, sx + 1, 0, sz + 1, c, e)
      o.box(-1, sy, -1, sx + 1, sy + 1, sz + 1, null, e)
      o.box(-1, 0, -1, 0, sy, sz + 1, c, null)
      o.box(sx, 0, -1, sx + 1, sy, sz + 1, c, null)
      o.box(0, 0, -1, sx, sy, 0, c, null)
      o.box(0, 0, sz, sx, sy, sz + 1, c, null)
    }
    if (spanY > 1 && sy === templateHeight(spanY)) {
      // The upper story's floor: everything below is the private lower story.
      const y = off
      o.line([0, y, 0], [sx, y, 0], [0.9, 0.7, 0.2, 0.9])
      o.line([sx, y, 0], [sx, y, sz], [0.9, 0.7, 0.2, 0.9])
      o.line([sx, y, sz], [0, y, sz], [0.9, 0.7, 0.2, 0.9])
      o.line([0, y, sz], [0, y, 0], [0.9, 0.7, 0.2, 0.9])
    }
    if (this.showDoors) {
      for (const edge of EDGES) {
        const active = (mask & ({ north: 1, east: 2, south: 4, west: 8 }[edge])) !== 0
        for (const p of canonicalDoorSlots(edge, off)) {
          const b = t.get(...p)
          const ok = b && jigsawName(b) === DOOR_NAME && (b.state.props.orientation ?? '').startsWith(edge)
          const v = rot(p)
          if (active) o.cell(v[0], v[1], v[2], ok ? [0.2, 0.9, 0.4, 0.28] : [1, 0.2, 0.2, 0.4], ok ? [0.3, 1, 0.5, 0.9] : [1, 0.3, 0.3, 1])
          else o.cell(v[0], v[1], v[2], null, [0.7, 0.7, 0.8, 0.35])
        }
        if (this.showWindows) {
          for (const p of windowBandSlots(edge, off)) {
            const v = rot(p)
            o.cell(v[0], v[1], v[2], active && this.room.meta?.window !== 'none' ? [1, 0.85, 0.2, 0.3] : null, [1, 0.85, 0.2, active ? 0.9 : 0.3])
          }
        }
      }
    }
    for (const p of this.highlight) {
      const v = rot(p)
      top.cell(v[0], v[1], v[2], [1, 0.5, 0, 0.25], [1, 0.6, 0.1, 1], -0.03)
    }
    const sel = this.selection()
    if (sel) {
      const a = rot(sel.min), b = rot(sel.max)
      top.box(Math.min(a[0], b[0]) - 0.02, Math.min(a[1], b[1]) - 0.02, Math.min(a[2], b[2]) - 0.02,
        Math.max(a[0], b[0]) + 1.02, Math.max(a[1], b[1]) + 1.02, Math.max(a[2], b[2]) + 1.02, [0.3, 0.8, 1, 0.1], [0.4, 0.9, 1, 1])
    }
    if (this.inspected) {
      const v = rot(this.inspected)
      top.cell(v[0], v[1], v[2], null, [1, 1, 1, 1], -0.04)
    }
    const hv = this.hover
    if (hv && this.rotation === 0) {
      const target = this.tool === 'place' || this.tool === 'paste' ? hv.place : hv.hit
      if (target) {
        if (this.tool === 'paste' && this.clipboard) {
          const s = this.clipboard.size
          top.box(target[0], target[1], target[2], target[0] + s[0], target[1] + s[1], target[2] + s[2], [0.6, 0.4, 1, 0.1], [0.7, 0.5, 1, 1])
        } else {
          const inside = t.inBounds(...target)
          const col: [number, number, number, number] = this.tool === 'remove' ? [1, 0.3, 0.3, 1] : inside ? [1, 1, 1, 1] : [1, 0.2, 0.2, 1]
          top.cell(target[0], target[1], target[2], this.tool === 'place' && inside ? [1, 1, 1, 0.12] : null, col, -0.02)
        }
      }
    }
  }

  // ---- toolbar, tabs, panels ------------------------------------------------------

  renderToolbar(): void {
    const bar = $('toolbar')
    bar.replaceChildren()
    const room = this.room
    const tool = (t: Tool, label: string, key: string, title: string) =>
      h('button', { class: `btn${this.tool === t ? ' active' : ''}`, title: `${title} (${key})`, onclick: () => this.setTool(t) }, label)
    bar.append(
      tool('place', 'Place', '1', 'Click a face to place the palette block'),
      tool('remove', 'Remove', '2', 'Click a block to replace it with air'),
      tool('pick', 'Pick', '3', 'Click a block to copy it (with block entity data) into the palette'),
      tool('select', 'Select', '4', 'Click two corners; Shift+click moves the second corner'),
      tool('paste', 'Paste', '5', 'Click to paste the clipboard; R rotates the clipboard'),
      tool('inspect', 'Inspect', '6', 'Click a block to read its block entity data'),
      h('span', { class: 'sep' }),
      h('button', { class: 'btn', disabled: !this.selA, onclick: () => this.fillSelection(), title: 'Fill the selection with the palette block (F)' }, 'Fill'),
      h('button', { class: 'btn', disabled: !this.selA, onclick: () => this.clearSelection(), title: 'Clear the selection to air; entities inside are dropped (Delete)' }, 'Clear'),
      h('button', { class: 'btn', disabled: !this.selA, onclick: () => this.copySelection(), title: 'Copy the selection (Ctrl+C)' }, 'Copy'),
      h('button', { class: 'btn', disabled: !this.selA, onclick: () => this.transformSel({ rotate: 1 }), title: 'Rotate the selection 90 degrees clockwise in place (R)' }, 'Rotate'),
      h('button', { class: 'btn', disabled: !this.selA, onclick: () => this.transformSel({ mirror: 'x' }), title: 'Mirror east and west (M)' }, 'Mirror E-W'),
      h('button', { class: 'btn', disabled: !this.selA, onclick: () => this.transformSel({ mirror: 'z' }), title: 'Mirror north and south (N)' }, 'Mirror N-S'),
      h('span', { class: 'sep' }),
      h('button', { class: 'btn', disabled: !room?.history.canUndo(), title: `Undo ${room?.history.peekUndo() ?? ''} (Ctrl+Z)`, onclick: () => this.undo() }, 'Undo'),
      h('button', { class: 'btn', disabled: !room?.history.canRedo(), title: `Redo ${room?.history.peekRedo() ?? ''} (Ctrl+Y)`, onclick: () => this.redo() }, 'Redo'),
      h('span', { class: 'sep' }),
    )
    if (room) {
      const maxY = room.template.size[1] - 1
      const slider = h('input', { type: 'range', min: 0, max: maxY, value: this.slice, title: 'Show layers up to this y ([ and ])' }) as HTMLInputElement
      slider.oninput = () => this.setSlice(Number(slider.value))
      bar.append(h('label', {}, 'Layers to y', slider, h('span', {}, `${this.slice}/${maxY}`)))
      bar.append(h('span', { class: 'sep' }))
      const mask = this.validation?.mask ?? 0
      bar.append(h('label', { title: 'Door mask from the template jigsaws. Click a wall to stamp or seal its canonical door slots.' }, 'Doors'))
      for (const edge of EDGES) {
        const on = (mask & ({ north: 1, east: 2, south: 4, west: 8 }[edge])) !== 0
        bar.append(h('button', { class: `btn small${on ? ' active' : ''}`, title: `${on ? 'Seal' : 'Stamp'} the ${edge} door`, onclick: () => this.toggleDoor(edge) }, edge[0].toUpperCase()))
      }
      bar.append(h('span', { class: 'sep' }))
      bar.append(h('label', { title: 'Preview the rotations TemplateStamper places this room at' }, 'Rotate view'))
      for (let q = 0; q < 4; q++) {
        bar.append(h('button', { class: `btn small${this.rotation === q ? ' active' : ''}`, onclick: () => this.setRotation(q) }, `${q * 90}`))
      }
      bar.append(h('span', { class: 'sep' }))
      const toggle = (label: string, get: () => boolean, set: (v: boolean) => void, title: string) => {
        const cb = h('input', { type: 'checkbox', checked: get() }) as HTMLInputElement
        cb.onchange = () => { set(cb.checked); this.viewer.requestDraw() }
        return h('label', { title }, cb, label)
      }
      bar.append(
        toggle('door slots', () => this.showDoors, v => { this.showDoors = v }, 'Canonical door slots: green complete, red partial, grey unused wall'),
        toggle('windows', () => this.showWindows, v => { this.showWindows = v }, 'Window band: the outer columns at eye height the stamper cuts beside a doorway'),
        toggle('envelope', () => this.showEnvelope, v => { this.showEnvelope = v }, 'Bedrock envelope: the one block shell outside the template, off limits'),
        toggle('grid', () => this.viewer.showGrid, v => { this.viewer.showGrid = v }, 'Cell grid and template bounds'),
        h('button', { class: 'btn small', title: 'Save a PNG of the view', onclick: () => this.saveScreenshot() }, 'PNG'),
      )
    }
  }

  saveScreenshot(): void {
    const url = this.viewer.screenshot()
    const a = document.createElement('a')
    a.href = url
    a.download = `${this.room?.name ?? 'room'}.png`
    a.click()
  }

  private renderTabs(): void {
    const nav = $('tabs')
    nav.replaceChildren()
    const tabs: [Tab, string][] = [['palette', 'Palette'], ['meta', 'Metadata'], ['checks', 'Checks'], ['inspector', 'Inspector']]
    for (const [t, label] of tabs) {
      nav.append(h('button', { class: this.tab === t ? 'active' : '', onclick: () => this.setTab(t) }, label))
    }
  }

  renderPanel(): void {
    const panel = $('panel')
    panel.replaceChildren()
    switch (this.tab) {
      case 'palette': panel.append(renderPalette(this)); break
      case 'meta': panel.append(renderMetaForm(this)); break
      case 'checks': panel.append(renderChecks(this)); break
      case 'inspector': panel.append(renderInspector(this)); break
    }
  }

  atlasDataUrl(): string | null {
    if (!this.res) return null
    if (!this.atlasUrl) {
      const img = this.res.atlasPixels()
      const c = document.createElement('canvas')
      c.width = img.width
      c.height = img.height
      c.getContext('2d')!.putImageData(new ImageData(img.data as unknown as Uint8ClampedArray<ArrayBuffer>, img.width, img.height), 0, 0)
      this.atlasUrl = c.toDataURL()
    }
    return this.atlasUrl
  }

  presets(): { label: string; entry: () => BlockEntry; title: string }[] {
    const t = this.room?.template
    return [
      { label: 'Door jigsaw', title: 'pocketdungeons:door; faces whichever wall it is placed on', entry: () => (t ? doorJigsawEntry(t, 'north') : chestEntry()) },
      { label: 'Spawn jigsaw', title: 'pocketdungeons:spawn mob spawn point', entry: () => (t ? spawnJigsawEntry(t) : chestEntry()) },
      { label: 'Chest (tier_1)', title: 'Chest with the pocketdungeons:chests/tier_1 placeholder loot table', entry: () => chestEntry() },
      { label: 'Air', title: 'Air (what Remove writes)', entry: () => airEntry() },
    ]
  }

  status(msg: string, bad = false): void {
    const s = $('status')
    s.textContent = msg
    s.className = bad ? 'err' : ''
  }

  // ---- dev harness -------------------------------------------------------------------

  /**
   * Development only (never in a build): when the dev server was started with
   * MC_JAR and PACK_DIR, ?dev=1 loads both without pickers, so the renderer
   * can be exercised by an automated browser. ?room=name opens a room.
   */
  private async devHarness(): Promise<void> {
    const q = new URLSearchParams(location.search)
    if (!q.has('dev')) return
    try {
      const jar = await fetch('/__dev/jar')
      if (jar.ok) {
        const entries = await extractEntries(new Uint8Array(await jar.arrayBuffer()))
        await this.loadAssetsFromEntries(entries, 'dev jar', false)
      }
      const idx = await fetch('/__dev/pack/index.json')
      if (idx.ok) await this.setFolder(await PackFolder.fromDevServer('/__dev/pack'))
      const room = q.get('room')
      const rec = room ? this.rooms.find(r => r.name === room) : undefined
      if (rec) await this.openRoom(rec)
      if (q.has('rot')) this.setRotation(Number(q.get('rot')))
      if (q.has('slice')) this.setSlice(Number(q.get('slice')))
      if (q.has('yaw')) { this.viewer.yaw = Number(q.get('yaw')); this.viewer.requestDraw() }
      if (q.has('pitch')) { this.viewer.pitch = Number(q.get('pitch')); this.viewer.requestDraw() }
      ;(window as unknown as { __devReady: boolean }).__devReady = true
    } catch (e) {
      this.status(`dev harness: ${(e as Error).message}`, true)
    }
  }

  /** Dev harness only: hands a PNG of the view to the dev server's SHOT_DIR. */
  async devShot(name: string): Promise<void> {
    const blob = await (await fetch(this.viewer.screenshot())).blob()
    await fetch(`/__dev/shot?name=${encodeURIComponent(name)}`, { method: 'POST', body: blob })
  }
}
