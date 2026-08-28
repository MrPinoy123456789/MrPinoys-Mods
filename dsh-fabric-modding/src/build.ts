/**
 * `build_mod` tool. Runs a gradle task inside a workspace mod project, captures
 * the combined output, parses javac/Kotlin compile diagnostics and failed gradle
 * tasks into a structured result, and returns a concise log tail. Confined to
 * the workspace root: the mod directory must resolve inside it. Long builds can
 * run in the background through `ctx.jobs`, returning a job id the model polls
 * with `job_output` and stops with `job_kill`.
 *
 * Diagnostics are parsed from `--console=plain` output, so the tool never
 * depends on gradle's rich console escape sequences. The gradle wrapper is used
 * (`gradlew.bat` on Windows, `./gradlew` elsewhere); a system gradle is never
 * invoked, so the build runs against the pin each mod ships.
 * @module build
 */

import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { confine, exists } from './fsutil.ts'

/** Options passed from the plugin entry. */
export interface BuildOptions {
  workspaceRoot: string
  /** Lets the tool run gradle at all. Set false to disable builds entirely. */
  enableBuildRuns: boolean
}

/** One parsed compile diagnostic. */
interface Diagnostic {
  file: string
  line: number
  /** Column when the compiler reported one (newer javac and Kotlin). */
  column?: number
  severity: 'error' | 'warning'
  message: string
}

/** Foreground canonical result. */
interface ForegroundResult {
  kind: 'foreground'
  success: boolean
  mod: string
  task: string
  exitCode: number
  durationMs: number
  errors: Diagnostic[]
  warnings: Diagnostic[]
  failedTasks: string[]
  /** Tail of the combined output, capped for the model context. */
  logTail: string
}

/** Background canonical result: a job handle the model polls separately. */
interface BackgroundResult {
  kind: 'background'
  jobId: string
}

type BuildResult = ForegroundResult | BackgroundResult

/** Generic task outcome the jobs registry expects from the `done` producer. */
interface JobOutcome {
  status: 'completed' | 'killed'
  detail: string
}

/** Live handle returned by `runGradle`: a settling outcome plus control hooks. */
interface GradleHandle {
  /** Resolves to the exit code (or null when killed) with the full output. */
  done: Promise<{ exitCode: number | null; signal: NodeJS.Signals | null; output: string }>
  /** Kill the process synchronously. */
  kill: () => void
  /** Snapshot the current output tail without waiting. */
  readTail: () => string
}

/** Max bytes of combined output kept in memory; the tail is what the model sees. */
const OUTPUT_CAP = 2_000_000
/** Max chars of log tail returned to the model. */
const LOG_TAIL_CHARS = 4000
/** Default foreground timeout: 5 minutes. Gradle daemons can be slow to warm. */
const DEFAULT_TIMEOUT_MS = 300_000

/**
 * Spawn the mod's gradle wrapper for one task. Returns a handle with a settling
 * `done` promise, a synchronous `kill`, and a non-blocking `readTail`. The
 * caller owns the timeout and abort wiring for foreground, or delegates cancel
 * to the jobs producer for background.
 */
function runGradle(modDir: string, task: string, extraArgs: string[]): GradleHandle {
  const args = [task, '--console=plain', ...extraArgs]
  const isWin = process.platform === 'win32'
  const child = isWin
    ? spawn('cmd', ['/c', 'gradlew.bat', ...args], { cwd: modDir, windowsHide: true })
    : spawn('./gradlew', args, { cwd: modDir })

  let output = ''
  let killed = false
  const append = (chunk: Buffer): void => {
    output += chunk.toString('utf8')
    // Keep only the tail once over the cap, so memory stays bounded for huge logs.
    if (output.length > OUTPUT_CAP) output = output.slice(output.length - OUTPUT_CAP)
  }
  child.stdout?.on('data', append)
  child.stderr?.on('data', append)

  const done = new Promise<{ exitCode: number | null; signal: NodeJS.Signals | null; output: string }>((resolve) => {
    child.on('error', () => resolve({ exitCode: null, signal: null, output: output + '\n[spawn failed]\n' }))
    child.on('close', (code, signal) => resolve({ exitCode: code, signal, output }))
  })

  return {
    done,
    kill: () => { killed = true; child.kill('SIGTERM') },
    readTail: () => output.slice(-LOG_TAIL_CHARS),
  }
}

/**
 * Parse compile diagnostics and failed gradle tasks out of `--console=plain`
 * output. Recognizes javac (`file:line: error:` / `file:line:col: error:`) and
 * Kotlin (`e: file:line:col:`) shapes, plus gradle's `> Task :path FAILED`
 * markers. Best-effort: unparsed lines still reach the model via `logTail`.
 */
function parseDiagnostics(output: string): { errors: Diagnostic[]; warnings: Diagnostic[]; failedTasks: string[] } {
  const errors: Diagnostic[] = []
  const warnings: Diagnostic[] = []
  const failedTasks: string[] = []
  // javac / kotlinc shared shape: <file>:<line>[:<col>]: <severity>: <message>
  const javacRe = /^(.+?\.(?:java|kt)):(\d+)(?::(\d+))?:\s*(error|warning):\s*(.+)$/
  // Kotlin compiler short form: e: <file>:<line>:<col>: <message>  (warnings: w:)
  const kotlinRe = /^[ew]:\s+(.+?):(\d+):(\d+):\s*(.+)$/
  // gradle task failure: > Task :project:name FAILED  (project optional)
  const taskRe = /^>\s*Task\s+(:[\w.-]+(?::[\w.-]+)*)\s+FAILED$/
  for (const line of output.split(/\r?\n/)) {
    const j = javacRe.exec(line)
    if (j) {
      const d: Diagnostic = {
        file: j[1] as string,
        line: Number(j[2]),
        severity: (j[4] as string) === 'warning' ? 'warning' : 'error',
        message: (j[5] as string).trim(),
      }
      if (j[3]) d.column = Number(j[3])
      ;(d.severity === 'warning' ? warnings : errors).push(d)
      continue
    }
    const k = kotlinRe.exec(line)
    if (k) {
      const d: Diagnostic = {
        file: (k[1] as string).replace(/^file:\/\//, ''),
        line: Number(k[2]),
        column: Number(k[3]),
        severity: line.startsWith('w:') ? 'warning' : 'error',
        message: (k[4] as string).trim(),
      }
      ;(d.severity === 'warning' ? warnings : errors).push(d)
      continue
    }
    const t = taskRe.exec(line)
    if (t) failedTasks.push((t[1] as string).replace(/^:/, ''))
  }
  return { errors, warnings, failedTasks }
}

/** Build the foreground canonical result from a settled gradle run. */
function foregroundResult(mod: string, task: string, exitCode: number | null, output: string, durationMs: number): ForegroundResult {
  const { errors, warnings, failedTasks } = parseDiagnostics(output)
  const success = exitCode === 0
  return {
    kind: 'foreground',
    success,
    mod,
    task,
    exitCode: exitCode ?? -1,
    durationMs,
    errors,
    warnings,
    failedTasks,
    logTail: output.slice(-LOG_TAIL_CHARS),
  }
}

/** Validate a gradle task name: identifier-ish, no shell metacharacters. */
function validTask(task: string): boolean {
  return /^[A-Za-z][A-Za-z0-9_:.-]{0,63}$/.test(task)
}

/** Validate one extra gradle arg: must start with `-` or be a plain token. */
function validArg(arg: string): boolean {
  if (arg.length > 256) return false
  return /^[-A-Za-z0-9_:=.\/\\]+$/.test(arg)
}

/** The shared output schema for both foreground and background results. */
const buildOutput = {
  schema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      kind: { type: 'string', required: true, enum: ['foreground', 'background'] },
      success: { type: 'boolean' },
      mod: { type: 'string' },
      task: { type: 'string' },
      exitCode: { type: 'integer' },
      durationMs: { type: 'integer' },
      errors: {
        type: 'array',
        items: {
          type: 'object',
          additionalProperties: false,
          properties: {
            file: { type: 'string', required: true },
            line: { type: 'integer', required: true },
            column: { type: 'integer' },
            severity: { type: 'string', required: true, enum: ['error', 'warning'] },
            message: { type: 'string', required: true },
          },
        },
      },
      warnings: {
        type: 'array',
        items: {
          type: 'object',
          additionalProperties: false,
          properties: {
            file: { type: 'string', required: true },
            line: { type: 'integer', required: true },
            column: { type: 'integer' },
            severity: { type: 'string', required: true, enum: ['error', 'warning'] },
            message: { type: 'string', required: true },
          },
        },
      },
      failedTasks: { type: 'array', items: { type: 'string' } },
      logTail: { type: 'string' },
      jobId: { type: 'string' },
    },
  },
  render: (_args, value: BuildResult) => {
    if (value.kind === 'background') {
      return [{ type: 'text', text: `started background job ${value.jobId} (collect with job_output, stop with job_kill)` }]
    }
    const status = value.success ? 'SUCCESS' : 'FAILED'
    const diag = `${value.errors.length} error(s), ${value.warnings.length} warning(s)`
    const tasks = value.failedTasks.length > 0 ? `; failed tasks: ${value.failedTasks.join(', ')}` : ''
    const head = `BUILD ${status} mod=${value.mod} task=${value.task} exit=${value.exitCode} (${value.durationMs}ms); ${diag}${tasks}`
    const body = value.logTail.length > 0 ? `\n\n${value.logTail}` : ''
    return [{ type: 'text', text: head + body }]
  },
}

/** Register the `build_mod` tool on `ctx.tools`. */
export function registerBuildTool(ctx: Context, opts: BuildOptions): void {
  const root = opts.workspaceRoot

  ctx.tools.register(defineTool({
    name: 'build_mod',
    description: 'Run a gradle task (default "build") inside a workspace mod project and return a structured result: exit code, parsed compile errors and warnings (file, line, column, message), failed gradle tasks, and a log tail. Confined to the workspace root. Use this to verify a change compiles and tests pass before declaring it done. For long builds, set run_in_background=true to get a job id you poll with job_output and stop with job_kill. The mod must have a gradle wrapper (gradlew / gradlew.bat).',
    parameters: {
      mod: { type: 'string', required: true, description: 'Mod directory name relative to the workspace root (e.g. "pocketdungeons"). Must be inside the workspace.' },
      task: { type: 'string', description: 'Gradle task to run. Default "build". Examples: "build", "test", "doorMaskTest", "dist".' },
      args: { type: 'array', items: { type: 'string' }, description: 'Extra gradle arguments (e.g. ["--stacktrace", "--refresh-dependencies"]). No shell metacharacters.' },
      timeoutMs: { type: 'integer', description: 'Foreground timeout in milliseconds. Default 300000 (5 min). Ignored when run_in_background is true.' },
      run_in_background: { type: 'boolean', description: 'Run in the background and return a job id immediately. Poll with job_output, stop with job_kill. No timeout applies.' },
    },
    output: buildOutput,
    async execute(args, exec) {
      if (!opts.enableBuildRuns) throw new Error('build_mod is disabled (enableBuildRuns: false)')
      const mod = args.mod
      const modDir = confine(root, mod)
      const wrapper = path.join(modDir, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')
      if (!(await exists(wrapper))) {
        throw new Error(`no gradle wrapper at ${wrapper}; pass a mod directory that contains gradlew/gradlew.bat`)
      }
      const task = args.task ?? 'build'
      if (!validTask(task)) throw new Error(`invalid gradle task name: ${task}`)
      const extraArgs = args.args ?? []
      for (const a of extraArgs) {
        if (!validArg(a)) throw new Error(`invalid gradle argument (metacharacters rejected): ${a}`)
      }
      const handle = runGradle(modDir, task, extraArgs)

      // Background: register a job and return the handle immediately.
      if (args.run_in_background === true) {
        const jobs = (ctx as Context & { get?: (k: string) => unknown }).get?.('jobs') as
          | { start: (r: { kind: string; label: string; owner?: unknown; run: () => { cancel: () => void; done: Promise<JobOutcome>; readOutput: () => string } }) => string }
          | undefined
        if (!jobs) throw new Error('background jobs unavailable: load @deepseek-ai/dsh-jobs and @deepseek-ai/dsh-tool-jobs')
        if (exec.signal.aborted) { handle.kill(); throw new Error('tool call aborted') }
        const owner = (exec as { agent?: unknown }).agent
        const jobId = jobs.start({
          kind: 'gradle',
          label: `gradle ${task} (${mod})`,
          ...(owner ? { owner } : {}),
          run: () => {
            return {
              cancel: () => handle.kill(),
              done: handle.done.then((r) => {
                if (r.exitCode === null) return { status: 'killed', detail: r.signal ? `signal: ${r.signal}` : 'killed before exit' }
                return { status: 'completed', detail: `exit code: ${r.exitCode}` }
              }),
              readOutput: () => {
                const tail = handle.readTail()
                return tail.length > 0 ? tail : '(no output yet)'
              },
            }
          },
        })
        return { kind: 'background', jobId } satisfies BackgroundResult
      }

      // Foreground: race the build against the tool-call signal and a timeout.
      const timeoutMs = args.timeoutMs ?? DEFAULT_TIMEOUT_MS
      const timer = new Promise<{ exitCode: null; signal: NodeJS.Signals | null; output: string }>((resolve) => {
        const t = setTimeout(() => {
          handle.kill()
          resolve({ exitCode: null, signal: null, output: handle.readTail() + '\n[timed out]\n' })
        }, timeoutMs)
        exec.signal.addEventListener('abort', () => { clearTimeout(t); handle.kill() }, { once: true })
      })
      const start = Date.now()
      const settled = await Promise.race([handle.done, timer])
      if (exec.signal.aborted) throw new Error('tool call aborted')
      return foregroundResult(mod, task, settled.exitCode, settled.output, Date.now() - start)
    },
  }))
}
