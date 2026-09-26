import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { Identifier } from 'deepslate/core'
import { describe, expect, it } from 'vitest'
import { extractAssetsSync } from '../src/core/jar'
import { RoomResources } from '../src/core/resources'
import { RoomTemplate, isAir } from '../src/core/template'
import { validateRoom } from '../src/core/validate'
import { MC_JAR, META_DIR, ROOMS_DIR, roomFiles } from './env'

const haveJar = existsSync(MC_JAR)

describe.skipIf(!haveJar)('client jar assets', () => {
  let res: RoomResources

  it('extracts blockstates, models and textures and builds the atlas', () => {
    const t0 = Date.now()
    const raw = extractAssetsSync(new Uint8Array(readFileSync(MC_JAR)))
    const t1 = Date.now()
    res = new RoomResources(raw, img => img as unknown as ImageData)
    const t2 = Date.now()
    const r = res.report
    console.log(`ASSETS version ${raw.versionId} dataVersion ${raw.dataVersion}; extract ${t1 - t0} ms, build ${t2 - t1} ms`)
    console.log(`ASSETS blockstates ${r.definitionsOk}/${r.blockstates}, models flattened ${r.modelsFlattened}/${r.models}, textures ${r.texturesDecoded}/${r.textures}, atlas ${r.atlasSize}px`)
    console.log(`ASSETS failures: definitions ${r.definitionsFailed.length}, models ${r.modelsFailed.length}, textures ${r.texturesFailed.length}; patched rotations in ${r.modelsPatched.join(', ') || 'none'}`)
    for (const f of [...r.definitionsFailed, ...r.modelsFailed, ...r.texturesFailed].slice(0, 10)) console.log('  ', f)
    expect(r.definitionsOk).toBe(r.blockstates)
    expect(r.modelsFailed).toEqual([])
    expect(r.texturesFailed).toEqual([])
    expect(r.definitionsOk).toBeGreaterThan(1000)
  })

  it('meshes every block of real rooms through deepslate', () => {
    const files = roomFiles()
    let blocks = 0, meshed = 0, quads = 0
    const unknown = new Set<string>()
    const errors: string[] = []
    const noGeometry = new Set<string>()
    for (const f of files) {
      const t = RoomTemplate.read(new Uint8Array(readFileSync(join(ROOMS_DIR, f))))
      for (const { entry } of t.entries()) {
        if (isAir(entry.state)) continue
        blocks++
        const id = Identifier.parse(entry.state.name)
        const def = res.getBlockDefinition(id)
        if (!def) { unknown.add(entry.state.name); continue }
        try {
          const props = { ...res.defaultsOf(entry.state.name), ...entry.state.props }
          const mesh = def.getMesh(id, props, res, res, {})
          if (mesh.quads.length === 0) noGeometry.add(entry.state.name)
          quads += mesh.quads.length
          meshed++
        } catch (e) {
          errors.push(`${f} ${entry.state.name}: ${(e as Error).message}`)
        }
      }
    }
    console.log(`MESH ${files.length} rooms, ${blocks} non-air blocks, ${meshed} meshed, ${quads} quads; unknown ids: ${[...unknown].join(', ') || 'none'}`)
    console.log(`MESH no model geometry (drawn by special renderers or invisible): ${[...noGeometry].join(', ') || 'none'}`)
    for (const e of errors.slice(0, 10)) console.log('  ', e)
    expect(files.length).toBeGreaterThanOrEqual(5)
    expect(errors).toEqual([])
    expect(unknown.size).toBe(0)
  })

  it('runs the client side checks over every room with its metadata', () => {
    const files = roomFiles()
    let errors = 0, warnings = 0
    for (const f of files) {
      const t = RoomTemplate.read(new Uint8Array(readFileSync(join(ROOMS_DIR, f))))
      const name = f.slice(0, -4)
      const metaPath = join(META_DIR, `${name}.json`)
      const anomalyPath = join(META_DIR, '../anomaly_room', `${name}.json`)
      const path = existsSync(metaPath) ? metaPath : existsSync(anomalyPath) ? anomalyPath : null
      const meta = path ? JSON.parse(readFileSync(path, 'utf8').replace(/^﻿/, '')) : null
      const r = validateRoom(t, meta, `pocketdungeons:rooms/${name}`)
      const e = r.findings.filter(x => x.severity === 'error')
      errors += e.length
      warnings += r.findings.filter(x => x.severity === 'warning').length
      if (e.length) console.log(`CHECK ${f}: ${e.map(x => x.message).join(' | ')}`)
    }
    console.log(`CHECK ${files.length} rooms: ${errors} errors, ${warnings} warnings`)
  })
})
