import type { mat4 } from 'gl-matrix'

/**
 * Translucent boxes and lines drawn over the deepslate render: door slots,
 * the window band, the bedrock envelope, the selection, hover outlines.
 */

export type Rgba = [number, number, number, number]

const VS = `
attribute vec3 pos;
attribute vec4 color;
uniform mat4 mView;
uniform mat4 mProj;
varying lowp vec4 vColor;
void main(void) {
  gl_Position = mProj * mView * vec4(pos, 1.0);
  vColor = color;
}`

const FS = `
precision mediump float;
varying lowp vec4 vColor;
void main(void) { gl_FragColor = vColor; }`

function compile(gl: WebGLRenderingContext, type: number, src: string): WebGLShader {
  const s = gl.createShader(type)!
  gl.shaderSource(s, src)
  gl.compileShader(s)
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? 'shader error')
  return s
}

export class OverlayBatch {
  tris: number[] = []
  triColors: number[] = []
  lines: number[] = []
  lineColors: number[] = []

  clear(): void {
    this.tris.length = 0
    this.triColors.length = 0
    this.lines.length = 0
    this.lineColors.length = 0
  }

  line(a: number[], b: number[], c: Rgba): void {
    this.lines.push(...a, ...b)
    this.lineColors.push(...c, ...c)
  }

  quad(a: number[], b: number[], c: number[], d: number[], col: Rgba): void {
    this.tris.push(...a, ...b, ...c, ...a, ...c, ...d)
    for (let i = 0; i < 6; i++) this.triColors.push(...col)
  }

  /** A box from (x0,y0,z0) to (x1,y1,z1), optionally filled and outlined. */
  box(x0: number, y0: number, z0: number, x1: number, y1: number, z1: number, fill: Rgba | null, edge: Rgba | null): void {
    if (fill) {
      this.quad([x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1], fill)
      this.quad([x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0], fill)
      this.quad([x0, y0, z0], [x0, y1, z0], [x1, y1, z0], [x1, y0, z0], fill)
      this.quad([x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1], fill)
      this.quad([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0], fill)
      this.quad([x1, y0, z0], [x1, y1, z0], [x1, y1, z1], [x1, y0, z1], fill)
    }
    if (edge) {
      const c: number[][] = [
        [x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1],
        [x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1],
      ]
      for (const [i, j] of [[0, 1], [1, 2], [2, 3], [3, 0], [4, 5], [5, 6], [6, 7], [7, 4], [0, 4], [1, 5], [2, 6], [3, 7]]) {
        this.line(c[i], c[j], edge)
      }
    }
  }

  /** One block cell, inset slightly so it does not z-fight the block faces. */
  cell(x: number, y: number, z: number, fill: Rgba | null, edge: Rgba | null, inset = -0.01): void {
    this.box(x + inset, y + inset, z + inset, x + 1 - inset, y + 1 - inset, z + 1 - inset, fill, edge)
  }
}

export class OverlayRenderer {
  private readonly program: WebGLProgram
  private readonly posBuf: WebGLBuffer
  private readonly colBuf: WebGLBuffer

  constructor(private readonly gl: WebGLRenderingContext) {
    const p = gl.createProgram()!
    gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, VS))
    gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, FS))
    gl.linkProgram(p)
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p) ?? 'link error')
    this.program = p
    this.posBuf = gl.createBuffer()!
    this.colBuf = gl.createBuffer()!
  }

  draw(batch: OverlayBatch, view: mat4, proj: mat4, depthTest: boolean): void {
    const gl = this.gl
    gl.useProgram(this.program)
    gl.uniformMatrix4fv(gl.getUniformLocation(this.program, 'mView'), false, view)
    gl.uniformMatrix4fv(gl.getUniformLocation(this.program, 'mProj'), false, proj)
    const posLoc = gl.getAttribLocation(this.program, 'pos')
    const colLoc = gl.getAttribLocation(this.program, 'color')
    if (depthTest) gl.enable(gl.DEPTH_TEST)
    else gl.disable(gl.DEPTH_TEST)
    gl.disable(gl.CULL_FACE)
    gl.enable(gl.BLEND)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
    const send = (pos: number[], col: number[], mode: number) => {
      if (pos.length === 0) return
      gl.bindBuffer(gl.ARRAY_BUFFER, this.posBuf)
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(pos), gl.DYNAMIC_DRAW)
      gl.vertexAttribPointer(posLoc, 3, gl.FLOAT, false, 0, 0)
      gl.enableVertexAttribArray(posLoc)
      gl.bindBuffer(gl.ARRAY_BUFFER, this.colBuf)
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(col), gl.DYNAMIC_DRAW)
      gl.vertexAttribPointer(colLoc, 4, gl.FLOAT, false, 0, 0)
      gl.enableVertexAttribArray(colLoc)
      gl.drawArrays(mode, 0, pos.length / 3)
    }
    gl.depthMask(false)
    send(batch.tris, batch.triColors, gl.TRIANGLES)
    gl.depthMask(true)
    send(batch.lines, batch.lineColors, gl.LINES)
    gl.disableVertexAttribArray(colLoc)
    gl.disableVertexAttribArray(posLoc)
    gl.enable(gl.DEPTH_TEST)
    gl.enable(gl.CULL_FACE)
  }
}
