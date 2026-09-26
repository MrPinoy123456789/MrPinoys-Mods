import { unzlibSync } from 'fflate'

/**
 * A small PNG decoder (non interlaced, every colour type and bit depth the
 * vanilla block textures use). Written here rather than going through a
 * canvas so the same atlas code runs in the browser and in the Node checks.
 */

export interface Rgba {
  width: number
  height: number
  data: Uint8Array
}

const SIG = [137, 80, 78, 71, 13, 10, 26, 10]

export function decodePng(bytes: Uint8Array): Rgba {
  for (let i = 0; i < 8; i++) if (bytes[i] !== SIG[i]) throw new Error('not a PNG')
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let off = 8
  let width = 0, height = 0, depth = 0, ctype = 0, interlace = 0
  let palette: Uint8Array | null = null
  let trns: Uint8Array | null = null
  const idat: Uint8Array[] = []
  while (off < bytes.length) {
    const len = dv.getUint32(off)
    const type = String.fromCharCode(bytes[off + 4], bytes[off + 5], bytes[off + 6], bytes[off + 7])
    const body = bytes.subarray(off + 8, off + 8 + len)
    if (type === 'IHDR') {
      width = dv.getUint32(off + 8)
      height = dv.getUint32(off + 12)
      depth = body[8]
      ctype = body[9]
      interlace = body[12]
    } else if (type === 'PLTE') palette = body
    else if (type === 'tRNS') trns = body
    else if (type === 'IDAT') idat.push(body)
    else if (type === 'IEND') break
    off += 12 + len
  }
  if (interlace !== 0) throw new Error('interlaced PNG is not supported')
  let total = 0
  for (const c of idat) total += c.length
  const z = new Uint8Array(total)
  let p = 0
  for (const c of idat) { z.set(c, p); p += c.length }
  const raw = unzlibSync(z)

  const channels = ctype === 0 ? 1 : ctype === 2 ? 3 : ctype === 3 ? 1 : ctype === 4 ? 2 : 4
  const bitsPerPixel = channels * depth
  const bpp = Math.max(1, bitsPerPixel >> 3)
  const stride = Math.ceil((width * bitsPerPixel) / 8)
  const img = new Uint8Array(stride * height)
  let prev = new Uint8Array(stride)
  let rp = 0
  for (let y = 0; y < height; y++) {
    const filter = raw[rp++]
    const line = img.subarray(y * stride, (y + 1) * stride)
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? line[x - bpp] : 0
      const b = prev[x]
      const c = x >= bpp ? prev[x - bpp] : 0
      let v = raw[rp++]
      switch (filter) {
        case 1: v += a; break
        case 2: v += b; break
        case 3: v += (a + b) >> 1; break
        case 4: {
          const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c)
          v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c
          break
        }
      }
      line[x] = v & 0xff
    }
    prev = line
  }

  const out = new Uint8Array(width * height * 4)
  const sample = (line: Uint8Array, idx: number): number => {
    if (depth === 8) return line[idx]
    if (depth === 16) return line[idx * 2]
    const perByte = 8 / depth
    const byte = line[Math.floor(idx / perByte)]
    const shift = 8 - depth - (idx % perByte) * depth
    return (byte >> shift) & ((1 << depth) - 1)
  }
  const scale = depth < 8 ? 255 / ((1 << depth) - 1) : 1
  const trnsGray = trns && ctype === 0 ? ((trns[0] << 8) | trns[1]) : -1
  for (let y = 0; y < height; y++) {
    const line = img.subarray(y * stride, (y + 1) * stride)
    for (let x = 0; x < width; x++) {
      const o = (y * width + x) * 4
      if (ctype === 3) {
        const i = sample(line, x)
        out[o] = palette ? palette[i * 3] : 0
        out[o + 1] = palette ? palette[i * 3 + 1] : 0
        out[o + 2] = palette ? palette[i * 3 + 2] : 0
        out[o + 3] = trns && i < trns.length ? trns[i] : 255
      } else if (ctype === 0) {
        const g = sample(line, x)
        const v = Math.round(g * scale)
        out[o] = out[o + 1] = out[o + 2] = v
        out[o + 3] = g === trnsGray ? 0 : 255
      } else if (ctype === 4) {
        out[o] = out[o + 1] = out[o + 2] = sample(line, x * 2)
        out[o + 3] = sample(line, x * 2 + 1)
      } else if (ctype === 2) {
        out[o] = sample(line, x * 3)
        out[o + 1] = sample(line, x * 3 + 1)
        out[o + 2] = sample(line, x * 3 + 2)
        out[o + 3] = 255
      } else {
        out[o] = sample(line, x * 4)
        out[o + 1] = sample(line, x * 4 + 1)
        out[o + 2] = sample(line, x * 4 + 2)
        out[o + 3] = sample(line, x * 4 + 3)
      }
    }
  }
  return { width, height, data: out }
}
