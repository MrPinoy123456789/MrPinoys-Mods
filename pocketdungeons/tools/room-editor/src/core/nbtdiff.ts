import type { NbtTag } from 'deepslate/nbt'

/**
 * Structural diff of two NBT trees: tag types, compound key sets, list order
 * and element types, and values. Returns the paths that differ (empty when
 * identical). Compound key order is not significant to the game, so it is not
 * compared here; the byte level check in the round trip test covers it.
 */
export function diffNbt(a: NbtTag | undefined, b: NbtTag | undefined, path = '', out: string[] = [], limit = 20): string[] {
  if (out.length >= limit) return out
  if (!a || !b) {
    if (a !== b) out.push(`${path || '<root>'}: ${a ? 'removed' : 'added'}`)
    return out
  }
  if (a.getId() !== b.getId()) {
    out.push(`${path}: type ${a.getId()} vs ${b.getId()}`)
    return out
  }
  if (a.isCompound() && b.isCompound()) {
    const keys = new Set([...a.keys(), ...b.keys()])
    for (const k of keys) diffNbt(a.get(k), b.get(k), path ? `${path}.${k}` : k, out, limit)
    return out
  }
  if (a.isList() && b.isList()) {
    if (a.length !== b.length) {
      out.push(`${path}: list length ${a.length} vs ${b.length}`)
      return out
    }
    if (a.length > 0 && a.getType() !== b.getType()) out.push(`${path}: list type ${a.getType()} vs ${b.getType()}`)
    for (let i = 0; i < a.length; i++) diffNbt(a.get(i), b.get(i), `${path}[${i}]`, out, limit)
    return out
  }
  if (!a.equals(b)) out.push(`${path}: ${a.toString().slice(0, 60)} vs ${b.toString().slice(0, 60)}`)
  return out
}
