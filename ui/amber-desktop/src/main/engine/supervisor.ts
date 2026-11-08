import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { EventEmitter } from 'node:events'
import { existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { RPC_ERROR, type EngineInfo } from '../../shared/protocol'
import type { EngineStatus } from '../../shared/api'
import { JsonRpcClient, LineSplitter, RpcError, errorMessage, type RequestOptions } from './rpc-client'
import { tm } from '../i18n'

export interface EngineCommand {
  command: string
  args: string[]
  /** Where the jar was expected; used in error messages. */
  jar: string
}

export interface ResolveOptions {
  packaged: boolean
  resourcesPath: string
  appRoot: string
  platform: NodeJS.Platform
  env: NodeJS.ProcessEnv
  exists?: (path: string) => boolean
}

/**
 * JVM options for every engine start: the heap may use at most half of the machine's memory, an
 * outer guard so a pathological script cannot grow it without bound and starve the rest of the system.
 */
export const ENGINE_JVM_OPTIONS = ['-XX:MaxRAMPercentage=50']

/** Works out how to start the engine, in the packaged app or in development. */
export function resolveEngineCommand(options: ResolveOptions): EngineCommand {
  const exists = options.exists ?? existsSync
  const javaExe = options.platform === 'win32' ? 'java.exe' : 'java'
  if (options.packaged) {
    const engineDir = join(options.resourcesPath, 'engine')
    const jar = join(engineDir, 'engine.jar')
    const jsa = join(engineDir, 'amber.jsa')
    const args: string[] = [...ENGINE_JVM_OPTIONS]
    if (exists(jsa)) args.push(`-XX:SharedArchiveFile=${jsa}`)
    args.push('-jar', jar, 'serve')
    return { command: join(options.resourcesPath, 'runtime', 'bin', javaExe), args, jar }
  }
  // A relative AMBER_ENGINE_JAR (as in .env) is relative to ui/amber-desktop.
  const jar = options.env.AMBER_ENGINE_JAR
    ? resolve(options.appRoot, options.env.AMBER_ENGINE_JAR)
    : resolve(options.appRoot, '..', '..', 'backend', 'amber-core', 'target', 'engine', 'engine.jar')
  // AMBER_JAVA (development .env) wins, then $JAVA_HOME, then java on the PATH.
  const javaHome = options.env.JAVA_HOME
  const fromHome = javaHome ? join(javaHome, 'bin', javaExe) : null
  const command = options.env.AMBER_JAVA ? options.env.AMBER_JAVA : fromHome && exists(fromHome) ? fromHome : javaExe
  return { command, args: [...ENGINE_JVM_OPTIONS, '-jar', jar, 'serve'], jar }
}

export interface SupervisorOptions {
  command: EngineCommand
  /** Restarts allowed inside `restartWindowMs` before giving up. */
  maxRestarts?: number
  restartWindowMs?: number
  log?: (line: string) => void
}

/**
 * Keeps one engine process running. Emits `status` with an EngineStatus whenever it changes.
 * Pending requests fail with ENGINE_UNAVAILABLE when the process exits; it is then restarted.
 */
export class EngineSupervisor extends EventEmitter {
  private child: ChildProcessWithoutNullStreams | null = null
  private client: JsonRpcClient | null = null
  private ready: Promise<void> | null = null
  private restarts: number[] = []
  private stopping = false
  private status: EngineStatus = { state: 'stopped' }
  private readonly maxRestarts: number
  private readonly restartWindowMs: number

  constructor(private readonly options: SupervisorOptions) {
    super()
    this.maxRestarts = options.maxRestarts ?? 5
    this.restartWindowMs = options.restartWindowMs ?? 60_000
  }

  getStatus(): EngineStatus {
    return this.status
  }

  isKnownUnsupported(method: string): boolean {
    return this.client?.isKnownUnsupported(method) ?? false
  }

  /** Starts the engine if needed and resolves when `engine.info` has answered. */
  start(): Promise<void> {
    if (this.ready) return this.ready
    this.stopping = false
    this.ready = this.spawnEngine()
    this.ready.catch(() => {
      this.ready = null
    })
    return this.ready
  }

  async request<T>(method: string, params?: unknown, options?: RequestOptions): Promise<T> {
    if (this.status.state === 'failed') this.ready = null
    await this.start()
    const client = this.client
    if (!client) throw new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, tm('main.engine.notRunning'))
    return client.request<T>(method, params, options)
  }

  async restart(): Promise<void> {
    this.restarts = []
    await this.stop()
    await this.start()
  }

  stop(): Promise<void> {
    this.stopping = true
    this.ready = null
    const child = this.child
    if (!child) return Promise.resolve()
    return new Promise((resolveStop) => {
      const timer = setTimeout(() => child.kill('SIGKILL'), 3000)
      child.once('exit', () => {
        clearTimeout(timer)
        resolveStop()
      })
      child.stdin.end()
      child.kill()
    })
  }

  private setStatus(status: EngineStatus): void {
    this.status = status
    this.emit('status', status)
  }

  private spawnEngine(): Promise<void> {
    const { command, args, jar } = this.options.command
    const restarting = this.restarts.length > 0
    this.setStatus({ state: restarting ? 'restarting' : 'starting' })

    return new Promise<void>((resolveReady, rejectReady) => {
      let child: ChildProcessWithoutNullStreams
      try {
        child = spawn(command, args, { stdio: 'pipe', windowsHide: true })
      } catch (error) {
        const message = tm('main.engine.couldNotStart', { reason: errorMessage(error) })
        this.setStatus({ state: 'failed', message })
        rejectReady(new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, message))
        return
      }
      this.child = child
      const client = new JsonRpcClient((line) => {
        if (!child.stdin.writable) throw new Error(tm('main.engine.notRunning'))
        child.stdin.write(line + '\n')
      })
      this.client = client
      const splitter = new LineSplitter((line) => client.handleLine(line))
      child.stdout.on('data', (chunk: Buffer) => splitter.push(chunk))
      const stderr = new LineSplitter((line) => this.options.log?.(line))
      const stderrTail: string[] = []
      child.stderr.on('data', (chunk: Buffer) => {
        stderr.push(chunk)
        stderrTail.push(chunk.toString('utf8'))
        if (stderrTail.length > 20) stderrTail.shift()
      })
      child.stdin.on('error', () => {
        // EPIPE when the process dies; handled by the exit handler.
      })

      let settled = false
      child.on('error', (error) => {
        const missing = (error as NodeJS.ErrnoException).code === 'ENOENT'
        const message = missing
          ? tm('main.engine.javaMissing', { command })
          : tm('main.engine.couldNotStart', { reason: error.message })
        this.child = null
        this.client = null
        client.failAll(new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, message))
        this.setStatus({ state: 'failed', message })
        if (!settled) {
          settled = true
          rejectReady(new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, message))
        }
      })

      child.on('exit', (code, signal) => {
        if (this.child === child) {
          this.child = null
          this.client = null
        }
        const reason = signal ? `signal ${signal}` : `exit code ${code}`
        client.failAll(new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, tm('main.engine.stopped', { reason })))
        if (!settled) {
          settled = true
          const tail = stderrTail.join('').trim().split('\n').slice(-3).join(' ')
          const hint = existsSync(jar) ? tail : tm('main.engine.jarMissing', { path: jar })
          const message = tm('main.engine.exitedAtStart', { reason, hint }).trim()
          this.setStatus({ state: 'failed', message })
          rejectReady(new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, message))
          return
        }
        if (this.stopping) {
          this.setStatus({ state: 'stopped' })
          return
        }
        this.scheduleRestart(reason)
      })

      client
        .request<EngineInfo>('engine.info', undefined, { timeoutMs: 60_000 })
        .then((info) => {
          settled = true
          this.setStatus({ state: 'ready', info })
          resolveReady()
        })
        .catch((error: unknown) => {
          if (settled) return
          settled = true
          const message = tm('main.engine.noAnswer', { reason: errorMessage(error) })
          this.setStatus({ state: 'failed', message })
          child.kill()
          rejectReady(new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, message))
        })
    })
  }

  private scheduleRestart(reason: string): void {
    const now = Date.now()
    this.restarts = this.restarts.filter((t) => now - t < this.restartWindowMs)
    if (this.restarts.length >= this.maxRestarts) {
      this.ready = null
      this.setStatus({ state: 'failed', message: tm('main.engine.keepsStopping', { reason }) })
      return
    }
    this.restarts.push(now)
    const delay = Math.min(250 * 2 ** (this.restarts.length - 1), 4000)
    this.ready = new Promise<void>((resolveLater, rejectLater) => {
      setTimeout(() => {
        this.spawnEngine().then(resolveLater, rejectLater)
      }, delay)
    })
    this.ready.catch(() => {
      this.ready = null
    })
  }
}
