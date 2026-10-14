// The read cursor for `server wait` and `server chat` (PD-118): one small JSON file
// holding the byte offset into latest.log that the next read starts from. A torn
// write or a corrupt file used to reset the offset to 0, replaying the whole log,
// or skip past lines that were never printed. These two functions are the only
// way the file is touched: writes are atomic (a temp file renamed over the real
// one, so a reader never sees half of it) and a read that cannot parse keeps the
// caller's last known offset instead of falling back to the start of the log.
//
// Run the tests: node chat-cursor.test.mjs

import { mkdirSync, readFileSync, renameSync, writeFileSync, existsSync } from 'node:fs'
import { dirname } from 'node:path'

/**
 * The saved byte offset into the log. On a missing file, a corrupt file or a
 * nonsensical value the caller's {@code lastKnown} wins, so one bad read cannot
 * rewind a running `wait` to the top of the log.
 */
export function cursorOffset(path, lastKnown = 0) {
  if (!existsSync(path)) return lastKnown
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8'))
    const offset = Number(parsed?.offset)
    return Number.isFinite(offset) && offset >= 0 ? offset : lastKnown
  } catch {
    return lastKnown
  }
}

/**
 * Saves the offset atomically: the full JSON lands in a sibling temp file first,
 * then a rename swaps it over the real cursor in one step.
 */
export function saveCursor(path, offset) {
  mkdirSync(dirname(path), { recursive: true })
  const tmp = `${path}.tmp`
  writeFileSync(tmp, JSON.stringify({ offset }))
  renameSync(tmp, path)
}
