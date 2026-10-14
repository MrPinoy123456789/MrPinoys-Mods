// Checks the chat cursor's failure modes (PD-118): a torn or corrupt cursor file
// must not rewind a reader to the start of the log, and writes must land
// atomically so a concurrent reader never sees half the JSON.
// Run: node chat-cursor.test.mjs

import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { cursorOffset, saveCursor } from './chat-cursor.mjs'

const dir = mkdtempSync(join(tmpdir(), 'cursor-test-'))
const path = join(dir, 'cursor.json')

try {
  // A missing file reads as the caller's offset (0 for a fresh process).
  assert.equal(cursorOffset(path), 0)
  assert.equal(cursorOffset(path, 457312), 457312, 'a missing file keeps the last known offset')

  // A normal round trip.
  saveCursor(path, 457312)
  assert.equal(cursorOffset(path), 457312, 'a saved offset reads back')
  assert.equal(existsSync(`${path}.tmp`), false, 'the temp file is renamed away, not left behind')

  // PD-118's failure: a torn write leaves half the JSON. The old code swallowed
  // that and silently reset to 0, replaying the whole log into `wait`.
  writeFileSync(path, '{"offset": 45')
  assert.equal(cursorOffset(path, 457312), 457312, 'a truncated cursor keeps the last known offset')
  writeFileSync(path, 'not json at all')
  assert.equal(cursorOffset(path, 457312), 457312, 'a garbage cursor keeps the last known offset')
  writeFileSync(path, '{"offset": "later"}')
  assert.equal(cursorOffset(path, 457312), 457312, 'a non-numeric offset keeps the last known offset')
  writeFileSync(path, '{"offset": -5}')
  assert.equal(cursorOffset(path, 457312), 457312, 'a negative offset keeps the last known offset')
  writeFileSync(path, '{}')
  assert.equal(cursorOffset(path, 457312), 457312, 'a cursor with no offset keeps the last known offset')

  // With no fallback a corrupt file still reads as 0 (fresh start, not a crash).
  assert.equal(cursorOffset(path), 0, 'corrupt with no fallback starts over')

  // A saved cursor is always complete JSON, so a reader during the swap sees
  // either the old file or the new one, never a torn one.
  saveCursor(path, 99)
  assert.equal(JSON.parse(readFileSync(path, 'utf8')).offset, 99, 'the written file parses whole')

  console.log('chat-cursor.test.mjs passed')
} finally {
  rmSync(dir, { recursive: true, force: true })
}
