import Ajv2020, { type ErrorObject, type ValidateFunction } from 'ajv/dist/2020'
import schema from '../../../../docs/schema/dungeon_room.schema.json'

/**
 * Room metadata against the published schema (docs/schema/dungeon_room.schema.json,
 * imported at build time so there is one source of truth). The schema is the
 * documented contract; the game's own parser is DungeonRoomMeta.fromJson.
 */

export type Meta = Record<string, unknown>

export interface SchemaProp {
  type?: string
  enum?: string[]
  items?: { type?: string; enum?: string[]; $ref?: string }
  minItems?: number
  maxItems?: number
  minimum?: number
  maximum?: number
  default?: unknown
  pattern?: string
  $comment?: string
}

export const ROOM_SCHEMA = schema as unknown as {
  properties: Record<string, SchemaProp>
  required: string[]
  $defs: Record<string, { enum?: string[] }>
}

let validator: ValidateFunction | null = null

function getValidator(): ValidateFunction {
  if (!validator) {
    const ajv = new Ajv2020({ allErrors: true, strict: false })
    validator = ajv.compile(schema as object)
  }
  return validator
}

export function validateMeta(meta: unknown): string[] {
  const v = getValidator()
  if (v(meta)) return []
  return (v.errors ?? []).map((e: ErrorObject) => {
    const where = e.instancePath ? e.instancePath.slice(1).replace(/\//g, '.') : '(root)'
    if (e.keyword === 'additionalProperties') return `${where}: unknown field "${(e.params as { additionalProperty: string }).additionalProperty}"`
    if (e.keyword === 'enum') return `${where}: must be one of ${(e.params as { allowedValues: unknown[] }).allowedValues.join(', ')}`
    return `${where}: ${e.message}`
  })
}

/** The enum values for a property, following a $ref into $defs. */
export function enumFor(prop: SchemaProp): string[] | null {
  if (prop.enum) return prop.enum
  if (prop.items?.enum) return prop.items.enum
  const ref = prop.items?.$ref
  if (ref?.startsWith('#/$defs/')) return ROOM_SCHEMA.$defs[ref.slice(8)]?.enum ?? null
  return null
}

/**
 * Serialises metadata the way the shipped files look: two space indent,
 * short arrays of plain values on one line, keys in their original order with
 * new keys appended in schema order.
 */
export function formatMeta(meta: Meta, originalText?: string | null): string {
  const order = Object.keys(ROOM_SCHEMA.properties)
  const existing = originalText ? safeKeys(originalText) : []
  const keys = Object.keys(meta)
  const sorted = [
    ...existing.filter(k => keys.includes(k)),
    ...order.filter(k => keys.includes(k) && !existing.includes(k)),
    ...keys.filter(k => !existing.includes(k) && !order.includes(k)),
  ]
  const ordered: Meta = {}
  for (const k of sorted) ordered[k] = meta[k]
  let text = JSON.stringify(ordered, null, 2)
  text = text.replace(/\[\s*\n\s*([^[\]{}]*?)\s*\n\s*\]/g, (_m, inner: string) => `[${inner.split(/,\s*\n\s*/).join(', ')}]`)
  const trailing = originalText ? /\n$/.test(originalText) : true
  return text + (trailing ? '\n' : '')
}

function safeKeys(text: string): string[] {
  try {
    const o = JSON.parse(text.replace(/^﻿/, ''))
    return o && typeof o === 'object' ? Object.keys(o) : []
  } catch {
    return []
  }
}

export function newRoomMeta(namespace: string, name: string, spanY: number): Meta {
  const m: Meta = {
    template: `${namespace}:rooms/${name}`,
    footprint: [1, 1],
    roles: ['loot'],
    weight: 1,
    minDepth: 0,
    maxPerDungeon: -1,
  }
  if (spanY > 1) m.spanY = spanY
  return m
}

export const NAME_PATTERN = /^[a-z0-9_-]+$/
