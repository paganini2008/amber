import { StringDecoder } from 'node:string_decoder'
import { RPC_ERROR, type RpcErrorObject } from '../../shared/protocol'
import { tm } from '../i18n'

export class RpcError extends Error {
  readonly code: number
  readonly data: unknown

  constructor(code: number, message: string, data?: unknown) {
    super(message)
    this.name = 'RpcError'
    this.code = code
    this.data = data
  }

  toObject(): RpcErrorObject {
    return this.data === undefined
      ? { code: this.code, message: this.message }
      : { code: this.code, message: this.message, data: this.data }
  }

  get isMethodNotFound(): boolean {
    return this.code === RPC_ERROR.METHOD_NOT_FOUND
  }
}

export interface RequestOptions {
  /** Aborting sends `$/cancel` to the engine and rejects with -32800. */
  signal?: AbortSignal
  /** Rejects with a timeout error and cancels the engine request. 0 or undefined = no timeout. */
  timeoutMs?: number
}

interface Pending {
  method: string
  resolve: (value: unknown) => void
  reject: (error: RpcError) => void
  cleanup: () => void
}

/**
 * Splits a byte or string stream into lines. Handles chunks that end mid-line and mid-character.
 */
export class LineSplitter {
  /**
   * The unfinished line, as pieces. Only new chunks are searched for a line break and the pieces are
   * joined once per line: a large answer (100 MB on one line for a huge script) used to be rescanned
   * and copied for every 64 KB chunk, which took minutes.
   */
  private pending: string[] = []
  private readonly decoder = new StringDecoder('utf8')

  constructor(private readonly onLine: (line: string) => void) {}

  push(chunk: Buffer | string): void {
    let text = typeof chunk === 'string' ? chunk : this.decoder.write(chunk)
    let index: number
    while ((index = text.indexOf('\n')) >= 0) {
      this.pending.push(text.slice(0, index))
      text = text.slice(index + 1)
      let line = this.pending.length === 1 ? this.pending[0]! : this.pending.join('')
      this.pending = []
      if (line.endsWith('\r')) line = line.slice(0, -1)
      if (line.trim().length > 0) this.onLine(line)
    }
    if (text) this.pending.push(text)
  }

  reset(): void {
    this.pending = []
  }
}

/**
 * JSON-RPC 2.0 client over a line-oriented transport. Transport-agnostic: give it a `send`
 * function and feed it each received line through `handleLine`.
 */
export class JsonRpcClient {
  private nextId = 1
  private readonly pending = new Map<number, Pending>()
  private readonly unsupported = new Set<string>()

  constructor(
    private readonly send: (line: string) => void,
    private readonly onNotification?: (method: string, params: unknown) => void
  ) {}

  /** Methods the engine answered with -32601. */
  isKnownUnsupported(method: string): boolean {
    return this.unsupported.has(method)
  }

  get pendingCount(): number {
    return this.pending.size
  }

  request<T>(method: string, params?: unknown, options: RequestOptions = {}): Promise<T> {
    const id = this.nextId++
    return new Promise<T>((resolve, reject) => {
      if (options.signal?.aborted) {
        reject(new RpcError(RPC_ERROR.REQUEST_CANCELLED, tm('main.engine.cancelled')))
        return
      }
      let timer: ReturnType<typeof setTimeout> | undefined
      const onAbort = (): void => this.cancel(id)
      const cleanup = (): void => {
        if (timer) clearTimeout(timer)
        options.signal?.removeEventListener('abort', onAbort)
      }
      this.pending.set(id, {
        method,
        resolve: (value) => resolve(value as T),
        reject,
        cleanup
      })
      options.signal?.addEventListener('abort', onAbort, { once: true })
      if (options.timeoutMs && options.timeoutMs > 0) {
        timer = setTimeout(() => {
          this.notify('$/cancel', { id })
          this.settle(id, new RpcError(RPC_ERROR.TIMEOUT, tm('main.engine.timedOut', { method })))
        }, options.timeoutMs)
      }
      const message: Record<string, unknown> = { jsonrpc: '2.0', id, method }
      if (params !== undefined) message.params = params
      try {
        this.send(JSON.stringify(message))
      } catch (error) {
        this.settle(id, new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, errorMessage(error, tm('main.engine.notRunning'))))
      }
    })
  }

  notify(method: string, params?: unknown): void {
    const message: Record<string, unknown> = { jsonrpc: '2.0', method }
    if (params !== undefined) message.params = params
    try {
      this.send(JSON.stringify(message))
    } catch {
      // The transport is gone; nothing to notify.
    }
  }

  /** Cancels a pending request: tells the engine and rejects locally right away. */
  cancel(id: number): void {
    if (!this.pending.has(id)) return
    this.notify('$/cancel', { id })
    this.settle(id, new RpcError(RPC_ERROR.REQUEST_CANCELLED, tm('main.engine.cancelled')))
  }

  /** Rejects every pending request, e.g. when the engine process exits. */
  failAll(error: RpcError): void {
    for (const id of [...this.pending.keys()]) this.settle(id, error)
  }

  handleLine(line: string): void {
    let message: unknown
    try {
      message = JSON.parse(line)
    } catch {
      return // Not JSON; engines log to stderr, so ignore stray output.
    }
    if (!message || typeof message !== 'object') return
    const m = message as { id?: unknown; method?: unknown; params?: unknown; result?: unknown; error?: unknown }
    if (typeof m.method === 'string' && m.id === undefined) {
      this.onNotification?.(m.method, m.params)
      return
    }
    if (typeof m.id !== 'number') return
    if (m.error && typeof m.error === 'object') {
      const e = m.error as { code?: unknown; message?: unknown; data?: unknown }
      const code = typeof e.code === 'number' ? e.code : RPC_ERROR.INTERNAL_ERROR
      const pending = this.pending.get(m.id)
      if (pending && code === RPC_ERROR.METHOD_NOT_FOUND) this.unsupported.add(pending.method)
      this.settle(m.id, new RpcError(code, typeof e.message === 'string' ? e.message : 'Engine error', e.data))
      return
    }
    const pending = this.pending.get(m.id)
    if (!pending) return
    this.pending.delete(m.id)
    pending.cleanup()
    pending.resolve(m.result)
  }

  private settle(id: number, error: RpcError): void {
    const pending = this.pending.get(id)
    if (!pending) return
    this.pending.delete(id)
    pending.cleanup()
    pending.reject(error)
  }
}

export function errorMessage(error: unknown, fallback = 'Unknown error'): string {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return fallback
}

export function toRpcError(error: unknown): RpcError {
  if (error instanceof RpcError) return error
  return new RpcError(RPC_ERROR.INTERNAL_ERROR, errorMessage(error))
}
